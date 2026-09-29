# heatnet-tracing-service

Сервис автоматической трассировки тепловых сетей для технологического присоединения новых зданий. Проект хакатона **ЛЦТ-2026 (Москва-2)**.

На вход подаётся один GeoJSON (существующая сеть, точки подключения, ограничения). На выход сервис отдаёт один GeoJSON с новой сетью: до трёх вариантов трассы, диаметры, стоимость и показатель **S**. Карта в оценку не входит. Проверка идёт по файлу.

Основной способ запуска - **Docker на Linux** (Ubuntu 22, `docker-compose` 1.29.2). Плоский файл и файл с глубиной снимаются двумя скриптами с одним и тем же флагом `--docker`. Дополнительно тот же образ поднимается **Docker на Windows** (Docker Desktop). JDK на машине для этих двух путей не нужен: Maven собирает JAR внутри образа.

Конкурсный датасет в этом репозитории: `dataset/dataset_updated.geojson` (технопарк «ЗИЛ», 144 объекта, 17 точек подключения). Других файлов в каталоге `dataset/` нет. Почему трасса, три варианта и смета на этом файле выглядят именно так - в [Почему расчёт такой](docs/12-pochemu-tak.md): что пакет документов задаёт буквально, где остаётся выбор и чего в опубликованном наборе нет.

## Что получается на выходе

Два независимых прогона. Плоский прогон и прогон с глубиной пишут **разные файлы**. Плоский `data/result.geojson` при выгрузке глубины не затирается.

| Файл | Когда появляется | Что внутри |
|---|---|---|
| `data/result.geojson` | Плоский прогон, `enableDepth: false` | Все варианты. `depth_start` и `depth_end` равны `null`. Координаты `[долгота, широта]` |
| `data/result_vA.geojson` | То же, нарезка | Только вариант `vA` |
| `data/result_vB.geojson` | То же | Только `vB` |
| `data/result_vC.geojson` | То же | Только `vC` |
| `data/m11-sample.geojson` | Прогон с глубиной, `enableDepth: true` | Все варианты. У `heat_network` заполнены `depth_start` и `depth_end` (метры до верха габарита) |
| `data/m11-sample_vA.geojson` | То же, нарезка | Вариант `vA` с профилем глубины |
| `data/m11-sample_vB.geojson` | То же | Вариант `vB` с профилем |
| `data/m11-sample_vC.geojson` | То же | Вариант `vC` с профилем |
| `data/jobs/<jobId>/result.geojson` | Любая задача API | Полный файл этой задачи. При `enableDepth: true` это уже файл с глубиной |

На карту кладите **один** вариант. В общем файле три трассы лежат друг на друге.

Подробный разбор полей и QGIS: [Вход и выход](docs/04-dannye.md), [Файлы с глубиной](docs/10-glubina.md).

## Показатель S

Меньше - лучше. C - рубли, L - метры новой сети.

```
S = 0,7 · (C / 25 000 000) + 0,3 · (L / 100)
```

Слагаемые не округляются. S округляется один раз до 4 знаков, `HALF_UP`. Эталон приложения §7.3 на контрольном примере: **0,6913**.

В C входят новые участки (`L · цена метра · Kгл · Kспец`), новые камеры (3 / 5 / 8 / 12 млн по Ду, присоединение уже включено) и врезки в существующие камеры (5 млн за каждую). Штраф неподключённой точки: `100 000 000 + 500 000 · G`. Реконструкция существующей сети в C, L и S не входит.

В плоском прогоне Kгл = 1. В прогоне с глубиной Kгл растёт, если труба уходит глубже 3,0 м: `Kгл = 1 + 0,10 · (h - 3)`. Горизонтальная трасса при этом заново не ищется: профиль накладывается на уже построенный план.

Снимок конкурсного набора 26.09.2026, плоский режим, 17 из 17 точек:

| `variant_id` | Стратегия | S |
|---|---|---|
| vA | общее дерево | 12,8418 |
| vB | отдельный ввод, где он короче | 13,1630 |
| vC | географические группы | 15,5002 |

vA: C = 267 684 842 ₽, L = 1 782,2 м. С глубиной S пересчитывается из-за Kгл и может отличаться от этих чисел.

## Документация

| Раздел | О чём |
|---|---|
| [Обзор](docs/01-obzor.md) | Объекты, правила, датасет, формулы |
| [Архитектура](docs/02-arhitektura.md) | Конвейер, Spring-слои, жизненный цикл job, Postgres, память |
| [Состав сервиса](docs/03-moduli.md) | Пакеты, контроллеры, VariantGenerator, очередь задач |
| [Вход и выход](docs/04-dannye.md) | Атрибуты GeoJSON. Входной файл: `dataset/dataset_updated.geojson` |
| [HTTP API](docs/05-api.md) | Загрузка, задача, результат |
| [Трассировка](docs/06-trassirovka.md) | Граф, Штейнер, заход в здание |
| [Расчёт](docs/07-raschet.md) | Ду, камеры, S, Kгл |
| [Конфигурация](docs/08-konfiguraciya.md) | `config/*.yaml` |
| [Запуск](docs/09-zapusk.md) | Linux Docker, Windows Docker, логи, остановка |
| [Файлы с глубиной](docs/10-glubina.md) | Команды, проверка чисел, QGIS |
| [Код](docs/11-kod.md) | Пакеты, сквозной вызов конвейера, SteinerPlanner, объекты в памяти |
| [Почему расчёт такой](docs/12-pochemu-tak.md) | Что задано пакетом документов буквально, где текст оставляет выбор и как состав `dataset/dataset_updated.geojson` виден в трассе |

## Стек

Java 11, Spring Boot 2.6.3, JTS 1.19.0, proj4j 1.2.2, springdoc-openapi-ui 1.7.0. В контейнере - PostgreSQL 16 + PostGIS, Flyway. Образ: multi-stage `maven:3.8-eclipse-temurin-11`, затем `eclipse-temurin:11-jre-focal`. Куча расчёта `-Xmx6g`. Пул задач - 2 потока.

Исходники: `backend/src/main/java/ru/heatnet`. Точка входа - `HeatnetApplication`. Один расчёт собирает `jobs.CalculationPipeline`. Разбор классов и методов - в [Код](docs/11-kod.md).

## Как код проходит один файл

Ниже тот же путь, что у `POST /api/jobs` и у командного запуска. Имена - классы Java, не номера внутренних этапов команды.

```
FileUploadController / ContestCliRunner
        |
        v
JobService.submit
        |
        v
CalculationPipeline.run
        |
        +-- IngestService.ingest
        |     StreamingGeoJsonReader -> SchemaValidator
        |     NetworkTreeBuilder + ChamberIncidence -> ExistingNetwork
        |
        +-- VariantGenerator.generate
        |     три вызова NetworkPlanner.plan (PlanMode JOINT, CLUSTERED, SEPARATE)
        |           |
        |           +-- SteinerPlanner.plan
        |           |     RouteFinderFactory.bundle / SharedVisibilityRouter
        |           |     OwnEntryCandidates, SpecialCost, polishLeaves
        |           +-- запас: TieInRouter, JointPlanner, RoutingPipeline
        |           +-- RouteSegmentSplitter, DnBoundarySplitter, CrossingResolver
        |           +-- EngineeringCalculator (диаметры, камеры)
        |
        +-- VariantCostCalculator + ScoreCalculator + VariantRanker
        |
        +-- DepthTracer.trace          только если enableDepth = true
        |     DepthProfileSolver, DepthCoefficient, пересчёт S
        |
        +-- GeoJsonExporter.write
              FeatureCollection в OutputStream
              словарь объяснений для GET /explain
```

`CalculationPipeline` держит замок на генерацию вариантов одного задания: идентификаторы новых участков общие. Два задания могут идти параллельно. Пул задаётся в `JobExecutorConfig`: `core = 2`, `max = 2`, очередь 100, при переполнении `JobRejectedException` и ответ `503`.

### Что лежит в памяти после загрузки

`IngestResult` - снимок принятого файла:

- `RawFeature` на каждый объект, геометрия и в WGS 84, и в EPSG:32637;
- `ExistingNetwork` - существующие участки и камеры, связанные по концам, если нет `upstream_object_id`;
- список `OksConnectionPoint` с `id` и `flow_tph`;
- `IngestReport` - ошибки и предупреждения схемы.

Файл меньше 256 МБ (`IngestService.TWO_PASS_THRESHOLD_BYTES`) читается одним проходом Джексона. Файл больше - двумя проходами: сначала сеть, камеры, источник и точки подключения, затем только ограничения, чья рамка пересекает район расчёта (точки, ближайшие трубы, запас 1 км). Остальные ограничения в отчёте учитываются, в граф не попадают.

### Три варианта

`VariantGenerator` вызывает `NetworkPlanner.plan` три раза. Режим передаётся как `PlanMode`:

| `variant_id` | `PlanMode` | Что меняется в `SteinerPlanner` |
|---|---|---|
| vA | `JOINT` | Следующая точка может сесть на уже построенную новую сеть |
| vC | `CLUSTERED` | Общие участки только внутри географической группы (`groupOf`) |
| vB | `SEPARATE` | Ответвление от уже построенной сети дороже на 250 м условной длины (`SEPARATE_BRANCH_PENALTY_M`), поэтому своя врезка выигрывает, пока она не намного длиннее |

После трёх планов `dropAvoidableUnconnected` выбрасывает вариант, в котором точка осталась без сети, хотя в другом плане этой же задачи маршрут для неё нашёлся. `rank` ставит `VariantRanker` по неокруглённому S.

Уточняющий проход `refineRoutingDn` строит план ещё раз в графе фактического Ду. Сравнение: сначала `NetworkPlanner.entryExcessM` (насколько заход в своё здание глубже ближайшей стены), затем S. Бюджет дополнительных стратегий — 900 секунд (`ADDITIONAL_STRATEGIES_BUDGET_MS`), уточнения Ду — 240 секунд (`ROUTING_DN_REFINEMENT_BUDGET_MS`).

### Дерево и путь

`SteinerPlanner` - основной планировщик и для одной точки, и для многих. Он строит общий граф дворов `SharedVisibilityRouter` на Ду магистрали (`RouteFinderFactory.magistralDn` по сумме расходов). Если набор ограничений не собрался или граф не построился, метод возвращает `null`, и `NetworkPlanner` уходит в запасной путь: `TieInCandidates`, `TieInRouter`, `JointPlanner`, `RoutingPipeline`.

Цена ребра графа и спрямления - `SpecialCost.weightedLength`: длина плюс `(Kспец - 1)` только на метрах спецучастка. Наложение нескольких спецпроходов берёт максимум K, не сумму. Добавка `turn_penalty_m` (10 м) живёт только в поиске, в рубли сметы не входит. Поворот больше 90 градусов в поиск не принимается.

Заход в полигон своей точки готовит `OwnEntryCandidates`: прямой отрезок от ближайшей доступной стены. До стены действует полный набор ограничений, включая свой корпус. На последнем отрезке буфер своего корпуса снят. После сборки дерева `polishLeaves` заново прокладывает каждую ветку «точка ответвления - точка ОКС» и принимает её, если она дешевле не меньше чем на 0,5 м и заход не стал хуже. `LeafDnPolish` повторяет это в графе фактического Ду ветки, если он меньше Ду магистрали.

Дальше по уже найденной линии:

- `RouteSegmentSplitter` режет обычный участок и спецучасток, ставит `technical_node` на границе;
- `EngineeringCalculator` суммирует расходы, `DiameterSelector` и `DiameterOptimizer` назначают Ду, `LengthLimitValidator` следит за плетью;
- `DnBoundarySplitter` ставит узел там, где Ду сменился;
- `CrossingResolver` разводит пересечения своих осей вне общего узла;
- `TopologyValidator` проверяет, что это дерево, листья - точки подключения, развилка - в камере.

### Смета и файл

`VariantCostCalculator` складывает:

- участок: `SegmentCostCalculator`, `L * cнов(Ду) * Kгл * Kспец`, рубли через `Money.product` (`HALF_UP` до целого рубля на каждом участке);
- новая камера: `ChamberCostScale` по наибольшему Ду, 3 / 5 / 8 / 12 млн, присоединение уже внутри этой суммы;
- врезка в существующую камеру: 5 млн, и только если тип объекта `HEAT_CHAMBER`;
- штраф: `UnconnectedPenaltyCalculator`, `100_000_000 + 500_000 * G`.

`calculatedCost()` равен стройке плюс штраф. Реконструкция в `ReconstructionEngine` считается и в эту сумму не входит. `ScoreCalculator.score` округляет S один раз до `score_scale` (4 знака).

`GeoJsonExporter` пишет поток. Идентификаторы новых объектов получают префикс варианта (`vA_...`), чтобы один и тот же внутренний id не повторился в трёх вариантах. Ссылки на входные точки и существующие камеры сохраняют исходный JSON-тип числа или строки (`ExportIds`).

Если `enableDepth` ложь, в свойства пишется `null`. Если истина, `DepthTracer` вызывает `DepthProfileSolver` по уже построенной линии, подставляет `depth_start` / `depth_end` и пересчитывает стоимость с `DepthCoefficient`. Координата Z не добавляется.

Полный разбор пакетов, методов и объектов - [Код](docs/11-kod.md): там же жадный цикл `SteinerPlanner`, резерв чужого захода, кэш графа на 8 Ду, доводка веток и проверки конструктора `NewNetworkTree`. Правила предметной области - [Обзор](docs/01-obzor.md). Поля файла - [Вход и выход](docs/04-dannye.md).

---

## 1. Запуск на Linux через Docker

Этот путь основной. Так сервис поднимают на Ubuntu 22: одна команда compose, API на порту 8080.

### Что установить

Стенд по пункту 3.2 ТЗ - это связка, которая реально поднимает `docker-compose.yml`:

- Docker Engine **не выше 24.0.9** (проверено на **24.0.9**; подойдёт и более ранняя 24.0.x той же ветки, если API совместим с `docker-compose` 1.29.2);
- `docker-compose` **1.29.2** (V1, команда через дефис, ожидаемая строка `docker-compose version 1.29.2, build 5becea4c`).

**Compose V1 (`docker-compose` 1.29.2) не поддерживает Docker Engine 25 и новее:** демон не отдаёт `ContainerConfig`, и `docker-compose up` падает с `KeyError: 'ContainerConfig'`. С Engine 25+ (в том числе после свежего `get.docker.com` или нового Docker Desktop) бинарник 1.29.2 **не запустит** этот `docker-compose.yml`.

**По п. 3.2 ТЗ нужны Engine не выше 24.0.9 и `docker-compose` 1.29.2 (V1).** Если `docker --version` показывает 25+, понизьте Docker Engine до 24.0.9 (или ниже). Скрипты `export-*.sh`, `deploy.sh` и примеры в документации вызывают `docker-compose` (V1).

Проверка:

```bash
docker --version
docker-compose --version
```

Ожидание: `Docker version 24.0.9` (или ниже, но не 25+) и `docker-compose version 1.29.2, build 5becea4c`.

Дальше нужны `curl`, свободные порты `8080` и `127.0.0.1:5432`, память хоста с запасом под лимиты compose (backend до 10 ГБ, Postgres до 4 ГБ; в ТЗ у сервера 16 ГБ) и файл `dataset/dataset_updated.geojson`.

Пользователь должен иметь право вызывать Docker (`docker` без sudo или пользователь в группе `docker`). Команды ниже выполняются из корня проекта, где лежат `docker-compose.yml`, `config/`, `scripts/` и `dataset/dataset_updated.geojson`.

```bash
cd heatnet-tracing-service-clean
chmod +x deploy.sh scripts/export-flat.sh scripts/export-depth.sh
sed -i 's/\r$//' deploy.sh scripts/export-flat.sh scripts/export-depth.sh
```

`chmod` нужен для трёх скриптов, которые запускают через `./`: `deploy.sh`, `scripts/export-flat.sh`, `scripts/export-depth.sh`. Без бита запуска оболочка отвечает `Permission denied`.

`sed` нужен тем же трём файлам, если они приехали с переводами строк Windows (`bash\r`). Тогда `./deploy.sh` (и оба `export-*.sh`) останавливаются сразу: интерпретатор ищется как `bash\r`. В git для `*.sh` зафиксирован LF (`.gitattributes`). Копия с диска Windows или из архива может прийти с `\r` — `sed` его снимает.

### Поднять стек

Из корня репозитория, команда V1:

```bash
docker-compose up --build -d
```

То же делает `./deploy.sh`: `docker-compose up -d --build`, затем до 3 минут ждёт health и печатает `/api/info`. Расчёт GeoJSON этот скрипт не запускает. Первая сборка Maven часто длиннее трёх минут - тогда дождитесь `docker-compose up --build -d` и запустите `./deploy.sh` ещё раз.

Первая сборка качает базовые образы и зависимости Maven внутри Dockerfile. Это несколько минут и нужен выход в сеть. Повторный `up` без смены кода берёт уже собранный образ.

Дождитесь здоровья. Postgres сначала инициализирует PostGIS (`start_period` 120 с), backend стартует после него.

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/api/info
```

В health должно быть `"status":"UP"`. Swagger: <http://localhost:8080/swagger-ui.html>.

Логи расчёта:

```bash
docker-compose logs -f backend
```

Остановка (том Postgres сохраняется):

```bash
docker-compose down
```

Полный сброс базы: `docker-compose down -v`. Каталог `data/` на хосте при этом остаётся: это bind-mount, не именованный том.

### Флаг `--docker` у `export-flat.sh` и `export-depth.sh`

Оба скрипта читают `dataset/dataset_updated.geojson` и пишут файлы в `data/`. На Windows тот же смысл у `-Docker` в `export-flat.ps1` и `export-depth.ps1`.

| Режим | Команда (Linux) | Что происходит |
|---|---|---|
| **С Docker (путь по ТЗ)** | `./scripts/export-flat.sh --docker` или `./scripts/export-depth.sh --docker` | `docker-compose up -d`, ожидание health, загрузка GeoJSON через `POST /api/files`, задача `POST /api/jobs`, ожидание `DONE`, скачивание результата в `data/`. Сборка JAR и JDK на хосте не нужны - расчёт в контейнере backend. |
| **Без Docker** | `./scripts/export-flat.sh` или `./scripts/export-depth.sh` (без флага) | Docker не вызывается. При отсутствии JAR скрипт запускает `mvn -f backend/pom.xml package -DskipTests`, затем `java -jar` с профилем `local` и `ContestCliRunner`: тот же конвейер, что в API, но без HTTP. Нужны **JDK 11** и **Maven** на машине. |

`export-flat` передаёт `enableDepth: false`, пишет `data/result*.geojson`. `export-depth` передаёт `enableDepth: true`, пишет `data/m11-sample*.geojson`. Плоские и глубинные файлы друг друга не перезаписывают.

`deploy.sh` / `deploy.ps1` только поднимают стек и ждут health; расчёт GeoJSON не запускают.

### Плоский результат (2D)

Конкурсный файл, без профиля глубины. `depth_start` / `depth_end` в ответе будут `null`.

```bash
./scripts/export-flat.sh --docker
```

Появляются `data/result.geojson` и `data/result_vA.geojson`, `data/result_vB.geojson`, `data/result_vC.geojson`.

Тот же запрос вручную, когда стек уже поднят:

```bash
mkdir -p data
UPLOAD=$(curl -fsS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files)
FILE_ID=$(printf '%s' "$UPLOAD" | sed -n 's/.*"fileId":"\([^"]*\)".*/\1/p')
JOB=$(curl -fsS -H "Content-Type: application/json" \
  -d "{\"fileId\":\"$FILE_ID\",\"enableDepth\":false}" \
  http://localhost:8080/api/jobs)
JOB_ID=$(printf '%s' "$JOB" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')
echo "job $JOB_ID"

while true; do
  ST=$(curl -fsS "http://localhost:8080/api/jobs/$JOB_ID")
  echo "$ST"
  printf '%s' "$ST" | grep -q '"status":"DONE"' && break
  printf '%s' "$ST" | grep -q '"status":"FAILED"' && exit 1
  sleep 5
done

curl -fsS -o data/result.geojson "http://localhost:8080/api/jobs/$JOB_ID/result"
for v in vA vB vC; do
  curl -fsS -o "data/result_${v}.geojson" \
    "http://localhost:8080/api/jobs/$JOB_ID/result?variant=${v}"
done
```

На конкурсном наборе стадия `PLAN` занимает около минуты. Тот же полный файл параллельно лежит в `data/jobs/$JOB_ID/result.geojson` (каталог `data` смонтирован в контейнер как `/app/data`). В плоском файле `depth_start` и `depth_end` равны `null`.

### Файлы с глубиной

Отдельная задача с `"enableDepth": true`. Горизонтальный план тот же конвейер, затем расчёт глубины записывает отметку до верха габарита и пересчитывает стоимость участков с Kгл. Z-координата в геометрию не добавляется.

Одной командой. Флаг тот же, что у плоского прогона (`--docker`). Скрипт поднимает стек через `docker-compose` 1.29.2, ждёт health до 8 минут, считает и сохраняет четыре файла:

```bash
./scripts/export-depth.sh --docker
```

Скрипт пишет:

- `data/m11-sample.geojson` - все три варианта;
- `data/m11-sample_vA.geojson`;
- `data/m11-sample_vB.geojson`;
- `data/m11-sample_vC.geojson`.

Вручную, когда стек уже поднят (тот же API, другие имена файлов):

```bash
mkdir -p data
UPLOAD=$(curl -fsS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files)
FILE_ID=$(printf '%s' "$UPLOAD" | sed -n 's/.*"fileId":"\([^"]*\)".*/\1/p')
JOB=$(curl -fsS -H "Content-Type: application/json" \
  -d "{\"fileId\":\"$FILE_ID\",\"enableDepth\":true}" \
  http://localhost:8080/api/jobs)
JOB_ID=$(printf '%s' "$JOB" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')
echo "depth job $JOB_ID"

while true; do
  ST=$(curl -fsS "http://localhost:8080/api/jobs/$JOB_ID")
  echo "$ST"
  printf '%s' "$ST" | grep -q '"status":"DONE"' && break
  printf '%s' "$ST" | grep -q '"status":"FAILED"' && exit 1
  sleep 5
done

curl -fsS -o data/m11-sample.geojson "http://localhost:8080/api/jobs/$JOB_ID/result"
for v in vA vB vC; do
  curl -fsS -o "data/m11-sample_${v}.geojson" \
    "http://localhost:8080/api/jobs/$JOB_ID/result?variant=${v}"
done
```

Пока идёт расчёт, в статусе после `COST` появляется стадия `DEPTH`, затем `EXPORT`. В `data/m11-sample_vA.geojson` у участков `depth_start` и `depth_end` - числа, координата остаётся парой `[долгота, широта]`. В плоском `data/result_vA.geojson` те же поля равны `null`.

Смотреть на карте `data/m11-sample_vA.geojson` поверх `dataset/dataset_updated.geojson`. Пошагово: [Файлы с глубиной](docs/10-glubina.md).

---

## 2. Запуск на Windows через Docker

Тот же `docker-compose.yml` и тот же `docker-compose` 1.29.2 (V1), через дефис. Нужен запущенный Docker Desktop и бинарник V1 в `PATH`. Команды - из корня репозитория.

Свежий Docker Desktop часто ставит **Engine 25+**; с ним **V1 1.29.2 не работает** (см. раздел Linux выше). Для пути по ТЗ на Windows нужны Engine ≤24.0.9 и `docker-compose` 1.29.2 в `PATH`, либо Ubuntu-ВМ из инструкции организаторов. Скрипты расчёта: `.\scripts\export-flat.ps1 -Docker` и `.\scripts\export-depth.ps1 -Docker`; без `-Docker` - JAR на хосте, как в таблице про `--docker`.

Поднять стек и дождаться health (расчёт не запускается, ожидание до 3 минут):

```powershell
.\deploy.ps1
```

Остановка: `docker-compose down`.

Плоский файл и файл с глубиной:

```powershell
.\scripts\export-flat.ps1 -Docker
.\scripts\export-depth.ps1 -Docker
```

Оба скрипта сначала вызывают `docker-compose`. Без `-Docker` ищут JAR на машине.

### Плоский результат на Windows

Стек уже поднят (`health` = UP). Дальше тот же API, что на Linux. `curl.exe` входит в Windows 10.

```powershell
$upload = curl.exe -sS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files | ConvertFrom-Json
$body = @{ fileId = $upload.fileId; enableDepth = $false } | ConvertTo-Json -Compress
$job = curl.exe -sS -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -d $body | ConvertFrom-Json
do {
  Start-Sleep -Seconds 5
  $st = curl.exe -sS "http://localhost:8080/api/jobs/$($job.jobId)" | ConvertFrom-Json
  "$($st.status) $($st.stage) $($st.progress)"
} while ($st.status -eq "QUEUED" -or $st.status -eq "RUNNING")
if ($st.status -ne "DONE") { throw $st.message }
New-Item -ItemType Directory -Force -Path data | Out-Null
curl.exe -sS -o data/result.geojson "http://localhost:8080/api/jobs/$($job.jobId)/result"
foreach ($v in @("vA","vB","vC")) {
  curl.exe -sS -o "data/result_$v.geojson" "http://localhost:8080/api/jobs/$($job.jobId)/result?variant=$v"
}
```

### Файлы с глубиной на Windows

```powershell
.\scripts\export-depth.ps1 -Docker
```

Появляются:

- `data\m11-sample.geojson`
- `data\m11-sample_vA.geojson`
- `data\m11-sample_vB.geojson`
- `data\m11-sample_vC.geojson`

Ожидание health в этом скрипте - до 8 минут, расчёта - до 15 минут. В консоли идут строки `RUNNING PLAN 25%`, затем `DEPTH`, затем `DONE`.

Вручную, если стек уже поднят через `docker-compose up -d`:

```powershell
$upload = curl.exe -sS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files | ConvertFrom-Json
$body = @{ fileId = $upload.fileId; enableDepth = $true } | ConvertTo-Json -Compress
$job = curl.exe -sS -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -d $body | ConvertFrom-Json
do {
  Start-Sleep -Seconds 5
  $st = curl.exe -sS "http://localhost:8080/api/jobs/$($job.jobId)" | ConvertFrom-Json
  "$($st.status) $($st.stage) $($st.progress)"
} while ($st.status -eq "QUEUED" -or $st.status -eq "RUNNING")
if ($st.status -ne "DONE") { throw $st.message }
curl.exe -sS -o data/m11-sample.geojson "http://localhost:8080/api/jobs/$($job.jobId)/result"
foreach ($v in @("vA","vB","vC")) {
  curl.exe -sS -o "data/m11-sample_$v.geojson" "http://localhost:8080/api/jobs/$($job.jobId)/result?variant=$v"
}
```

---

## Как устроен один прогон

1. `POST /api/files` сохраняет GeoJSON в `data/uploads/<fileId>/` и проверяет схему.
2. `POST /api/jobs` ставит задачу в пул из двух потоков. Тело: `fileId` и `enableDepth`.
3. Конвейер: разбор, три стратегии трассы, диаметры и смета, при `enableDepth: true` профиль глубины, запись GeoJSON.
4. `GET /api/jobs/{id}` показывает `QUEUED`, `RUNNING`, `DONE` или `FAILED`. Стадии: `INGEST`, `PLAN`, `COST`, `DEPTH`, `EXPORT`.
5. `GET /api/jobs/{id}/result` отдаёт все варианты. `?variant=vA` (также `vB`, `vC`) - один вариант для карты.
6. `GET /api/jobs/{id}/explain/{objectId}` - правило по участку, камере или сводке. `objectId` берётся из выходного файла.

Идентификаторы задач живут в памяти процесса. После `docker-compose down` старые `jobId` пропадают. Файлы в `data/jobs/` и скачанные `data/result*.geojson` / `data/m11-sample*.geojson` остаются на диске.

Варианты в выходном файле:

| `variant_id` | Смысл |
|---|---|
| vA | Все точки в одном дереве Штейнера: следующая садится на уже построенную сеть или на существующую, что дешевле по правилам |
| vB | Своя врезка, пока она не длиннее ответвления больше чем на 250 м условной длины |
| vC | Совместные участки только внутри географической группы |

Заход в своё здание - прямой отрезок от ближайшей доступной стены до точки внутри полигона. Отступ от своего полигона действует до этой стены. Чужие здания обходятся.

## Конфигурация в контейнере

`docker-compose.yml` монтирует:

- `./config` → `/app/config` (только чтение): `diameters.yaml`, `gabarits.yaml`, `rules.yaml`, `depth-rules.yaml`;
- `./data` → `/app/data`: загрузки, `jobs/`, и туда же попадают файлы, которые скрипты кладут с хоста.

Профиль Spring внутри образа - `docker`. Учётная запись Postgres: база `heatnet`, пользователь `heatnet`, пароль `heatnet`. Порт 5432 опубликован только на localhost.

Правка YAML подхватывается после перезапуска контейнера backend: `docker-compose up -d --force-recreate backend`. Пересборка образа для смены справочника не нужна. Пересборка нужна после изменения Java-кода: `docker-compose up --build -d`.

## Структура репозитория

```
heatnet-tracing-service-clean/
├── backend/                  исходники, Dockerfile, тесты
├── config/                   справочники, монтируются в контейнер
├── data/                     результат на хосте (result*, m11-sample*, jobs/, uploads/)
├── docker/postgres/          init PostGIS
├── docker-compose.yml
├── deploy.sh                 Linux: поднять стек и дождаться health
├── deploy.ps1                Windows: то же
├── scripts/
│   ├── export-flat.sh        Linux: плоский файл (--docker)
│   ├── export-flat.ps1       Windows: плоский файл (-Docker)
│   ├── export-depth.sh       Linux: файлы с глубиной (--docker)
│   └── export-depth.ps1      Windows: файлы с глубиной (-Docker)
└── dataset/dataset_updated.geojson   конкурсный набор
```

Пакет Java: `ru.heatnet`. Загрузка - `ingest`, ограничения - `rules`, путь - `routing`, дерево - `network` (`SteinerPlanner`), диаметры - `calc`, смета - `cost`, три варианта - `variants`, файл - `export`, очередь - `jobs`, глубина - `depth`.

## Лицензия

Проект разрабатывается в рамках хакатона ЛЦТ-2026.
