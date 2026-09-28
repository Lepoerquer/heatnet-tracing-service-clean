# Конфигурация

Справочники лежат в `config/` и читаются при старте. Путь переопределяется переменной `HEATNET_CONFIG_DIR`. В Docker каталог монтируется в `/app/config` только для чтения.

Правка YAML требует перезапуска процесса. Схема ключей проверяется загрузчиком: отсутствующее обязательное поле роняет старт, а не подставляет ноль в середине расчёта.

## `diameters.yaml`

18 номиналов. На каждую строку:

| Поле | Смысл |
|---|---|
| `dn` | Условный диаметр |
| `capacity_tph` | Пропускная способность, т/ч |
| `max_length_m` | Предельная длина плети, м |
| `new_cost_rub_m` | Цена метра нового строительства |
| `reconstruction_cost_rub_m` | Цена метра реконструкции (в S не входит) |

Источник комментария в файле - таблица диаметров приложения. Обе редакции PDF по этим 18 строкам совпадают.

## `gabarits.yaml`

На каждый Ду: `outer_diameter_m`, `gap_m`, `width_m`, `height_m`. Ширина пары уже посчитана как `2 · D + просвет`. Её половина добавляется к нормативному отступу.

## `rules.yaml`

### `restrictions`

Ключ - тип ограничения после нормализации (`oks` входного файла отображается на `oks_existing`).

- `rule: prohibited` и `min_offset_m`, либо `min_offset_by_dn` со ступенями `dn_from`, `dn_to`, `offset_m`.
- `rule: special`, плюс `k_spec`, `min_crossing_angle_deg` (для дороги и трамвая), `special_zone`.

`special_zone.kind`:

- `polygon_margin` - полигон и `margin_m` вдоль трассы с каждой стороны (дорога, трамвай, 3 м);
- `point_margin` - `margin_m` в обе стороны от точки пересечения (газ, кабель, теплосеть, 2 м).

### `overlap`

`k_spec_mode: max`. `prohibited_has_priority: true` - запретная зона перекрывает спецпроход.

### `angle`

`k_angle: 1.0`. Список `standard_angles_deg` и `angle_tolerance_deg` остаются в файле как параметры загрузчика. На стоимость участка они не влияют. Поворот ограничен 90° в коде поиска.

### `network`

| Ключ | Значение | Смысл |
|---|---:|---|
| `tie_in_chamber_radius_m` | 10 | Врезка в камеру, если проекция на трубу не дальше |
| `max_segments_per_chamber` | 4 | Вместе с уже существующими участками |
| `length_limit_max_dn_steps` | 1 | В загрузчике есть, потолком Ду не служит |

### `geometry`

`tolerance_m: 0.01` - допуск сравнения расстояния с нормативом.

### `routing`

| Ключ | По умолчанию | Смысл |
|---|---:|---|
| `workspace_margin_m` | 200 | Запас рамки вокруг концов маршрута |
| `vertex_outward_m` | 0,05 | Вынос вершины буфера наружу, м |
| `vertex_simplify_m` | 0,005 | Упрощение контура, меньше выноса |
| `turn_penalty_m` | 10 | Штраф поиска за поворот, м условной длины |
| `grid_cell_m` | 2 | Шаг запасной сетки |
| `grid_fallback_min_vertices` | 8000 | С этого размера графа включается сетка |
| `vg_deadline_ms` | 12000 | Бюджет сборки графа видимости |
| `grid_deadline_ms` | 25000 | Бюджет сетки |
| `route_budget_ms` | 20000 | Бюджет одного поиска пути |
| `crossing_budget_ms` | 15000 | Бюджет разведения пересечений |
| `cluster_prefix_m` | 20 | Общий префикс маршрутов кластера |
| `joint_group_m` | 250 | До этого расстояния пробуется общая врезка |
| `tie_in_attempts` | 2 | Число разных точек сети при `NOT_FOUND` |
| `start_exit_max_m` | 12 | Выход из буфера у точки старта |

### `costs`

`tie_in_rub: 5000000` - только существующая камера.

`chamber_scale` - четыре ступени, см. [Обзор](01-obzor.md).

`unconnected_penalty.fixed_rub: 100000000`, `per_tph_rub: 500000`.

### `ranking`

`weight_cost: 0.7`, `weight_length: 0.3`, `base_cost_rub: 25000000`, `base_length_m: 100`, `score_scale: 4`, `max_variants: 3`.

## `depth-rules.yaml`

| Ключ | Значение | Смысл |
|---|---:|---|
| `normal_depth_m` | 3,0 | Обычная глубина до верха габарита |
| `min_depth_m` | 0,7 | Нижняя отметка из текста раздела глубины |
| `step_m` | null | Непрерывный профиль |
| `max_slope_m_per_m` | 0,10 | Предельный уклон |
| `point_crossing_plateau_m` | 4 | Площадка у точечного пересечения |
| `cost.threshold_depth_m` | 3 | До этой отметки Kгл = 1 |
| `cost.rate_per_extra_m` | 0,10 | Прирост Kгл на каждый метр глубже порога |

Блок `existing_utilities` - габарит, глубина верха и вертикальный просвет газа, кабеля и теплосети. Блок `surface_crossings` - дорога 1,0 м и трамвай 1,2 м, поля `zone_margin_m: 3`.

`min_offset_m: 0.7` в этом файле загрузчик требует. Профиль глубины берёт просветы из `vertical_clearance_m`, не из этого поля.

## Свойства процесса

`backend/src/main/resources/application.yml`:

| Свойство | По умолчанию |
|---|---|
| `SERVER_PORT` | 8080 |
| `SPRING_PROFILES_ACTIVE` | `local` |
| `HEATNET_CONFIG_DIR` | `../config` |
| `HEATNET_DATA_DIR` | `../data` |
| `HEATNET_MAX_UPLOAD_SIZE` | 3GB |
| `HEATNET_INGEST_SNAP_TOLERANCE_M` | 1.0 |
| `HEATNET_INGEST_CHAMBER_SNAP_REPORT_TOLERANCE_M` | 0.5 |

Профиль `local` исключает автоконфигурацию JDBC. Профиль `docker` задаёт URL Postgres `jdbc:postgresql://postgres:5432/heatnet`, пользователь и пароль `heatnet`.

Командный режим включается наличием `heatnet.cli.input`. Рядом: `heatnet.cli.output`, `heatnet.cli.depth`, `heatnet.cli.split-variants`.

В контейнере эти умолчания перекрыты окружением compose:

| Переменная | Значение в `docker-compose.yml` |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` |
| `HEATNET_CONFIG_DIR` | `/app/config` (это `./config` с хоста, только чтение) |
| `HEATNET_DATA_DIR` | `/app/data` (это `./data` с хоста) |
| `JAVA_OPTS` | `-Xms512m -Xmx6g` |

Поэтому правка `config/depth-rules.yaml` на хосте видна процессу после пересоздания контейнера backend и влияет на следующий прогон с `enableDepth: true`. Образ пересобирать не нужно. Файлы результата этого прогона: `data/m11-sample.geojson` и `data/m11-sample_vA.geojson`, `_vB`, `_vC`. Как их снять с API на Linux и на Windows: [Файлы с глубиной](10-glubina.md).

Плоский прогон справочник глубин для геометрии не использует: Kгл остаётся 1, в файл пишется `null`. Оба прогона читают `rules.yaml`, `diameters.yaml` и `gabarits.yaml` одинаково.
