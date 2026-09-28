# Архитектура

Сдаваемый артефакт - один процесс Spring Boot. Клиент (скрипт жюри, curl, Swagger) загружает GeoJSON и скачивает результат. Визуальная проверка делается в QGIS и в состав runtime не входит. Каталог `frontend/` в репозитории пустой: веб-карта в оценку не входит.

## Процесс

```
клиент
  │  POST /api/files          multipart GeoJSON, до 3 ГБ
  │  POST /api/jobs           { fileId, enableDepth }
  ▼
JobService                    пул из 2 потоков, очередь 100
  │
  ▼
CalculationPipeline
  1. IngestService            поток JSON, модель сети
  2. VariantGenerator         до 3 стратегий
       NetworkPlanner         дерево
         RoutingPipeline      путь
           SpatialConstraint  можно ли провести отрезок
       EngineeringCalculator  расходы и Ду
       VariantCostCalculator  C, L, S
  3. DepthTracer              только если enableDepth
  4. GeoJsonExporter          FeatureCollection
  ▼
data/jobs/{jobId}/result.geojson
```

Стадии, которые видит `GET /api/jobs/{id}`: `INGEST` → `PLAN` → `COST` → (`DEPTH`) → `EXPORT` → `DONE`. Ошибка переводит задачу в `FAILED`.

Идентификаторы новой сети общие на один расчёт, поэтому генерация вариантов одного задания сериализована внутри `CalculationPipeline`. Два задания могут считаться параллельно: пул `core = 2`, `max = 2`. Пятьдесят пользователей в ТЗ - это опрос статуса, а не пятьдесят одновременных трассировок. При переполнении очереди `POST /api/jobs` отклоняется.

## Слои Spring Boot

Процесс один JAR (`HeatnetApplication`). Расчётное ядро - обычные `@Service` без отдельного микросервиса.

| Слой | Пакет | Роль |
|---|---|---|
| HTTP | `api` | Контроллеры, `ApiExceptionHandler`, springdoc |
| Оркестрация задач | `jobs` | `JobService`, `CalculationPipeline`, пул `JobExecutorConfig` |
| Предметная логика | `ingest`, `rules`, `routing`, `network`, `calc`, `cost`, `variants`, `depth`, `export` | Чистые сервисы и статические помощники; геометрия в JTS |
| Справочники | `calc.reference` + `config` | `ReferenceData` после загрузки YAML |
| CLI | `cli` | `ContestCliRunner` - тот же `CalculationPipeline`, выход по `System.exit` |

Контроллеры не вызывают планировщик напрямую: `FileUploadController` → `UploadIngestService`, `JobController` → `JobService` → `CalculationPipeline`. Так HTTP и командная строка делят один конвейер.

## Жизненный цикл HTTP-задачи

```
POST /api/files
  FileUploadController.upload
    UploadIngestService.save  -> data/uploads/{fileId}/input.geojson
    IngestService.ingest      -> отчёт для ответа и GET .../report

POST /api/jobs { fileId, enableDepth }
  JobController.create
    JobService.submit
      JobRecord в ConcurrentHashMap, status QUEUED
      calculationExecutor.execute -> JobService.execute

JobService.execute (фоновый поток)
  stage INGEST, progress 5
  CalculationPipeline.run(Path, FileOutputStream, enableDepth, job::updateProgress)
  при успехе: DONE, resultPath, explanations
  при ошибке: FAILED, message, удаление недописанного result.geojson

GET /api/jobs/{id}/result
  JobController.result -> JobService.result -> Path на диске
  query variant=vA -> VariantSplitter во временный файл, отдача attachment
```

`JobRecord` хранит `status`, `stage`, `progress`, путь к файлу и карту `explanations` (ключ - `id` объекта из GeoJSON). После перезапуска контейнера записи в памяти пропадают, но файл в `data/jobs/{id}/` на смонтированном томе хоста остаётся.

Прогресс приблизительный: `INGEST` 5-10%, `PLAN` 25%, `COST` 65%, `DEPTH` 80% (если включена), `EXPORT` 92%, `DONE` 100%. Долгая стадия на конкурсном наборе - обычно `PLAN` (граф видимости и три стратегии).

## Пакеты

Корень: `backend/src/main/java/ru/heatnet`.

| Пакет | Назначение |
|---|---|
| `config` | Каталоги, версия, свойства |
| `api` | HTTP, ошибки, OpenAPI |
| `geo` | WGS 84 и UTM 37N, ремонт геометрии |
| `ingest` | Поток GeoJSON, схема, дерево существующей сети |
| `rules` | Буфер, запрет, спецпроход, портал |
| `routing` | Полилиния от точки до точки |
| `network` | Врезки, дерево, пересечения своих участков |
| `calc` | Расход, Ду, предельная длина, камеры |
| `cost` | Рубли, штраф, S |
| `variants` | Три стратегии и отбор |
| `export` | Выходной GeoJSON |
| `jobs` | Очередь и конвейер |
| `depth` | Профиль глубины по готовой трассе |
| `cli` | Тот же конвейер без HTTP |

Модель новой сети (`NewNetworkTree`, `NewSegment`, `TieInPoint`) лежит в `calc.model` и общая для планировщика, сметы и экспорта.

## Поток данных одного расчёта

```
GeoJSON (EPSG:4326)
        │
        ▼
IngestResult
  accepted features
  ExistingNetwork          дерево по snap концов
  OksConnectionPoint[]     id + flow_tph + точка
  restrictions
        │
        ▼
SpatialConstraintBundle    на заданный Ду (отступ зависит от Ду)
        │
        ▼
RouteResult                LineString в EPSG:32637 или NOT_FOUND
        │
        ▼
NetworkPlan
  List<NewNetworkTree>
  unconnected OKS
        │
        ▼
EngineeringResult          Ду, камеры, диагностика
        │
        ▼
VariantCost                C, L, S, rank
        │
        ▼
GeoJSON (EPSG:4326, [долгота, широта])
```

В ходе расчёта геометрия новой сети хранится в метрах UTM. На границе экспорта координаты переводятся обратно. Порядок осей на входе и выходе - RFC 7946: сначала долгота, потом широта. В UTM ось X - восток, ось Y - север.

## Контейнер, в котором это крутится

Основной запуск - Docker на Linux. Тот же файл compose поднимается Docker Desktop на Windows. Оба пути описаны по командам в [Запуск](09-zapusk.md).

На хосте Linux по ТЗ: Engine **24.0.9 или ниже** и **`docker-compose` 1.29.2 (Compose V1)**. V1 **не работает** с Docker 25+ (`ContainerConfig`) - при 25+ понизьте Engine. Скрипты `export-* --docker` ходят в API контейнера; без `--docker` - JAR на хосте через CLI. Подробнее: [Запуск](09-zapusk.md).

`docker-compose.yml` (формат 3.8, на VPS читается docker-compose 1.29.2):

- `postgres` - `postgis/postgis:16-3.4`, порт только `127.0.0.1:5432`, лимит 4 ГБ, healthcheck `pg_isready` с `start_period` 120 с;
- `backend` - сборка `backend/Dockerfile`, порт 8080, лимит 10 ГБ, `JAVA_OPTS=-Xms512m -Xmx6g`, старт после healthy Postgres.

Dockerfile: стадия Maven Temurin 11 упаковывает JAR без тестов, стадия JRE Focal запускает `java $JAVA_OPTS -jar /app/app.jar` и ставит `curl` для healthcheck. Профиль `docker` задаётся переменной окружения, не флагом при ручном `java -jar`.

С хоста в контейнер смонтированы `./config` (только чтение) и `./data`. Поэтому результат задачи виден на хосте как `data/jobs/<uuid>/result.geojson` без `docker cp`. Скрипт `scripts/export-flat.sh --docker` скачивает плоский ответ в `data/result.geojson` и три файла вариантов. Скрипт `scripts/export-depth.sh --docker` скачивает ответ с глубиной в `data/m11-sample.geojson` и три файла вариантов. На Linux это `scripts/export-flat.sh --docker` и `scripts/export-depth.sh --docker`. На Windows - `scripts/export-flat.ps1 -Docker` и `scripts/export-depth.ps1 -Docker`. Все четыре вызывают `docker-compose` 1.29.2. Только поднять стек, без расчёта файла: `deploy.sh` или `deploy.ps1`. Разбор полей глубины: [Файлы с глубиной](10-glubina.md).

Именованный том `postgres-data` хранит СУБД между `docker-compose down`. Каталог `data/` томом не является: `down -v` его не удаляет.

## Профили запуска

| Профиль | Когда | База |
|---|---|---|
| `local` (по умолчанию) | JAR на Windows или Linux без Docker | JDBC отключён, расчёт идёт по файлам в `data/` |
| `docker` | `docker-compose up` | PostgreSQL 16 + PostGIS, Flyway |

Общие свойства (`application.yml`):

- порт `8080`;
- multipart до 3 ГБ;
- `heatnet.config-dir` - справочники, по умолчанию `../config`;
- `heatnet.data-dir` - загрузки и результаты, по умолчанию `../data`;
- допуск совмещения концов существующей сети - 1,0 м.

В контейнере каталоги монтируются как `/app/config` и `/app/data`, куча JVM - до 6 ГБ (`JAVA_OPTS`).

## База данных

Схема нужна профилю `docker`. Миграции Flyway:

- `V1__m1_ingest.sql` - `upload_session`, `ingested_feature` с геометрией 4326 и 32637, индексы GiST;
- `V2__widen_external_id.sql` - более длинный внешний идентификатор.

Расчётное ядро читает модель из памяти (`IngestResult`), а не из пространственного запроса на каждом ребре. PostGIS фиксирует загрузку и отчёт. Для файла от 256 МБ ingest делает два потоковых прохода и отбирает ограничения по району расчёта, не держа весь файл как один объект.

## Конвейер в коде

Класс `jobs.CalculationPipeline`:

1. `ingestService.ingest` - по потоку или по пути к файлу.
2. `variantGenerator.generate` - под замком, чтобы счётчики идентификаторов не смешивались.
3. Если ни один вариант не построен (нет точек подключения или все стратегии упали), формируется вырожденный вариант: пустая сеть и штраф на каждую точку. Так выход всё равно содержит ровно одну `variant_summary`, а не «нулевую стоимость при неподключённых ОКС».
4. Для каждого варианта при `enableDepth` вызывается `DepthTracer.trace`. Стоимость участков пересчитывается с Kгл, горизонтальная геометрия не ищется заново.
5. `GeoJsonExporter.write` пишет поток и параллельно словарь объяснений для `GET /api/jobs/{id}/explain/{objectId}`.

Командный режим `cli.ContestCliRunner` вызывает тот же `CalculationPipeline`. Он включается свойством `heatnet.cli.input` и после записи файла завершает процесс. Флаг `heatnet.cli.split-variants` дополнительно режет общий файл на `*_vA`, `*_vB`, `*_vC`.

### Что делает `VariantGenerator` внутри стадии PLAN

Под замком `CalculationPipeline.planLock` вызывается `variantGenerator.generate(ingest)`:

1. Для каждой стратегии (`JOINT_ALL` → `SEPARATE_EACH` → `CLUSTERED` по умолчанию) - полный `NetworkPlanner.plan` с своим `PlanMode` и картой групп для режима C.
2. На каждый план - `EngineeringCalculator.calculate`, затем `VariantCostCalculator.calculate` (смета уже внутри `GeneratedVariant`).
3. Планы с нарушениями топологии (`NetworkPlan.diagnostics` не пуст) в обычную выдачу не попадают; если все три упали, берётся лучший по S из «плохих» с предупреждением в лог.
4. `dropAvoidableUnconnected` убирает вариант, где точка не подключена, хотя в другом варианте того же job маршрут для неё есть.
5. `refineRoutingDn` для каждого варианта (с бюджетом 240 с суммарно) перестраивает план в графе фактического Ду; сравнение сначала по `entryExcessM` (§2.2), затем по S.
6. Стратегии после первой (`JOINT_ALL`) могут быть пропущены, если истёк `ADDITIONAL_STRATEGIES_BUDGET_MS` (240 с): основной вариант A важнее полного набора B/C на медленной машине.
7. `VariantRanker` сортирует по неокруглённому S и обрезает до `ranking.max_variants` (3).

Детали жадного цикла Штейнера, кэша графа и доводки веток - в [Коде](11-kod.md) и [Трассировке](06-trassirovka.md).

### Стадии COST, DEPTH, EXPORT

На стадии `COST` конвейер не пересчитывает деревья: он только обходит уже готовые `GeneratedVariant` и при `enableDepth` вызывает `DepthTracer.trace` для каждого. `DepthTracer` режет участки по профилю, обновляет `kDepth`, заново гоняет `SegmentCostCalculator` и `ScoreCalculator`, горизонтальные `NetworkTreeLayout` не трогает.

`GeoJsonExporter.write` идёт потоком (`JsonGenerator`), варианты - в порядке `rank`. Параллельно заполняется `Map` объяснений для API `explain`.

## Справочники

Числа таблиц не зашиты в Java. `ReferenceDataLoader` читает при старте:

| Файл | Содержание |
|---|---|
| `config/diameters.yaml` | Ду, пропускная способность, предельная длина, цена метра |
| `config/gabarits.yaml` | Ширина и высота пары труб |
| `config/rules.yaml` | Ограничения, камеры, штраф, веса S, бюджеты поиска |
| `config/depth-rules.yaml` | Глубины, просветы, Kгл |

Смена коэффициента или цены - правка YAML и перезапуск, без перекомпиляции формул.

## Нагрузка и память

ТЗ: файл до 3 ГБ, ответ до 500 МБ, сервер 16 ГБ RAM, до 50 пользователей. Парсер - Jackson Streaming API. Экспорт тоже потоковый (`JsonGenerator`), результат не собирается в одну строку.

Граф видимости в плотной застройке ограничен по времени (`routing.vg_deadline_ms`). Неполный граф помечается как частичный, тогда поиск уходит на запасную сетку. Эти потолки лежат в `rules.yaml`.

## Логи и диагностика

Уровень по умолчанию - INFO. Полезные маркеры при разборе одного прогона:

| Префикс / сообщение | Класс | Смысл |
|---|---|---|
| `M7` | `VariantGenerator` | Диагностика стратегий, бюджет, пропуск B/C |
| `STEINER` | `SteinerPlanner` | Подключение ОКС, уровень захода, доводка веток |
| `План построен с нарушениями` | `NetworkPlanner` | Топология или пересечения; вариант может уйти в запасной список |
| Имя стадии в `GET /api/jobs` | `JobService` + `CalculationPipeline` | Где завис расчёт |

Пакет `ingest` пишет в `IngestReport` предупреждения схемы (неизвестный тип, пропущенный объект). Их видно в `GET /api/files/{id}/report` до запуска job.

## Что сознательно вынесено за runtime

- QGIS - просмотр готового GeoJSON. Схему входа проверяет сервис при загрузке, схему выхода пишет сам экспорт.
- Каталоги `prototype/` и `presentation/` - черновики, не сервис.
- Внутренние отчёты `01`…`13-*.md` и `QA-REPORT-*.md` в корне рабочего репозитория - журнал разработки. Эта документация описывает поведение кода, а не историю замечаний.
