# Файлы с глубиной

Профиль глубины - отдельный прогон того же сервиса. Горизонтальная трасса строится как в плоском режиме, затем `DepthTracer` (пакет `ru.heatnet.depth`) назначает каждому новому участку глубину до **верха габарита** пары труб и пересчитывает рубли с коэффициентом Kгл.

В геометрию Z не пишется. Глубина лежит в свойствах `depth_start` и `depth_end` у объектов `heat_network`. Единицы - метры. Обычное значение на участке без пересечений - **3,0**.

Плоские файлы `data/result*.geojson` этот прогон не заменяет и не удаляет. У них те же поля есть, но равны `null`.

Входной файл прогона: `dataset/dataset_updated.geojson`. Стек должен быть уже описан в [Запуск](09-zapusk.md): Linux - основной, Windows Docker - дополнительный.

## Какие файлы появляются

| Файл | Содержимое |
|---|---|
| `data/m11-sample.geojson` | Все варианты `vA`, `vB`, `vC` с заполненной глубиной |
| `data/m11-sample_vA.geojson` | Только `vA`. Этот файл открывать в QGIS |
| `data/m11-sample_vB.geojson` | Только `vB` |
| `data/m11-sample_vC.geojson` | Только `vC` |
| `data/jobs/<jobId>/result.geojson` | Тот же полный ответ, который записал контейнер. Имя каталога - UUID задачи |

В общем `m11-sample.geojson` три сети наложены. Для карты и для идентификации участка берите файл одного варианта.

Дополнительные точки `technical_node` могут появиться там, где профиль перегибается между камерами (начало спуска, конец площадки). Их нет в плоском файле, если перегиб не совпал с уже существующим узлом.

## Linux

Из корня репозитория, Docker уже установлен:

```bash
cd heatnet-tracing-service-clean
chmod +x deploy.sh scripts/export-flat.sh scripts/export-depth.sh
sed -i 's/\r$//' deploy.sh scripts/export-flat.sh scripts/export-depth.sh
./scripts/export-depth.sh --docker
```

`chmod` и `sed` — для трёх скриптов: `deploy.sh`, `scripts/export-flat.sh`, `scripts/export-depth.sh`. `chmod` даёт право запуска через `./` (иначе `Permission denied`). `sed` снимает `\r`, если файл приехал с переводом строки Windows: иначе shebang читается как `bash\r` и скрипт не стартует. В git для `*.sh` зафиксирован LF (`.gitattributes`).

Плоский файл той же машиной и тем же флагом снимает другой скрипт: `./scripts/export-flat.sh --docker`. Он пишет `data/result*.geojson` и в запросе передаёт `"enableDepth": false`. Только поднять стек, без расчёта: `./deploy.sh`.

Скрипт `scripts/export-depth.sh`:

1. Вызывает `docker-compose up --build -d` (бинарник 1.29.2, V1).
2. До 8 минут ждёт `"status":"UP"` на `http://localhost:8080/actuator/health`. Если не дождался - печатает `docker-compose logs backend --tail 80` и завершается с кодом 1.
3. Загружает `dataset/dataset_updated.geojson` через `POST /api/files`.
4. Создаёт задачу `POST /api/jobs` с телом `{"fileId":"…","enableDepth":true}`.
5. Раз в 3 секунды читает статус, до 15 минут. В консоли строки вида `RUNNING DEPTH 75%`.
6. Скачивает результат в `data/m11-sample.geojson` и три файла вариантов.

Конец успешного прогона:

```text
Сохранён data/m11-sample_vA.geojson
Сохранён data/m11-sample_vB.geojson
Сохранён data/m11-sample_vC.geojson
Готово: data/m11-sample.geojson и m11-sample_vA.geojson, _vB, _vC
```

### Вручную, стек уже работает

Нужно, когда стек поднят заранее и скрипт заново вызывать не хочется. Отличие от плоского прогона - только `enableDepth` и имена файлов.

```bash
cd heatnet-tracing-service-clean
mkdir -p data

UPLOAD=$(curl -fsS -F "file=@dataset/dataset_updated.geojson" \
  http://localhost:8080/api/files)
FILE_ID=$(printf '%s' "$UPLOAD" | sed -n 's/.*"fileId":"\([^"]*\)".*/\1/p')
echo "fileId=$FILE_ID"

JOB=$(curl -fsS -H "Content-Type: application/json" \
  -d "{\"fileId\":\"$FILE_ID\",\"enableDepth\":true}" \
  http://localhost:8080/api/jobs)
JOB_ID=$(printf '%s' "$JOB" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')
echo "jobId=$JOB_ID"

while true; do
  ST=$(curl -fsS "http://localhost:8080/api/jobs/$JOB_ID")
  echo "$ST"
  printf '%s' "$ST" | grep -q '"status":"FAILED"' && { echo "$ST"; exit 1; }
  printf '%s' "$ST" | grep -q '"status":"DONE"' && break
  sleep 5
done

curl -fsS -o data/m11-sample.geojson \
  "http://localhost:8080/api/jobs/$JOB_ID/result"
for v in vA vB vC; do
  curl -fsS -o "data/m11-sample_${v}.geojson" \
    "http://localhost:8080/api/jobs/$JOB_ID/result?variant=${v}"
done

# копия, которую контейнер уже положил на хост через том ./data
ls -lh "data/jobs/$JOB_ID/result.geojson" data/m11-sample*.geojson
```

Параметр `variant` равен полю `variant_id` в файле: `vA`, `vB`, `vC`. Другое значение даст GeoJSON с пустым массивом `features`.

Лог сервиса на время стадии `DEPTH`:

```bash
docker-compose logs -f backend
```

## Windows

Из корня репозитория, `docker-compose` 1.29.2:

```powershell
.\scripts\export-depth.ps1 -Docker
```

Тот же порядок, что у bash-скрипта: compose, health до 8 минут, загрузка `dataset/dataset_updated.geojson`, задача с `enableDepth: true`, ожидание до 15 минут, запись:

- `data\m11-sample.geojson`
- `data\m11-sample_vA.geojson`
- `data\m11-sample_vB.geojson`
- `data\m11-sample_vC.geojson`

В консоли: `Расчёт <uuid> (с глубиной)`, затем `RUNNING PLAN …`, `RUNNING DEPTH …`, `DONE 100%`, затем `Сохранён …` и строка `Готово`.

Ручной цикл, если контейнеры уже здоровы:

```powershell
$upload = curl.exe -sS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files | ConvertFrom-Json
if (-not $upload.fileId) { throw "Нет fileId" }
$body = @{ fileId = $upload.fileId; enableDepth = $true } | ConvertTo-Json -Compress
$job = curl.exe -sS -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -d $body | ConvertFrom-Json
if (-not $job.jobId) { throw "Нет jobId" }
Write-Host "jobId $($job.jobId)"
do {
  Start-Sleep -Seconds 5
  $st = curl.exe -sS "http://localhost:8080/api/jobs/$($job.jobId)" | ConvertFrom-Json
  Write-Host "$($st.status) $($st.stage) $($st.progress)%"
  if ($st.status -eq "FAILED") { throw $st.message }
} while ($st.status -ne "DONE")
New-Item -ItemType Directory -Force -Path data | Out-Null
curl.exe -sS -o data/m11-sample.geojson "http://localhost:8080/api/jobs/$($job.jobId)/result"
foreach ($v in @("vA", "vB", "vC")) {
  curl.exe -sS -o "data/m11-sample_$v.geojson" "http://localhost:8080/api/jobs/$($job.jobId)/result?variant=$v"
  Write-Host "data/m11-sample_$v.geojson"
}
Get-ChildItem data\m11-sample*.geojson | Format-Table Name, Length
```

Ключ скрипта именно `-Docker`. Без него скрипт ищет JDK и Maven на Windows и считает вне контейнера. Для стенда этой документации нужен контейнер.

## Как понять, что глубина в файле есть

Фрагмент участка в `m11-sample_vA.geojson`:

```json
"properties": {
  "object_type": "heat_network",
  "variant_id": "vA",
  "depth_start": 3.0,
  "depth_end": 3.0
}
```

Геометрия при этом:

```json
"coordinates": [ [37.64, 55.70], [37.641, 55.701] ]
```

Два числа в точке, не три. Глубина в свойствах, не в координате. Если в `m11-sample` у участка `depth_start` равен `null`, открыт плоский прогон или задача ушла с `enableDepth: false`.

Объяснение конкретного участка, пока процесс контейнера не перезапускали:

```bash
# objectId - поле id участка из m11-sample_vA.geojson, с префиксом vA_
curl -fsS "http://localhost:8080/api/jobs/$JOB_ID/explain/vA_<id-участка>"
```

В ответе есть `depthStartM`, `depthEndM`, `kDepth`, `costRub` и текст правила. После `docker-compose down` словарь объяснений пропадает вместе с памятью процесса, файлы на диске остаются.

## Что означают числа

Глубина меряется от условной поверхности вниз, до верха габарита новой пары труб.

| Значение | Смысл |
|---|---|
| около 3,0 и `depth_start` ≈ `depth_end` | Обычная прокладка, Kгл = 1 |
| меньше 3 | Участок поднялся, чтобы пройти над точечным пересечением с заданным просветом |
| больше 3 | Участок ушёл ниже обычной отметки, Kгл > 1 |
| `depth_start` и `depth_end` различаются | Спуск или подъём. Уклон не круче 0,10 (1 м глубины на 10 м по горизонтали) |

Kгл на участке глубже 3 м: `1 + 0,10 · (h - 3)`. На наклонном куске берётся среднее коэффициентов концов. Из-за этого `cost` в файле с глубиной может отличаться от `cost` того же участка в `result_vA.geojson`, а `score` в `variant_summary` - от плоского S.

Правила профиля (справочник `config/depth-rules.yaml`):

- точечные газ, кабель и существующая теплосеть - площадка постоянной глубины 4 м (по 2 м от точки пересечения) и вертикальный просвет 0,2 м (газ) или 0,5 м (кабель и теплосеть);
- дорога - верх габарита не выше чем на 1,0 м под поверхностью в полосе «полигон + 3 м»; трамвай - 1,2 м;
- если два точечных пересечения стоят так близко, что спуск, площадка и подъём не помещаются, между ними остаётся один глубокий коридор.

На конкурсном наборе «ЗИЛ» нет объектов `road` и `tram_tracks`. Профиль там в основном держит 3,0 м и реагирует на пересечения существующей теплосети. Дорога и трамвай проверяются конфигурацией и синтетикой; на другом входном файле той же схемы они включаются сами.

Горизонтальный обход здания профиль не спрямляет. Сначала выполняется план 2D, затем по уже выбранной полилинии считается вертикаль.

## QGIS

1. Проект в EPSG:4326.
2. Подложка: `dataset/dataset_updated.geojson`.
3. Добавить `data/m11-sample_vA.geojson` (на Windows тот же путь с обратными слэшами).
4. Фильтр слоя: `"object_type" = 'heat_network'`.
5. Идентификация объекта: поля `depth_start`, `depth_end`, `diameter`, `k_spec`, `cost`.
6. Раскраска: Символика → Градуированный знак → поле `depth_start`. Участки около 3,0 соберутся в один класс, отклонения будут видны отдельно.
7. Технические узлы - отдельный стиль, фильтр `"object_type" = 'technical_node'`. Камеры - `"object_type" = 'heat_chamber'`.

Объём. Калькулятор полей, тип геометрии LineStringZ. Глубина вниз, поэтому Z со знаком минус. Выражение:

```
make_line(
  array_foreach(
    generate_series(1, num_points($geometry)),
    with_variable(
      't',
      if(length($geometry) = 0, 0,
         line_locate_point($geometry, point_n($geometry, @element)) / length($geometry)),
      make_point(
        x(point_n($geometry, @element)),
        y(point_n($geometry, @element)),
        -("depth_start" + @t * ("depth_end" - "depth_start"))
      )
    )
  )
)
```

У нового слоя: Свойства → Высота → привязка «Абсолютная», данные - Z. Вид → Новая 3D-карта. Вертикальное преувеличение 20-40: перепад глубины - метры, длина трассы - сотни метров. В проекте EPSG:4326 вертикаль в градусах выглядит плоско без преувеличения; для обмера удобнее перевести слой в EPSG:32637 до построения Z.

## Сводка варианта

В `m11-sample_vA.geojson` найдите объект `"object_type": "variant_summary"`. Поля те же, что в плоском файле: `score`, `calculated_cost`, `new_network_length`, `rank`. `score` уже учитывает Kгл. Сравнивать его с 12,8418 из плоского снимка vA напрямую нельзя: это две сметы.

`rank` 1 - меньший S среди вариантов **этого** прогона.

## Частые промахи

| Что видно | Причина |
|---|---|
| Файлов `m11-sample*` нет | Скрипт не отработал до строки `Готово`, либо `curl -o` не запускали. Смотрите `data/jobs/*/result.geojson` - полный ответ задачи там появляется и без переименования |
| `depth_start` = `null` | Скачан плоский прогон или в JSON ушло `enableDepth: false` |
| Пустой `m11-sample_vA.geojson` (`features: []`) | В query передали `A` вместо `vA` |
| Стадия зависла на `PLAN` | Это поиск трассы, на конкурсном наборе около минуты, на более крупном файле дольше. Бюджет дополнительных стратегий — 900 с, уточнения Ду — 240 с. Общий предел скрипта - 15 минут, затем он завершается с ошибкой, задача в контейнере может ещё идти |
| `FAILED` и текст про память | Контейнеру не хватило лимита 10 ГБ или хост урезал Docker Desktop. В Desktop → Resources увеличьте RAM |
| В 3D-виде линия на нуле | Калькулятор Z не применяли: в исходном GeoJSON третьей координаты нет специально |
