# Вход и выход

Оба файла - GeoJSON `FeatureCollection`, WGS 84, оси `[долгота, широта]`.

## Вход

Конкурсный файл репозитория: `dataset/dataset_updated.geojson`.

Минимальный каркас:

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "id": 1,
      "geometry": { "type": "Point", "coordinates": [37.64, 55.70] },
      "properties": {
        "id": 1,
        "object_type": "oks_connection_point",
        "flow_tph": 12.5
      }
    }
  ]
}
```

Идентификатор берётся из `id` Feature или из `properties.id`. Тип JSON сохраняется до выходного файла: число `116` и строка `"116"` - разные объекты.

### Обязательные свойства

| Тип | Обязательно | Может отсутствовать |
|---|---|---|
| `source` | `id`, `object_type`, Point | имя |
| `heat_network` | `id`, `object_type`, `diameter`, LineString | `flow_tph`, `upstream_object_id`, `laying_method` |
| `heat_chamber` | `id`, `object_type`, Point | `diameter` |
| `oks_connection_point` | `id`, `object_type`, `flow_tph` > 0, Point | `oks_id`, ссылка на полигон |
| `restriction` | `id`, `object_type`, `restriction_type`, геометрия | адрес и прочие поля |
| `oks_future` | геометрия, если объект принят | `flow_tph` (тогда только справочное хранение) |

`restriction_type`, которые понимает таблица правил: `oks`, `park`, `social_area`, `prohibited_site`, `water`, `railway`, `road`, `tram_tracks`, `gas_pipeline`, `power_cable`, `heat_network`. В `dataset/dataset_updated.geojson` встречаются `oks`, `water`, `railway`. Остальные типы таблица уже содержит: на проверочном файле они начнут работать без правки кода.

Неизвестный тип ограничения не останавливает загрузку. Объект с ошибкой схемы (нет расхода у точки, нет диаметра у трубы) исключается из модели и попадает в отчёт.

### Отчёт загрузки

`GET /api/files/{fileId}/report` возвращает JSON со списками сообщений (`severity`, код, id объекта, текст) и счётчиками принятых, предупреждений и ошибок. Расчёт можно запускать и при предупреждениях. Объекты с ошибкой схемы в дерево не входят.

## Выход

Один FeatureCollection на все варианты. У каждой фичи есть `variant_id`. Порядок - по рангу, внутри варианта участки, затем камеры и технические узлы, затем сводка.

Типы, которые сервис пишет:

| `object_type` | Геометрия | Смысл |
|---|---|---|
| `heat_network` | LineString | Новый участок |
| `heat_chamber` | Point | Новая камера |
| `technical_node` | Point | Граница спецпрохода, смена Ду или параметра, узел профиля глубины |
| `variant_summary` | `null` | Сводка варианта, ровно одна |

Типы `tie_in`, `heat_network_reconstruction`, `heat_chamber_reconstruction` не создаются.

Дополнительные свойства сверх обязательных проверка игнорирует. Сервис пишет их там, где они нужны для разбора: `k_spec`, `construction_cost` у участка, `diameter` и `cost` у новой камеры.

### Участок `heat_network`

| Поле | Смысл |
|---|---|
| `id` | Идентификатор участка с префиксом варианта (`vA_…`) |
| `variant_id` | `vA`, `vB` или `vC` |
| `start_node_id`, `end_node_id` | Узлы. Тип значения совпадает с типом входного id, если узел - входной объект |
| `flow_tph` | Расход участка, т/ч |
| `diameter` | Условный диаметр |
| `length` | Длина в метрах, по EPSG:32637 |
| `laying_method` | `base` - обычная прокладка, `special` - спецучасток |
| `depth_start`, `depth_end` | `null` в 2D; метры до верха габарита в режиме глубины |
| `cost`, `construction_cost` | Стоимость участка, ₽ |
| `k_spec` | Коэффициент спецпрохода этого куска |

Участок режется так, чтобы на одном объекте `heat_network` коэффициент был постоянным. Граница спецучастка - `technical_node`.

### Новая камера

`object_type = heat_chamber`, Point в месте врезки или развилки. Свойства: `id`, `variant_id`, `diameter` (наибольший Ду примыкающих участков), `cost` (3/5/8/12 млн, присоединение уже включено).

### Технический узел

Point без стоимости. Ставится на границе спецпрохода, при смене Ду и, в режиме глубины, там, где профиль перегибается между камерами.

### Сводка `variant_summary`

| Поле | Смысл |
|---|---|
| `id` | `summary_vA` / `summary_vB` / `summary_vC` |
| `variant_id` | `vA`, `vB` или `vC` |
| `rank` | 1 - лучший по S |
| `construction_cost` | Участки + новые камеры + врезки в существующие камеры, ₽ |
| `chamber_construction_cost` | Только новые камеры, ₽ |
| `existing_chamber_tie_in_count` | Число врезок в существующие камеры |
| `existing_chamber_tie_in_cost` | Эти врезки, ₽ (по 5 млн) |
| `unconnected_penalty` | Штраф, ₽ |
| `calculated_cost` | `construction_cost` + штраф. Реконструкция сюда не входит |
| `new_network_length` | L, метры новой сети |
| `score` | S, 4 знака |
| `unconnected_oks_ids` | Идентификаторы точек без маршрута |

Пример эталона приложения §7.3 даёт `score = 0.6913` на маленькой контрольной сети. На конкурсном наборе S другого порядка: сеть длиннее и дороже базы 25 млн ₽ и 100 м.

## Каталог файлов на диске

Их пишет клиент (`curl` или `scripts/export-depth.sh`), скачивая `GET /api/jobs/{id}/result`. Контейнер сам всегда оставляет полный ответ задачи в `data/jobs/<jobId>/result.geojson`.

| Файл | Прогон | Глубина в свойствах |
|---|---|---|
| `data/result.geojson` | `enableDepth: false`, все варианты | `null` |
| `data/result_vA.geojson`, `_vB`, `_vC` | тот же прогон, один `variant_id` | `null` |
| `data/m11-sample.geojson` | `enableDepth: true`, все варианты | числа, метры |
| `data/m11-sample_vA.geojson`, `_vB`, `_vC` | тот же прогон, один вариант | числа, метры |
| `data/jobs/<jobId>/result.geojson` | конкретная задача, как её создали | `null` или числа - как в теле запроса |

Команды: [Запуск](09-zapusk.md). Проверка чисел и 3D: [Файлы с глубиной](10-glubina.md).

## Как смотреть результат

В `data/result.geojson` и в `data/m11-sample.geojson` три трассы лежат в одних координатах. Для карты берите один вариант:

- `data/result_vA.geojson`
- `data/result_vB.geojson`
- `data/result_vC.geojson`

Подложка - `dataset/dataset_updated.geojson`. Проект QGIS в EPSG:4326 или EPSG:32637.

Фильтр слоя новой сети: `"object_type" = 'heat_network'`. Технические узлы стилизуйте отдельно от камер: и те и другие - точки, смысл разный.

`GET /api/jobs/{id}/result?variant=vA` отдаёт один вариант сразу, без локальной нарезки. Значение параметра совпадает с полем `variant_id`: `vA`, `vB`, `vC`.

Подложка карты (спутник, OSM) может не совпадать с полигонами датасета. Ориентир для проверки отступов - полигоны входного файла, не картинка подложки.

## Глубина в свойствах участка

Поля есть у каждого `heat_network` в обоих прогонах.

Плоский файл:

```json
"depth_start": null,
"depth_end": null
```

Файл `data/m11-sample_vA.geojson` (и `_vB`, `_vC`):

```json
"depth_start": 3.0,
"depth_end": 3.0
```

Около 3,0 м - обычная отметка до верха габарита. Меньше 3 - труба поднята над пересечением. Больше 3 - ушла ниже, в `cost` уже сидит Kгл > 1. Если числа на концах участка различаются, на нём уклон. Координата линии остаётся парой `[долгота, широта]`.

Как получить файлы через Docker на Linux. Флаг у обоих скриптов один и тот же, вызывается `docker-compose` 1.29.2:

- плоский прогон: `./scripts/export-flat.sh --docker` → `data/result.geojson` и `data/result_vA.geojson`, `_vB`, `_vC`;
- прогон с глубиной: `./scripts/export-depth.sh --docker` → `data/m11-sample.geojson` и `data/m11-sample_vA.geojson`, `_vB`, `_vC`.

На Windows: `.\scripts\export-flat.ps1 -Docker` и `.\scripts\export-depth.ps1 -Docker`. Оба вызывают `docker-compose` 1.29.2.

Раскраска в QGIS: градуированный знак по `depth_start`. Выражение для слоя с Z и порядок проверки - в [10-glubina.md](10-glubina.md). Сводка в файле с глубиной имеет свой `score`: он посчитан с Kгл и не повторяет плоский S.

## Объяснение объекта

`GET /api/jobs/{id}/explain/{objectId}` возвращает правило, по которому объект попал в выход: длина, Ду, расход, Kспец, Kгл, стоимость, текст правила. Идентификатор - тот, что записан в выходном GeoJSON (с префиксом варианта). Для сводки правило содержит формулу S.

Словарь живёт в памяти задачи до перезапуска процесса.
