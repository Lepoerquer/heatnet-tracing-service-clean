# Состав сервиса

Ниже части сервиса по пакетам Java: загрузка, ограничения, маршрут, дерево сети, диаметры, смета, варианты, выгрузка, очередь задач и профиль глубины.

Полный порядок вызовов методов, жадный цикл Штейнера и поля объектов в памяти - в [Коде](11-kod.md). Здесь - назначение пакетов и связи между ними.

## Каркас

Пакеты `config`, часть `api`. Точка входа `HeatnetApplication`.

- Профили `local` и `docker`.
- `GET /api/info` - версия, активный профиль, число строк справочника диаметров.
- Actuator: `/actuator/health`, `/actuator/info`.
- OpenAPI: `/swagger-ui.html`, спецификация `/api-docs`.
- `docker-compose.yml`: сервисы `postgres` (`postgis/postgis:16-3.4`) и `backend`. Порт Postgres опубликован только на `127.0.0.1:5432`. Лимит памяти контейнера backend - 10 ГБ, postgres - 4 ГБ.
- Образ backend собирается из `backend/Dockerfile`.

Обработчик `ApiExceptionHandler` переводит отсутствие файла, неготовый результат и переполнение очереди в JSON-ошибку с кодом HTTP.

| Класс | Метод / путь | Что делает в коде |
|---|---|---|
| `ServiceInfoController` | `GET /api/info` | Читает `HeatnetProperties`, `ReferenceData` |
| `FileUploadController` | `POST /api/files` | Multipart → `UploadIngestService` |
| `FileUploadController` | `GET /api/files/{id}/report` | `IngestReport` сохранённой загрузки |
| `JobController` | `POST /api/jobs` | `JobService.submit`, ответ 202 |
| `JobController` | `GET /api/jobs/{id}` | Снимок `JobRecord` |
| `JobController` | `GET .../result` | Файл или `VariantSplitter` по query |
| `JobController` | `GET .../explain/{objectId}` | Фрагмент карты из job |
| `ContestCliRunner` | свойства `heatnet.cli.*` | `CalculationPipeline.run(Path, ...)` без HTTP |

`HeatnetConfiguration` поднимает каталоги `config-dir` и `data-dir`. `ReferenceDataLoader` - `@Bean` с загрузкой четырёх YAML при старте; без файла приложение не поднимется.

## Загрузка

Пакеты `ingest`, `geo`.

`StreamingGeoJsonReader` читает FeatureCollection потоком. Порядок ключей внутри объекта не важен. Числа с запятой (`12,5`) принимаются. Регистр кодов нормализуется. Идентификатор объекта сохраняет JSON-тип: число и строка с тем же текстом - разные id и оба доходят до выхода.

`SchemaValidator` проверяет обязательные поля:

- `oks_connection_point` - Point и `flow_tph` > 0, иначе объект отвергается;
- `restriction` - непустой `restriction_type` и геометрия;
- `heat_network` - LineString и `diameter`;
- `heat_chamber` и `source` - идентификатор и тип;
- `oks_future` - справочное предупреждение, `heat_load` в расчёт не идёт.

`ProjectionService` переводит геометрию в EPSG:32637. `GeometryRepair` чинит невалидные кольца средствами JTS 1.19 (`GeometryFixer`). Откат JTS на 1.18 ломает этот ремонт.

`NetworkTreeBuilder` (ingest) собирает `ExistingNetwork`: если `upstream_object_id` нет, концы линий и камеры совмещаются в допуске. Камера без атрибута диаметра получает диаметр как максимум уже примыкающих труб.

`IngestService.ingest` возвращает `IngestResult` и `IngestReport` (ошибки, предупреждения, счётчики). `UploadIngestService` кладёт исходный файл в `data/uploads/{uuid}/` и отдаёт `fileId`. Отчёт доступен как `GET /api/files/{id}/report`.

Файлы от 256 МБ читаются двумя проходами: сначала район расчёта, затем ограничения, попавшие в этот район.

Цепочка внутри `IngestService.build`:

```
RawFeature (поток)
  -> SchemaValidator.validate
  -> накопление по Kind (точки, сеть, камеры, restrictions)
  -> NetworkTreeBuilder.build -> ExistingNetwork
  -> ChamberIncidence (диаметр камеры, степень)
  -> IngestResult + IngestReport
```

`IngestPersistenceService` (профиль `docker`) дублирует принятые объекты в PostGIS для отчёта загрузки; расчётное ядро читает только `IngestResult` из памяти job.

## Пространственные ограничения

Пакет `rules`. Задача этого кода: можно ли провести отрезок при данном Ду и какой коэффициент у пересечения.

Сборка: `RestrictionEngineFactory.createBundle(ingest, dn)` → `SpatialConstraintBundle`.

| Класс | Роль |
|---|---|
| `RestrictionTypeMapper` | `restriction_type=oks` → правило существующего ОКС |
| `IngestRestrictionsLoader` | Геометрия ограничений в UTM |
| `BufferFactory` | Отступ + половина ширины пары + половина габарита препятствия |
| `RestrictionIndexBuilder` | Пространственный индекс (STRtree) |
| `PortalGateGenerator` | Створы через дорогу и трамвай с углом ≥ 45° |
| `DefaultSpatialConstraintEngine` | `isSegmentBlocked`, пересечения, спецучастки |
| `UtmGeometryMapper` | Перевод геометрии ограничений |

`SpatialConstraintEngine`:

- `isSegmentBlocked` - ребро графа и спрямление;
- `extractSpecialSections` - интервалы Kспец для сметы и для поиска;
- `findCrossings` - факт пересечения;
- порталы - вершины графа на дороге и трамвае.

Набор зависит от Ду: отступ от ОКС 5, 7 или 9 м. Вызов с Ду, для которого бандл не собран, даёт `RulesException`. Фабрика держит кэш бандлов на расчёт. Допуск сравнения с нормативом - `geometry.tolerance_m` (0,01 м), чтобы погрешность проекции не превращала 4,999 м в нарушение.

Свой полигон точки подключения снимается с финального прямого участка отдельно, в связке с `OwnEntryCandidates`, а не «дыркой» во всём бандле.

## Поиск маршрута

Пакет `routing`. Вопрос: полилиния в UTM от точки A до точки B при заданном Ду.

Основной метод - граф видимости с порталами (`PortaledVisibilityRouteFinder`, `VisibilityGraphBuilder`, `SharedVisibilityRouter`). Запасной - A* по сетке 2 м (`GridRouteFinder`), если граф не собрался за `vg_deadline_ms` или вершин слишком много (`grid_fallback_min_vertices`).

`GraphPathSearcher` ищет путь. Цена ребра:

- длина, умноженная на Kспец **только на спецучастке** (`SpecialCost`);
- добавка `turn_penalty_m` за сам факт поворота (эвристика поиска, в смету C не входит);
- `k_angle = 1.0`, то есть множителя за «нестандартный» угол нет.

Поворот в поиске - отклонение от прямой, не больше 90°. `RouteSmoother` стягивает лишние вершины, если хорда законна. `TurnRepair` добивает оставшиеся изломы больше 90°. `CrossingFixer` подправляет угол входа в дорогу.

`RouteFinder.findRoute` при неудаче возвращает `RouteStatus.NOT_FOUND`, а не исключение. `RoutingPipeline` - фасад для одной ветки. `RouteFinderFactory` собирает бандл и граф под конкретный Ду и кэширует их.

`NewNetworkClearance` запрещает новой трубе подходить к уже принятой ближе 0,3 м вне общего узла. `OwnEntryCandidates` строит лучи финального захода: та же ближайшая стена, выборка вдоль стены 0,25 м, окно глубины внутрь полигона не больше 1 м сверх расстояния до границы.

Вершины контура сдвигаются наружу по биссектрисе угла (`RoutingGeometry.outwardVertex`). Для П-образного двора сдвиг «от центроида» уводил бы угол внутрь здания, поэтому биссектриса выбирает сторону, где точка оказывается снаружи.

## Топология новой сети

Пакет `network`. Вопрос: куда врезаться и как собрать дерево.

Фасад - `NetworkPlanner`. Для двух и более точек, и для одной точки тоже, основной планировщик - `SteinerPlanner`. Режимы `PlanMode`: `JOINT`, `SEPARATE`, `CLUSTERED`. Все режимы живут в одном графе, поэтому части сети разных групп не пересекаются «задним числом» при склейке независимых прогонов.

Запасной путь, если Штейнер не посадил точку: `TieInCandidates` + `TieInRouter` / `JointPlanner` через `EntryAwareRouteFinder`. Нет законного маршрута - точка в `NetworkPlan.unconnectedOks` со штрафом.

| Класс | Роль |
|---|---|
| `ExistingNetworkGeometry` | Участки существующей сети в UTM, сторона к источнику |
| `TieInCandidates` | Камера в радиусе 10 м или проекция на трубу |
| `ChamberDegreeCalculator` | Лимит 4 участка |
| `SteinerPlanner` | Жадное дерево: следующий ОКС к уже построенному или к существующей сети |
| `RouteSegmentSplitter` | Разрез на обычный участок и спецучасток, технический узел на границе |
| `DnBoundarySplitter` | Технический узел на смене Ду после назначения диаметров |
| `CrossingResolver` | Разведение пересечений своих осей |
| `NetworkTreeBuilder` | `NewNetworkTree` |
| `NetworkTreeLayout` | Координаты узлов и линии участков |
| `TopologyValidator` | Дерево, листья-ОКС, камеры в развилках |
| `JointPlanner` | Совместно или раздельно по полному ΔS, запасной режим |

После назначения фактических Ду `NetworkPlanner` проверяет отступы ещё раз (`verifyAfterM5`) и спрямляет звенья, которые при меньшем Ду ветки стали короче (`straightenAtActualDn`).

## Инженерный расчёт

Пакет `calc`. Вход - `List<NewNetworkTree>` и существующая сеть. Выход - `EngineeringResult`.

| Класс | Роль |
|---|---|
| `FlowAggregator` | Сумма `flow_tph` к корню дерева |
| `DiameterSelector` | Минимальный Ду с пропускной способностью ≥ расхода |
| `DiameterOptimizer` | Минимальная стоимость при неубывании Ду к врезке, постоянстве Ду на отрезке постоянного расхода и предельной длине |
| `LengthLimitValidator` | Плеть одного Ду; если оптимизатор не нашёл решение - пошаговый подъём и диагностика `LENGTH_LIMIT_EXCEEDED` |
| `ChamberSizing` / `TieInSizing` | Новая камера и тип врезки |
| `ReconstructionEngine` | Диагностика реконструкции, в S не передаётся |
| `EngineeringCalculator` | Сборка результата по каждому дереву |

Таблица Ду - `config/diameters.yaml`, 18 номиналов от 50 до 1400.

## Смета и рейтинг

Пакет `cost`.

`VariantCostCalculator.calculate(variantId, engineering, unconnected)`:

- участок - `SegmentCostCalculator`: `L · cнов · Kгл · Kспец`, деньги в `long` рублях;
- новая камера - `ChamberCostScale` по шкале 3/5/8/12 млн;
- врезка - 5 млн только если `ExistingObjectType.HEAT_CHAMBER`;
- неподключённые - `UnconnectedPenaltyCalculator`;
- реконструкция накапливается в сводке и в `calculatedCost()` не складывается.

`ScoreCalculator` считает S в `double` и округляет один раз. `VariantRanker` сортирует варианты по сырому S и проставляет `rank`.

`VariantSummary` - поля выходной `variant_summary`. Идентификатор сводки: `summary_` + `variant_id`, например `summary_vA`.

## Варианты

Пакет `variants`. Класс `VariantGenerator` (в коде помечен как этап M7) связывает планировщик, инженерный расчёт и смету.

`generate(IngestResult)` по шагам:

1. `ExistingNetworkGeometry.fromIngest` - существующие линии в UTM для якорей и врезок.
2. Цикл по `DEFAULT_STRATEGIES`: сначала `JOINT_ALL` (vA), затем `SEPARATE_EACH` (vB), затем `CLUSTERED` (vC). Для C вызывается `cluster()` - группы точек по близости координат (`groupOf`).
3. На каждую стратегию: `evaluate` → `networkPlanner.plan(..., mode, groupOf, routingDn)` → `engineering.calculate` → `costCalculator.calculate` → объект `GeneratedVariant` с уже заполненным `VariantCost`.
4. Если `plan.getDiagnostics()` не пуст - вариант в `withViolations`, иначе в `built`. При одной точке B и C не строятся (совпали бы с A).
5. После цикла: `dropAvoidableUnconnected`, затем `refineAll` / `refineRoutingDn` (бюджет `ROUTING_DN_REFINEMENT_BUDGET_MS`).
6. `distinctness` отсекает содержательные дубли (одинаковая топология и смета).
7. `ranker.rank` и обрезка до `ranking.max_variants`.

Порядок стратегий в списке по умолчанию - A, B, C, но в GeoJSON ранг `1` получает лучший по S, не обязательно vA.

В GeoJSON `variant_id` равен `vA`, `vB`, `vC` (префикс `v` и код стратегии). Идентификатор сводки - `summary_vA` и аналоги.

| Класс | Роль |
|---|---|
| `VariantStrategy` | Код A/B/C и `PlanMode` |
| `VariantDistinctness` | Сравнение планов, отсев копий |
| `VariantRanker` | `rank` по raw S |
| `VariantSet` | Список вариантов + строки диагностики для лога M7 |

## Экспорт

Пакет `export`. `GeoJsonExporter` пишет FeatureCollection потоком.

На каждый вариант, в порядке ранга:

- `heat_network` - LineString новых участков;
- `heat_chamber` - Point только новых камер, с `diameter` и `cost`;
- `technical_node` - границы спецпрохода, смена параметра, при глубине - дополнительные узлы профиля;
- `variant_summary` - `geometry: null`, ровно одна на вариант.

Существующая камера отдельной геометрией не повторяется: она видна в `existing_chamber_tie_in_count` и `existing_chamber_tie_in_cost`.

`VariantSplitter` делит общий файл по `variant_id`. `ExportIds` сохраняет исходный JSON-тип идентификаторов входных объектов, на которые ссылается выход.

В плоском режиме `depth_start` и `depth_end` записаны как `null`. В режиме глубины - числа, метры до верха габарита.

## Задачи

Пакет `jobs`, контроллер `api.JobController`.

`JobService` хранит задачи в памяти процесса (`ConcurrentHashMap`). После перезапуска идентификаторы пропадают: результат лежит файлом в `data/jobs/{id}/result.geojson`, но реестр задач не восстанавливается. Для сдачи одного прогона этого достаточно; долгоживущая очередь в ТЗ не требуется.

Статусы: `QUEUED`, `RUNNING`, `DONE`, `FAILED`. Сообщение об ошибке попадает в поле `message`. Исчерпание памяти тоже переводит задачу в `FAILED`, а не оставляет её в `RUNNING`.

| Класс | Роль |
|---|---|
| `CalculationPipeline` | Единственный сценарий ingest → варианты → глубина → export; замок `planLock` |
| `JobProgress` | Интерфейс колбэка `update(stage, percent)` |
| `JobRecord` | Состояние одной задачи, путь к результату, explanations |
| `JobExecutorConfig` | `ThreadPoolTaskExecutor`: core=max=2, queue=100, `AbortPolicy` |
| `JobRejectedException` | Очередь полна → HTTP 503 |
| `JobNotReadyException` | Результат или explain раньше DONE → HTTP 409 |

`execute` открывает `FileOutputStream` на `data/jobs/{jobId}/result.geojson` и передаёт его в конвейер. Карта объяснений возвращается из `GeoJsonExporter` и кладётся в `JobRecord` до закрытия потока.

## Глубина

Пакет `depth`. `DepthTracer` читает готовый `NetworkPlan` и препятствия из ingest.

`DepthProfileSolver` строит профиль по цепочкам участков между камерами: площадка на спецпроходе, спуск и подъём на соседних участках, уклон ≤ 0,10. `DepthCoefficient` даёт Kгл. После профиля `SegmentCostCalculator` пересчитывает рубли участка, `ScoreCalculator` - S варианта.

Горизонтальные координаты не меняются. Новые технические узлы профиля добавляются в выход, если смена глубины не совпала с уже существующим узлом.

По умолчанию профиль глубины выключен. Включение в задаче API: `"enableDepth": true`.

Готовые файлы этого режима, если их забрал скрипт или `curl`:

| Среда | Команда | Куда пишется |
|---|---|---|
| Linux, Docker, без глубины | `./scripts/export-flat.sh --docker` | `data/result.geojson`, `data/result_vA.geojson`, `_vB`, `_vC` |
| Linux, Docker, с глубиной | `./scripts/export-depth.sh --docker` | `data/m11-sample.geojson`, `data/m11-sample_vA.geojson`, `_vB`, `_vC` |
| Windows, без глубины | `.\scripts\export-flat.ps1 -Docker` | `data\result.geojson`, `result_vA`, `_vB`, `_vC` |
| Windows, с глубиной | `.\scripts\export-depth.ps1 -Docker` | `data\m11-sample.geojson`, `m11-sample_vA`, `_vB`, `_vC` |

Скрипты с `--docker` и `-Docker` зовут `docker-compose` 1.29.2. Без флага ищут JAR на хосте. Поднять только стек, без расчёта: `./deploy.sh` или `.\deploy.ps1`. Пока профиль глубины выключен, экспорт пишет в те же поля `null`. Контейнер в обоих случаях дублирует полный ответ в `data/jobs/<jobId>/result.geojson`. Пошагово: [Запуск](09-zapusk.md), [Файлы с глубиной](10-glubina.md).

## Тесты

Каталог `backend/src/test/java/ru/heatnet`. Обычный прогон исключает тесты с тегом `slow`:

```powershell
mvn -f backend/pom.xml test
```

Полный прогон конкурсного набора (минуты):

```powershell
mvn -f backend/pom.xml test -Pcontest-full
```

`ContestPipelineDiagTest` гоняет все 17 точек через три стратегии и может перезаписать `data/result.geojson`. Это диагностический тест, не шаг сборки по умолчанию.

Порядок вызовов от `CalculationPipeline` до `GeoJsonExporter`, с методами `SteinerPlanner`, `SpecialCost`, `DiameterOptimizer` и `DepthProfileSolver`, разобран в [Коде](11-kod.md).
