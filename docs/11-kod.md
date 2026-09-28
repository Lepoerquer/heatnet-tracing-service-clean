# Код

Корень исходников: `backend/src/main/java/ru/heatnet`. Сборка - `backend/pom.xml`, Java 11, родитель Spring Boot 2.6.3. Тесты - `backend/src/test/java/ru/heatnet`.

Документ описывает, какой класс за что отвечает и в каком порядке их вызывает один расчёт. Предметные правила (отступы, камеры, формула S) - в [Обзоре](01-obzor.md). Запуск контейнера - в [Запуске](09-zapusk.md). Карта пакетов без деталей методов - в [Составе сервиса](03-moduli.md).

## Сквозной путь одного расчёта

Ниже - цепочка вызовов от сохранённого GeoJSON до байтов ответа. Имена методов - ориентир при чтении исходников.

```
JobService.execute(inputPath)
  CalculationPipeline.run(path, out, enableDepth, progress)
    IngestService.ingest(path)
      StreamingGeoJsonReader + SchemaValidator + NetworkTreeBuilder
    synchronized(planLock):
      VariantGenerator.generate(ingest)
        для каждой VariantStrategy:
          NetworkPlanner.plan(ingest, ..., PlanMode, groupOf, routingDn)
            RestrictionEngineFactory.beginRequest()
            planNetwork -> SteinerPlanner или TieInRouter/JointPlanner
            verifyAfterM5
            straightenAtActualDn(CrossingResolver.resolveAll(...))
            TopologyValidator.problems -> diagnostics
            RestrictionEngineFactory.endRequest()
          EngineeringCalculator.calculate
          VariantCostCalculator.calculate
        dropAvoidableUnconnected, refineRoutingDn, rank
    для каждого GeneratedVariant:
      [если enableDepth] DepthTracer.trace
    GeoJsonExporter.write(variants, ingest, out, enableDepth)
```

Вырожденный случай (нет вариантов после M7): `degenerateVariant` создаёт пустой план и штраф на каждую точку, чтобы в файле была одна `variant_summary` с ненулевым штрафом.

## `NetworkPlanner.plan` после сырой геометрии

Публичный `plan(..., PlanMode, groupOf, routingDn)` всегда оборачивает запрос в `RestrictionEngineFactory.beginRequest()` / `endRequest()`, чтобы кэш бандлов ограничений жил в рамках одного плана, а счётчики id новой сети (`NetworkTreeBuilder`, `CrossingResolver`) сбрасывались предсказуемо.

Последовательность после `planNetwork`:

1. `verifyAfterM5` - повторная проверка отступов при фактическом Ду каждого участка; точки, для которых маршрут стал невозможен, попадают в `lost`.
2. `crossingResolver.resolveAll` - разведение пересечений осей новой сети вне узлов.
3. `straightenAtActualDn` - удаление мелких изломов, ставших законными при меньшем Ду ветки (не трогает финальный заход §2.2).
4. `TopologyValidator.problems` - дерево, камеры, листья-ОКС; список строк идёт в `NetworkPlan.diagnostics`, расчёт не прерывается.

Запасной `planNetwork` без Штейнера: для каждой точки `TieInRouter` с `EntryAwareRouteFinder`, для близких пар `JointPlanner` по полному ΔS.

## Карта пакетов

| Пакет | Кто создаёт объекты | Главный фасад |
|---|---|---|
| `config` | свойства процесса, каталоги | `HeatnetProperties`, `HeatnetConfiguration` |
| `api` | HTTP | `FileUploadController`, `JobController`, `ServiceInfoController` |
| `api.dto` | тела запросов и ответов | `CreateJobRequest`, `JobStatusResponse`, `FileUploadResponse` |
| `ingest` | модель входного файла | `IngestService` |
| `geo` | перевод координат | `ProjectionService` |
| `rules` | можно ли провести отрезок | `RestrictionEngineFactory`, `DefaultSpatialConstraintEngine` |
| `routing` | полилиния между двумя точками | `RouteFinderFactory`, `RoutingPipeline`, `SharedVisibilityRouter` |
| `network` | дерево новой сети | `NetworkPlanner`, `SteinerPlanner` |
| `calc` | расходы, Ду, камеры | `EngineeringCalculator` |
| `calc.model` | дерево, участок, узел, врезка | `NewNetworkTree`, `NewSegment`, `TieInPoint` |
| `calc.reference` | таблицы из YAML | `ReferenceData`, `ReferenceDataLoader` |
| `cost` | рубли и S | `VariantCostCalculator`, `ScoreCalculator` |
| `variants` | до трёх планов | `VariantGenerator` |
| `export` | выходной GeoJSON | `GeoJsonExporter` |
| `jobs` | очередь и конвейер | `JobService`, `CalculationPipeline` |
| `depth` | профиль по готовой линии | `DepthTracer`, `DepthProfileSolver` |
| `cli` | тот же конвейер без HTTP | `ContestCliRunner` |

`calc.model` общий: планировщик его заполняет, смета и экспорт только читают.

## Конвейер `CalculationPipeline`

Файл: `jobs/CalculationPipeline.java`. Spring-бин. Конструктор получает `IngestService`, `NetworkPlanner`, `VariantGenerator`, `ReferenceData`, `DepthTracer`, `GeoJsonExporter`.

Два публичных входа с одинаковым хвостом:

- `run(InputStream, OutputStream, enableDepth, JobProgress)` - поток, один проход разбора;
- `run(Path, OutputStream, enableDepth, JobProgress)` - файл на диске, для больших файлов включается двухпроходное чтение.

Дальше приватный `run(IngestResult, ...)`:

1. Стадия `PLAN`, 25%. Под `synchronized (planLock)` вызывается `variantGenerator.generate(ingest)`. Замок сериализует сборку планов между заданиями: счётчики id новой сети не смешиваются, два дерева не строятся одновременно. Профиль глубины и запись GeoJSON стоят снаружи замка.
2. Если список вариантов пуст, `degenerateVariant` строит пустую сеть и штраф на каждую точку из `ingest.getOksConnectionPoints()`. Так в файле всё равно есть одна `variant_summary`, и S не становится нулём при непустом наборе точек.
3. Стадия `COST`, 65%. Для каждого `GeneratedVariant` берётся уже посчитанный `VariantCost`. Если `enableDepth`, стадия `DEPTH` и `depthTracer.trace(...)`.
4. Стадия `EXPORT`, 92%. `exporter.write` пишет GeoJSON и возвращает `Map` объяснений. Ключ карты - выходной `id` объекта.
5. В лог пишется число вариантов, id лучшего, число деревьев, число неподключённых и S. Стадия `DONE`, 100%.

Прогресс наружу уходит через `JobProgress.update(stage, percent)`. `JobService` пишет это в `JobRecord`, который отдаёт `GET /api/jobs/{id}`.

## Загрузка

### `StreamingGeoJsonReader`

Читает `FeatureCollection` токенами Jackson, не собирая весь документ в одно дерево. Порядок ключей внутри Feature не важен. Число с запятой принимается. Регистр `object_type` нормализуется. `id` сохраняет JSON-тип: число и строка с тем же текстом - разные объекты.

### `SchemaValidator`

На каждый `RawFeature` возвращает принять, отвергнуть или пропустить:

| Вид | Условие отказа |
|---|---|
| `oks_connection_point` | нет положительного `flow_tph` или геометрия не Point |
| `restriction` | пустой `restriction_type` или нет геометрии |
| `heat_network` | нет `diameter` или геометрия не LineString |
| неизвестный `object_type` | предупреждение, объект пропускается, расчёт не падает |

`oks_future` принимается как справка. `heat_load` в расход не переводится. `source` и `heat_chamber` требуют идентификатор и тип. Диаметр камеры из атрибута не обязателен: его потом выводит `ChamberIncidence` как максимум примыкающих труб.

### `IngestService`

`ingest(Path)` смотрит размер. Ниже `TWO_PASS_THRESHOLD_BYTES` (256 МБ) - один проход `ingest(InputStream)`. Выше - два прохода:

1. фильтр «всё, кроме restriction»;
2. только restriction, чья рамка пересекает район.

Район (`areaOfInterestWgs`): точки подключения и ближайшие к ним участки. Если охват существующей сети не больше 3 км, в район входит вся сеть. Вокруг района запас `AREA_MARGIN_M` = 1000 м. Сообщение `INGEST_TWO_PASS` попадает в отчёт.

`build` прогоняет `SchemaValidator`, затем `NetworkTreeBuilder` (совмещение концов в допуске `heatnet.ingest-snap-tolerance-m`, по умолчанию 1 м) и `ChamberIncidence`. Результат - `IngestResult` плюс `IngestReport`.

`UploadIngestService` сохраняет байты в `data/uploads/{fileId}/` и отдаёт `fileId`. Отчёт - `GET /api/files/{id}/report`.

### `ProjectionService`

WGS 84 в EPSG:32637 и обратно. На входе и выходе GeoJSON ось первая - долгота, вторая - широта. В UTM X - восток, Y - север. `GeometryRepair` чинит кольца через JTS `GeometryFixer` (нужна JTS 1.19).

## Ограничения

`RestrictionEngineFactory.createBundle(ingest, dn)` собирает `SpatialConstraintBundle`: движок плюс контуры запретных зон.

Зависимость от Ду принципиальная. Отступ от существующего ОКС 5, 7 или 9 м плюс половина ширины пары из `gabarits.yaml`. Бандл, собранный на Ду 500, нельзя подставить в поиск ветки Ду 80: фабрика отдаёт другой бандл, а чужой Ду даёт `RulesException`.

`DefaultSpatialConstraintEngine`:

- `isSegmentBlocked` - ребро графа и хорда спрямления;
- `extractSpecialSections` - интервалы вдоль линии с Kспец, их ест и поиск, и `RouteSegmentSplitter`;
- пересечения и порталы дороги/трамвая.

`BufferFactory` добавляет к нормативному отступу половину габарита своей пары и половину габарита препятствия, где таблица это задаёт. `RestrictionIndexBuilder` кладёт зоны в STRtree. `PortalGateGenerator` ставит створы с углом к границе не меньше 45 градусов. `RestrictionTypeMapper` переводит входной `restriction_type=oks` на правило `oks_existing` из `rules.yaml`.

Свой полигон точки подключения не вырезается из общего бандла дыркой. Его снимают только с финального прямого участка, это делает связка `OwnEntryCandidates` и планировщика.

Допуск сравнения с нормативом - `geometry.tolerance_m` (0,01 м).

## Поиск пути

`RouteFinder` - контракт «две точки UTM и подсказка Ду». Неудача - `RouteResult` со статусом `NOT_FOUND`, не исключение.

`RouteFinderFactory` выбирает Ду и бандл:

- `leafDn(flow)` - Ду одной точки по её расходу;
- `magistralDn(totalFlow)` - Ду графа по сумме расходов;
- `bundle(ingest, dn)` и `bundleForTarget` - набор ограничений, у цели можно учесть свой полигон;
- `createForDn` - искатель под конкретный Ду.

Основной искатель для дерева - `SharedVisibilityRouter`. Он строит граф видимости по контурам запретных зон в рамке расчёта (`build(area)`). Вершины контура сдвигает наружу `RoutingGeometry.outwardVertex`: по биссектрисе угла, в ту сторону, где точка оказывается снаружи полигона. Для П-образного двора сдвиг «от центроида» уводил бы угол внутрь корпуса, поэтому биссектриса.

Запасной искатель - `GridRouteFinder`, сетка `grid_cell_m` (2 м), если граф не собрался за `vg_deadline_ms` или вершин больше `grid_fallback_min_vertices`. Классический portaled visibility (`PortaledVisibilityRouteFinder`, `VisibilityGraphBuilder`, `GraphPathSearcher`) остаётся для одиночных маршрутов запасного ввода.

`SpecialCost.weightedLength(segment, sections)`:

```
цена = длина + сумма по кускам (Kспец - 1) * длина куска
```

Интервалы спецучастков проецируются на звено. На перекрытии интервалов остаётся больший K. Пустой список секций - цена равна длине.

`RouteSmoother` стягивает вершины, если хорда не запрещена. `TurnRepair` дожимает излом больше 90 градусов. `CrossingFixer` поправляет угол входа в дорогу. `NewNetworkClearance` не даёт новой трубе подойти к уже принятой ближе 0,3 м вне общего узла.

`OwnEntryCandidates` строит лучи финального захода:

- стена - звено границы своего полигона на минимальном расстоянии до точки, окно 0,2 м;
- глубина внутрь полигона не больше `max(лучший заход + 0,2 м, расстояние до границы + 1 м)`;
- шаг вдоль стены 0,25 м;
- `computeRelaxed` - тот же критерий ближайшей стены, без грубой склейки соседних лучей.

## Дерево `SteinerPlanner`

Класс пакетно-приватный, снаружи его вызывает только `NetworkPlanner`.

`plan(...)` принимает существующую сеть, геометрию, список точек, `PlanMode`, карту групп и необязательный `routingDnOverride`. Если override больше нуля и меньше Ду магистрали, граф строится на override. Так генератор вариантов делает второй проход «тоньше», не раздувая отступы выше магистрали.

Дальше:

1. `bundle` на выбранный Ду. Нет движка - `return null`.
2. Рамка по точкам подключения и ближайшим точкам существующих труб.
3. `cachedRouter` или новый `SharedVisibilityRouter.build`. Граф не собрался - `return null`.
4. `networkAnchors` - камеры со свободным портом и точки на трубах. Пустой список - `return null`.
5. На каждую точку `prepareTarget`: заходы `OwnEntryCandidates` под этот Ду.
6. Жадный цикл: пока есть неприсоединённые точки, `collectAttempts` ищет самую дешёвую пару «якорь - точка».
7. `attemptFromHit` склеивает путь графа с финальным прямым заходом. Излом в точке Q меньше 2 градусов убирается, если прямая с предыдущей вершины законна.
8. `straighten` / `shortcutOk` выкидывают лишние вершины, сравнивая цену через `SpecialCost`.
9. `refineBranch` сдвигает точку ответвления вдоль питающего участка, если соседняя точка дешевле.
10. Неудачная попытка откатывается и не оставляет камеру без ветки.
11. `polishLeaves` до трёх кругов перепрокладывает готовые ветки.
12. `LeafDnPolish` повторяет доводку в графе фактического Ду ветки.

Якоря на существующей трубе берутся с шагом `PIPE_SAMPLE_M` = 40 м, не ближе `PIPE_END_MARGIN_M` = 2 м к концу участка. На уже построенной новой сети шаг `TREE_SAMPLE_M` = 22 м. Якоря ближе `ANCHOR_DEDUPE_M` = 5 м схлопываются. Точка на трубе ближе 10 м к камере, у которой есть свободный порт, не используется: врезка идёт в камеру (`tieOccupied`, правило радиуса из `rules.yaml`).

`SEPARATE_BRANCH_PENALTY_M` = 250 добавляется к цене ответвления только в режиме `SEPARATE`. Это параметр стратегии, не строка приложения.

`headingOk` проверяет, что путь от якоря на уже построенной сети не разворачивается больше чем на 90 градусов относительно питающего участка. Направление берёт `headingAt` на метр назад по линии (`HEADING_BACK_M`).

Кэш графа и кэш заходов висят на слабой ссылке на `IngestResult`: другой файл сбрасывает кэш. Ключ графа - Ду и рамка.

Если `SteinerPlanner.plan` вернул `null`, `NetworkPlanner.planNetwork` строит деревья по одному через `TieInRouter` и при необходимости сливает близкие точки в `JointPlanner`. `JointPlanner` сравнивает два готовых дерева по полному S (общая врезка против двух отдельных). Порог `joint_group_m` (250 м) только ограничивает, какие пары вообще пробуются. `tie_in_attempts` (2) - сколько разных точек сети пробовать после `NOT_FOUND`.

### После геометрии

`NetworkPlanner` на сыром плане:

- `RouteSegmentSplitter` - куски `base` / `special` и узлы на границах Kспец;
- `EngineeringCalculator.calculate` - расходы и Ду;
- `verifyAfterM5` - имя метода в коде: повторная проверка отступов при фактическом Ду каждого участка;
- `straightenAtActualDn` - хорда, которая при меньшем Ду ветки стала законной и короче;
- `DnBoundarySplitter` - узел на смене Ду;
- `CrossingResolver` - пересечение своих осей вне узла, коллинеарное наложение не считается пересечением;
- `TopologyValidator` - одно дерево на врезку, без цикла, развилка только в камере.

`entryExcessM` суммирует по точкам, насколько финальный прямой участок идёт внутри своего полигона дальше расстояния до ближайшей наружной стены плюс 1 м. Ноль значит, что заход соответствует ближайшей стене в принятом допуске.

## Диаметры `EngineeringCalculator`

На каждое `NewNetworkTree`:

1. `FlowAggregator` складывает `flow_tph` от листьев к врезке. Существующая сеть в эту сумму не входит.
2. `DiameterSelector` берёт минимальный Ду таблицы с `capacity_tph` не меньше расхода участка.
3. `DiameterOptimizer.optimize` ищет назначение на всё дерево. Если точного решения нет, `LengthLimitValidator` поднимает Ду по шагам и пишет диагностику `LENGTH_LIMIT_EXCEEDED`.

Ограничения оптимизатора:

- Ду не ниже минимума по расходу;
- на цепочке постоянного расхода (через технические узлы и камеры без ветвления) Ду один и тот же;
- от точки подключения к врезке Ду не убывает;
- по каждому пути «врезка - точка» длина плети одного Ду не больше `max_length_m`;
- общий участок входит в каждый путь, параллельные ветки между собой не складываются;
- среди допустимых назначений берётся меньшая стоимость участков и камер разветвления.

Поле `length_limit_max_dn_steps` в YAML загрузчик читает. Потолком «только плюс один номинал» оно не работает: выбирается следующий минимальный Ду, который закрывает и расход, и длину.

`ChamberSizing` смотрит наибольший Ду участков новой камеры. `TieInSizing` запоминает, врезка это в `HEAT_CHAMBER` или в трубу. `ReconstructionEngine` может оценить реконструкцию существующей сети от врезки к источнику. Эти рубли в `calculatedCost()` не попадают и в GeoJSON отдельным типом не пишутся.

## Смета

| Класс | Формула |
|---|---|
| `SegmentCostCalculator` | `Money.product(L, cнов, Kгл, Kспец)` |
| `ChamberCostScale` | ступень 3 / 5 / 8 / 12 млн по Ду |
| `UnconnectedPenaltyCalculator` | `100_000_000 + 500_000 * G` на точку |
| `ScoreCalculator.raw` | `0.7 * (C / 25_000_000) + 0.3 * (L / 100)` |
| `ScoreCalculator.score` | `raw` один раз `setScale(score_scale, HALF_UP)` |
| `Money.add` | `Math.addExact`, переполнение - исключение |

`VariantSummary.Builder.constructionCost` = участки + новые камеры + врезки в существующие камеры. `calculatedCost` = это плюс штраф. `addReconstruction` копит диагностику отдельно. `getLength()` для S - только `newNetworkLength`.

`VariantCostCalculator.calculate` пропускает 5 млн, если `TieIn.getExistingObjectType()` не `HEAT_CHAMBER`.

`VariantRanker` сортирует по `rawScore` и проставляет `rank`, начиная с 1. Идентификатор сводки - `"summary_" + variantId`, то есть `summary_vA`.

## Варианты

`VariantStrategy`:

- `JOINT_ALL`, код `A`, в файле `vA`;
- `SEPARATE_EACH`, код `B`, в файле `vB`;
- `CLUSTERED`, код `C`, в файле `vC`.

`evaluate` ставит `variantId = "v" + strategy.getCode()`. Если у плана непустой список замечаний геометрии (пересечение своих участков, поворот больше 90 градусов, развилка вне камеры), вариант в обычную выдачу не идёт: метод бросает `NetworkException`. Такие планы копятся в `withViolations`. Если годных планов не осталось ни одного, в выдачу берётся лучший по S из списка с замечаниями, и в лог пишется предупреждение.

`cluster` делит точки по координатам на группы для режима C. При одной точке стратегии B и C пропускаются: они совпали бы с A.

`refineAll` / `refineRoutingDn` - второй план. Новый план принимается, если отступление захода меньше больше чем на 0,5 м, даже когда S чуть хуже. Иначе сравнивается S.

## Экспорт

`GeoJsonExporter.write` идёт по вариантам в порядке ранга.

Участок `heat_network`: `id` с префиксом варианта (`outId`), `variant_id`, `start_node_id`, `end_node_id`, `flow_tph`, `diameter`, `length`, `laying_method` (`base` или `special`), `depth_start`, `depth_end`, `cost`, `construction_cost`, `k_spec`. Линия - `LineString` в долготе и широте. Концы линии подменяются координатами узлов, чтобы стык не разъезжался из-за округления.

`technical_node` - точка без стоимости. `heat_chamber` - только новая камера, свойства `diameter` и `cost`. Существующая камера точкой не повторяется.

`variant_summary`: `geometry` = null, поля `rank`, `construction_cost`, `chamber_construction_cost`, `existing_chamber_tie_in_count`, `existing_chamber_tie_in_cost`, `unconnected_penalty`, `calculated_cost`, `new_network_length`, `score`, `unconnected_oks_ids`.

Параллельно заполняется карта объяснений: длина, Ду, расход, Kспец, Kгл, текст правила. Её отдаёт `JobService.explain`.

`VariantSplitter.writeVariant` копирует из общего файла только объекты с тем же `variant_id`, что передан в query (`vA`, `vB`, `vC`). Сравнение строковое, без отрезания префикса.

`ExportIds` хранит исходный тип id входных объектов. Новый участок всегда строка с префиксом варианта.

## Глубина

`DepthTracer.trace` не двигает план в плане. `fromIngest` переводит препятствия в UTM. `DepthProfileSolver.solve(line, dn, obstacles)`:

- обычная отметка 3,0 м до верха габарита;
- на точечном пересечении сравнивает проход выше и ниже и берёт более дешёвый по Kгл;
- площадка 4 м только у газа, кабеля и существующей теплосети;
- уклон не круче `max_slope` (0,10);
- если между пересечениями не помещается выход на 3,0 м, остаётся один глубокий коридор;
- `solve` возвращает `null`, если вся линия остаётся на обычной глубине.

`DepthCoefficient`: при h не больше 3 м коэффициент 1, иначе `1 + 0,10 * (h - 3)`. На наклонном куске берётся среднее концов. После этого `SegmentCostCalculator` и `ScoreCalculator` пересчитывают рубли и S этого варианта. Дополнительные узлы перегиба попадают в выход как `technical_node`, если не совпали с уже существующим узлом.

В плоском прогоне экспорт пишет в `depth_start` и `depth_end` JSON `null` и солвер не вызывает.

## HTTP и очередь

`JobExecutorConfig` создаёт `ThreadPoolTaskExecutor` с именем бина `calculationExecutor`. `AbortPolicy`: лишняя задача не ждёт молча, а отвергается.

`JobService.submit`:

- нет `fileId` - `IllegalArgumentException`, ответ 400;
- нет файла загрузки - `NoSuchElementException`, ответ 404;
- очередь полна - `JobRejectedException`, ответ 503;
- иначе запись `QUEUED` и `executor.execute`.

`execute` ставит `RUNNING`. Каталог `data/jobs/{jobId}/`, файл `result.geojson`. Любое `Exception` и `Error` (в том числе нехватка памяти) переводят запись в `FAILED`, кладут текст в `message` и удаляют недописанный результат. Успех - `DONE` и путь к файлу.

Реестр - `ConcurrentHashMap` в памяти процесса. После перезапуска контейнера `jobId` пропадает. Файл на диске остаётся, потому что `./data` смонтирован в контейнер.

`JobController.create` отвечает `202 Accepted`. `result` при статусе не `DONE` бросает `JobNotReadyException`, это `409`. Query `variant` нарезает файл через `VariantSplitter` во временный `result_variant_<hex>.geojson` рядом с результатом и отдаёт его как вложение `application/geo+json`.

`ContestCliRunner` включается свойством `heatnet.cli.input`. Он вызывает тот же `CalculationPipeline.run(Path, ...)`. Флаг `heatnet.cli.depth` - это `enableDepth`. Флаг `heatnet.cli.split-variants` после записи режет файл по `variant_id` в соседние имена.

`ApiExceptionHandler` переводит исключения в `ApiErrorResponse`: время, статус, сообщение, путь. Слишком большой multipart - `413`. Битый JSON - `400`.

## Справочники в коде

`ReferenceDataLoader` читает четыре YAML при старте в `ReferenceData`. Классы-таблицы: `DiameterTable`, `GabaritTable`, `RulesConfig`, `DepthRules`. Формулы не содержат зашитых цен: они берут число из этих объектов. Нет обязательного ключа - процесс не стартует.

В контейнере путь - `/app/config` (`HEATNET_CONFIG_DIR`). Это каталог `config/` с хоста.

## Объекты, которые ходят между пакетами

### `RawFeature`

Один объект входного GeoJSON до сборки сети. Поля: строковый `id`, флаг `numericId`, исходное значение `originalId` (число остаётся числом, целое нормализуется в `Long`), `Kind`, карта свойств, геометрия WGS 84.

`Kind`: `OKS_CONNECTION_POINT`, `OKS_FUTURE`, `RESTRICTION`, `HEAT_NETWORK`, `HEAT_CHAMBER`, `SOURCE`, `UNKNOWN`.

Если у двух объектов разных типов совпал текст id, внутренний ключ делается уникальным, а в выход уходит `originalId`. Так число `116` и строка `"116"` не затирают друг друга.

### `NewSegment`

Направление фиксировано: `fromNodeId` ближе к врезке, `toNodeId` ближе к точке подключения. Длина в метрах UTM, обязана быть больше нуля. `kSpec` и `kDepth` не меньше 1. У `LayingMethod.BASE` коэффициент спецпрохода обязан быть ровно 1, иначе конструктор бросает `IllegalArgumentException`. Фабрики `base` и `special` собирают типичные куски.

### `NewNode` и `NodeKind`

| `NodeKind` | Где появляется |
|---|---|
| `TIE_IN` | Корень дерева, id совпадает с `TieInPoint` |
| `NEW_CHAMBER` | Врезка в трубу или развилка |
| `TECHNICAL_NODE` | Граница спецпрохода, смена Ду, перегиб глубины |
| `OKS_CONNECTION` | Лист, внутри хранится id точки подключения |

### `NewNetworkTree`

Конструктор сам проверяет форму и при ошибке бросает `CalcException`:

- один корень `TIE_IN` с тем же id, что у `TieInPoint`;
- у каждого узла, кроме корня, ровно один входящий участок;
- нет циклов;
- все узлы достижимы от корня;
- `OKS_CONNECTION` только листья;
- id узлов и участков уникальны.

`segmentsTopDown()` обходит от врезки к листьям. Этот порядок используют суммирование расхода и смета. `childrenByNode` и `incomingByNode` - индексы для подъёма Ду и для цепочки ветки в `leafChain`.

### `TieInPoint`

Хранит, куда село дерево: существующая камера или тело трубы (`ExistingObjectType.HEAT_CHAMBER` или `HEAT_NETWORK`), id существующего объекта и координату врезки. От типа зависит, начислит ли смета 5 млн.

### `NetworkPlan`

Неизменяемые списки:

- `trees` - одно или несколько `NewNetworkTree`;
- `layouts` - `NetworkTreeLayout` с линиями участков в UTM, индекс совпадает с деревом;
- `unconnectedOks` - точки без маршрута, id и `flow_tph`;
- `jointConnection` - истина, если хотя бы в одном дереве больше одной точки подключения;
- `diagnostics` - строки нарушений геометрии. Пустой список - норма. Непустой список генератор вариантов в обычную выдачу не берёт.

### `RouteResult`

`FOUND` несёт `LineString` в EPSG:32637, длину и список `SpecialSection`. Пустая линия превращается в `NOT_FOUND`. Исключение при отсутствии пути не бросается.

### `GeneratedVariant`

Четвёрка: `variantId` (`vA` / `vB` / `vC`), `VariantStrategy`, `NetworkPlan`, `VariantCost`. Смета уже посчитана к моменту, когда объект попадает в `CalculationPipeline`.

## Жадный цикл `SteinerPlanner` по шагам

Метод `plan` после сборки графа и целей крутит цикл, пока список `waiting` не пуст.

Предел итераций: `число точек * (длина LEVEL_WINDOWS + 2) + 2`. Окна уровней захода заданы в `OwnEntryCandidates.LEVEL_WINDOWS`: 0,2 м, 1 м, 3 м, 6 м, 12 м, 25 м и бесконечность. Уровень 0 - ближайшая стена в окне 0,2 м. Следующие уровни разрешают стену дальше. Повышение уровня происходит только когда на текущем уровне ни одна попытка не прикрепилась.

На каждом шаге:

1. `dropOccupiedTies` убирает якоря существующих камер, которые уже заняты до лимита 4 участков.
2. Ожидающие точки группируются по номеру группы. В режиме `CLUSTERED` чужая группа не видит якоря чужих деревьев. В `JOINT` и `SEPARATE` группа одна.
3. `signature` запоминает, что у группы не изменились ни свои ветви, ни уровень захода. Повтор с той же подписью пропускается: чужие ветви только добавляют запреты, пересчёт ничего нового не даст.
4. Якоря шага = свободные точки существующей сети плюс `treeAnchors` уже построенных деревьев этой группы.
5. `collectAttempts` гоняет граф от каждого якоря до целей. `rayHitAttempts` дополнительно пробует луч захода, который упирается в уже построенную линию.
6. Попытки сортируются сначала по уровню захода (меньше лучше), потом по цене.
7. `attach` вставляет первую попытку, которая не пересекла чужие ветви и не нарушила поворот. Точка уходит из `waiting`. В лог уровня debug пишется `STEINER + ОКС`, уровень, округлённая цена и подпись «новая врезка» или «ответвление».
8. Если ни одна попытка не встала, у всех ожидающих, у кого `level < maxLevel`, уровень увеличивается на 1, кэш неудачных подписей сбрасывается, цикл повторяется.
9. Если повышать уже некуда, цикл выходит. Оставшиеся точки становятся `UnconnectedOks`.

Пустой список деревьев - `return null`, и наружный `NetworkPlanner` включает запасной ввод.

`branchPenalty` равен 0, кроме режима `SEPARATE`, где он равен `SEPARATE_BRANCH_PENALTY_M` (250). Штраф прибавляется к цене попытки-ответвления, не к новой врезке в существующую сеть.

### Резерв чужого захода

`prepareTarget` для точки внутри своего полигона считает отступ `clearanceM`: норматив `oks_existing` для этого Ду плюс половина ширины пары. Если своего полигона нет, единственный «заход» - сама точка, `maxLevel = 0`.

У лучшего захода уровня 0 строится короткий резервный отрезок `reserved`: от точки наружу на 3 м вдоль луча за стену. Пока точка ещё в `waiting` и её уровень 0, `reservations` отдаёт этот отрезок. `crossesReserved` отвергает путь, который пересекает резерв чужой точки. Иначе жадный шаг мог бы провести чужую ветку прямо перед ближайшей стеной и оставить точке только дальний заход.

Свой резерв путь не блокирует.

### Кэш графа

`ROUTER_CACHE_SLOTS = 8`. Карта `routers` - `LinkedHashMap` с доступом по порядку: старые Ду вытесняются. Ключ - Ду и рамка. Ссылка на `IngestResult` слабая: другой файл обнуляет кэш. Отдельный кэш `entriesCache` хранит список заходов по ключу `idТочки|Ду`.

Один граф переиспользуется тремя стратегиями, если Ду и рамка совпали. Уточняющий проход и доводка ветки на меньшем Ду занимают другие слоты, а не пересобирают магистральный граф.

### Доводка `polishLeaves`

После цикла, не больше `POLISH_MAX_ROUNDS` (3) кругов. Круг вызывает `polishLeaves`, тот для каждого листа вызывает `polishLeaf`.

Новая геометрия ветки принимается, если уровень захода не хуже и взвешенная длина меньше старой хотя бы на `POLISH_MIN_GAIN_M` (0,5 м). Иначе дерево возвращается к снимку. Точка ответвления и набор участков не меняются, поэтому расходы и Ду, посчитанные следом, остаются согласованы с топологией.

Если фактический Ду цепочки ветки меньше Ду графа плана, `LeafDnPolish.context` поднимает отдельный граф и отдельный набор заходов этого Ду. `levelOfCurrent` пересчитывает уровень старой ветки уже в окнах меньшего Ду. Если при тонком Ду ближайшая стена доступна, а при толстом была закрыта, ветка к ближайшей стене принимается, даже когда она немного длиннее. `noDnIncrease` отклоняет замену, после которой какой-либо участок дерева получил бы Ду больше прежнего.

В лог info пишется `STEINER доводка веток`, если улучшилась хотя бы одна ветка. Затем `STEINER mode=... connected=... lost=... trees=...`.

### `OwnEntryCandidates.compute`

Точка подключения P, граница своего полигона B, точка выхода на перпендикуляр Q за пределами зоны отступа. Финальный участок - один прямой отрезок Q-B-P. Длина внутри полигона равна расстоянию P-B.

Стена - грань, ближайшая к P, не ближайшая вершина контура. Если нормаль снова входит в своё здание, упирается в чужое ограничение или идёт вплотную к другой части того же контура, берётся следующая грань по расстоянию. `computeRelaxed` оставляет ту же стену и ту же нормаль, но ставит несколько Q с шагом `RAY_STEP_M` = 0,25 м, чтобы путь мог выйти на перпендикуляр раньше. Наклонять финальный отрезок этот набор не имеет права.

Подход к Q считается движком полного бандла (свой корпус запрещён). Отрезок Q-P считается движком `bundleForTarget`, где буфер своего полигона снят.

## Запасной путь в `NetworkPlanner`

`plan` сначала зовёт `SteinerPlanner`. `null` означает, что граф или якоря не собрались. Тогда `planNetwork` для каждой точки вызывает `TieInRouter`: кандидаты `TieInCandidates` (камера в радиусе 10 м, иначе проекция на трубу), затем `RoutingPipeline.findLeafRoute`. Число разных точек сети при `NOT_FOUND` - `tie_in_attempts`.

`EntryAwareRouteFinder` оборачивает искатель и не отдаёт линию, которая входит в свой корпус не через подготовленный заход.

`JointPlanner` для пары точек ближе `joint_group_m` считает два полных дерева и оставляет то, у которого меньше S: одна врезка на двоих или две отдельные. Решение принимается по знаку разницы S, не по метрам между точками.

`ChamberDegreeCalculator` считает уже примыкающие участки существующей камеры. Свободных портов нет - камера не якорь.

После любого успешного плана, штейнерова или запасного, один и тот же хвост: нарезка спецпрохода, расчёт Ду, повторная проверка отступов методом `verifyAfterM5`, спрямление `straightenAtActualDn`, узлы смены Ду, `CrossingResolver`, `TopologyValidator`. Имя `verifyAfterM5` - это метод в `NetworkPlanner`, не отдельная часть поставки.

## Как `DepthTracer` переписывает смету

`trace` получает план, уже посчитанный `EngineeringResult` и `VariantCost`. Для каждого участка layout даёт линию в UTM. `DepthProfileSolver.solve` возвращает список `DepthSpan` или `null`, если вся линия остаётся на 3,0 м.

Ненулевой профиль режет линию на куски с постоянным уклоном. Концы куска - `depth_start` и `depth_end`. Если перегиб не совпал с камерой, создаётся `DepthExtraNode`, экспорт пишет его как `technical_node`.

`DepthCoefficient.value(h)` используется и на концах, и как среднее на наклоне. Новые `NewSegment` с обновлённым `kDepth` заново проходят `SegmentCostCalculator`. Итог подменяет карту стоимостей участков в `VariantCost`, затем `ScoreCalculator` пишет новый `score` в сводку. Ранг между вариантами после этого пересчитывается тем же компаратором, что и в плоском прогоне: по неокруглённому S.

Горизонтальные координаты `NetworkTreeLayout` этот класс не меняет.

## Тесты

Каталог `backend/src/test/java/ru/heatnet` повторяет пакеты основного кода. Обычный `mvn test` исключает тег `slow`. Профиль Maven `contest-full` включает прогон всех 17 точек конкурсного набора. `ContestPipelineDiagTest` вызывает тот же конвейер и может перезаписать `data/result.geojson`.

Отдельные проверки, которые полезно искать по имени класса:

| Тест | Что фиксирует |
|---|---|
| `SpecialCostTest` | Kспец только на спецучастке, наложение берёт max |
| `OwnEntryRelaxedTest` | заход доводки через ближайшую стену |
| `RoutingGeometryOutwardTest` | биссектриса наружу у вогнутого угла |
| `LeafDnPolish` (тест рядом с планировщиком) | ветка в графе своего Ду проходит узкий двор |
| тесты `ScoreCalculator` / эталон §7.3 | S = 0,6913 на контрольном примере |
