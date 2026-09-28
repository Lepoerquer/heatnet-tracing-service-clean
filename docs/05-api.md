# HTTP API

Базовый URL локально: `http://localhost:8080`. Интерактивная схема: `/swagger-ui.html`. Машиночитаемая спецификация: `/api-docs`.

Тела ошибок - JSON (`ApiErrorResponse`): время, статус, сообщение, путь.

## Служебные

### `GET /actuator/health`

Состояние процесса. В профиле `docker` сюда входит проверка Postgres. В профиле `local` базы нет, health отражает сам сервис.

### `GET /api/info`

Версия (`heatnet.service-version`, сейчас `0.1.0-SNAPSHOT`), активные профили, каталог конфигурации и краткая сводка загруженных справочников.

## Загрузка

### `POST /api/files`

`multipart/form-data`, поле `file`. Лимит - 3 ГБ. Конкурсный файл для этого запроса: `dataset/dataset_updated.geojson`.

Ответ `200`. Основные поля: `fileId`, `originalFilename`, `totalFeatures`, `warningCount`, `errorCount`, `hasErrors`, `countsByType`.

```json
{
  "fileId": "019d1362-999b-46f5-8387-7006da167450",
  "originalFilename": "dataset_updated.geojson",
  "totalFeatures": 144,
  "warningCount": 0,
  "errorCount": 0,
  "hasErrors": false
}
```

Файл сохраняется в `data/uploads/{fileId}/`. Повторная загрузка того же содержимого создаёт новый `fileId`.

### `GET /api/files/{fileId}/report`

Отчёт схемы и топологии. `404`, если загрузка неизвестна.

## Расчёт

Один `fileId` можно посчитать дважды. Первый запрос с `"enableDepth": false` - плоский GeoJSON (`depth_start`/`depth_end` = `null`), его сохраняют как `data/result.geojson` и `data/result_vA.geojson` (и B, C). На Linux это делает `./scripts/export-flat.sh --docker`. Второй запрос с `"enableDepth": true` - тот же план плюс профиль глубины, файлы `data/m11-sample.geojson` и `data/m11-sample_vA.geojson` (и B, C). На Linux это делает `./scripts/export-depth.sh --docker`. Это разные `jobId`. Команды целиком: [Запуск](09-zapusk.md), только глубина: [Файлы с глубиной](10-glubina.md).

Полный ответ каждой задачи контейнер сам кладёт в `data/jobs/<jobId>/result.geojson` (каталог `./data` смонтирован в `/app/data`). Скачивание через `curl -o` нужно, чтобы разложить варианты по предсказуемым именам.

### `POST /api/jobs`

```json
{
  "fileId": "019d1362-999b-46f5-8387-7006da167450",
  "enableDepth": false
}
```

`enableDepth` по умолчанию `false`. Ответ `202 Accepted`:

```json
{
  "jobId": "0422afc1-3bd7-477f-a031-a003b2d52eba",
  "status": "QUEUED",
  "stage": "QUEUED",
  "progress": 0
}
```

`404` - нет такой загрузки. `503` с текстом «Очередь расчётов заполнена» - пул и очередь заняты (2 потока, очередь 100).

### `GET /api/jobs/{jobId}`

```json
{
  "jobId": "0422afc1-3bd7-477f-a031-a003b2d52eba",
  "status": "RUNNING",
  "stage": "PLAN",
  "progress": 25
}
```

`status`: `QUEUED`, `RUNNING`, `DONE`, `FAILED`.

`stage`: `QUEUED`, `INGEST`, `PLAN`, `COST`, `DEPTH`, `EXPORT`, `DONE`, `FAILED`.

`progress` - проценты от 0 до 100, оценка стадии, не доля построенных метров.

При `FAILED` поле `message` содержит текст исключения.

### `GET /api/jobs/{jobId}/result`

Файл `result.geojson`. Доступен при `status = DONE`. Пока задача не готова - `409` с текстом «результат ещё не готов». Неизвестный `jobId` - `404`.

Все варианты в одном файле.

### `GET /api/jobs/{jobId}/result?variant=vA`

Только вариант A. Допустимы `vA`, `vB`, `vC`. Удобно класть слой на карту, не фильтруя общий файл.

### `GET /api/jobs/{jobId}/explain/{objectId}`

JSON с правилом для участка, камеры, узла или сводки. `objectId` - значение `id` из выходного файла. Доступно при `DONE`.

## Пример сеанса

```powershell
$upload = curl.exe -s -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files | ConvertFrom-Json
$body = @{ fileId = $upload.fileId; enableDepth = $false } | ConvertTo-Json
$job = curl.exe -s -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -d $body | ConvertFrom-Json

do {
  Start-Sleep -Seconds 5
  $st = curl.exe -s "http://localhost:8080/api/jobs/$($job.jobId)" | ConvertFrom-Json
  "$($st.status) $($st.stage) $($st.progress)"
} while ($st.status -eq "QUEUED" -or $st.status -eq "RUNNING")

curl.exe -s -o data/result.geojson "http://localhost:8080/api/jobs/$($job.jobId)/result"
curl.exe -s -o data/result_vA.geojson "http://localhost:8080/api/jobs/$($job.jobId)/result?variant=vA"
```

На конкурсном наборе стадия `PLAN` занимает около минуты на двух ядрах (снимок 26.09.2026 - около 58 с вместе с уточняющим проходом).

Тот же результат без HTTP - командный режим, см. [Запуск](09-zapusk.md).

## Командный режим и API

Оба пути вызывают `CalculationPipeline`. Расхождение возможно только во флаге глубины и в нарезке по вариантам: API всегда пишет один `result.geojson` внутри каталога задачи, query `variant` нарезает его при отдаче. CLI с `--heatnet.cli.split-variants=true` сразу кладёт рядом файлы вариантов.

## Что происходит в коде при типичном сеансе

| Шаг HTTP | Класс | Файл на диске |
|---|---|---|
| Загрузка | `UploadIngestService` сохраняет байты, `IngestService` строит отчёт | `data/uploads/{fileId}/` |
| Создание job | `JobService.submit` резервирует UUID, ставит задачу в пул | - |
| Фоновый расчёт | `CalculationPipeline.run(Path, ...)` | `data/jobs/{jobId}/result.geojson` |
| Опрос статуса | Чтение полей `JobRecord` без повторного ingest | - |
| Скачивание | `Files.copy` или `VariantSplitter` + временный файл | query `variant` не меняет основной result |
| Explain | Поиск в `job.getExplanations()` по id участка | - |

Повторный `POST /api/jobs` с тем же `fileId` и другим `enableDepth` создаёт **новый** `jobId` и новый каталог. Плоский и глубинный прогоны не перезаписывают друг друга, если скрипты сохраняют выгрузку в `data/result*.geojson` и `data/m11-sample*.geojson`.

Ошибки ingest на этапе загрузки попадают в ответ `POST /api/files` (`errorCount`, `hasErrors`). Job всё равно можно создать, если файл сохранён; критические ошибки схемы могут оставить ноль точек подключения - тогда `VariantGenerator` вернёт пустой набор и конвейер соберёт вырожденный вариант со штрафами (`CalculationPipeline.degenerateVariant`).

Подробнее о стадиях `PLAN`/`COST`/`EXPORT` - [Архитектура](02-arhitektura.md), о классах - [Код](11-kod.md).
