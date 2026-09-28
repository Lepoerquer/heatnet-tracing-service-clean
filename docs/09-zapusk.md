# Запуск

Рабочий каталог всех команд - корень репозитория `heatnet-tracing-service-clean`, там лежат `docker-compose.yml`, `config/`, `data/`, `dataset/dataset_updated.geojson`.

Основной путь - **Linux и Docker**. Дополнительный - **Windows и Docker Desktop**. Оба поднимают одни и те же сервисы `postgres` и `backend` из `docker-compose.yml`. JAR на хосте для них не собирается: сборка идёт внутри Dockerfile.

Связанные разделы: [файлы с глубиной](10-glubina.md), [API](05-api.md), [конфигурация](08-konfiguraciya.md).

## Что поднимает compose

| Сервис | Образ | Порт на хосте | Память |
|---|---|---|---|
| `postgres` | `postgis/postgis:16-3.4` | `127.0.0.1:5432` | до 4 ГБ |
| `backend` | сборка `backend/Dockerfile` | `8080` | до 10 ГБ |

Backend ждёт, пока Postgres станет healthy (`pg_isready` по TCP, `start_period` 120 с: расширение PostGIS инициализируется не сразу). Профиль Spring - `docker`. Куча: `JAVA_OPTS=-Xms512m -Xmx6g`.

Тома:

- именованный `postgres-data` - данные СУБД, переживают `docker-compose down`;
- `./config:/app/config:ro` - справочники с хоста;
- `./data:/app/data` - загрузки и каталоги задач. Файл результата задачи всегда появляется на хосте как `data/jobs/<jobId>/result.geojson`.

Учётная запись базы, зашитая в compose для локального стенда: база `heatnet`, пользователь `heatnet`, пароль `heatnet`.

Образ backend двухступенчатый. Стадия `build` - `maven:3.8-eclipse-temurin-11`, `mvn -B -DskipTests package`. Стадия запуска - `eclipse-temurin:11-jre-focal` плюс `curl` для healthcheck. Исходники копируются в образ на `docker-compose build`. Смена только YAML в `config/` образ не пересобирает.

---

## Linux

### Подготовка

Ubuntu 22 (целевая ОС из ТЗ). На стенде для организаторов нужна связка, с которой `docker-compose` 1.29.2 создаёт контейнеры:

- Docker Engine **не выше 24.0.9** (ориентир для проверки - **24.0.9**; более ранняя 24.0.x допустима, если `docker-compose up` проходит без `ContainerConfig`);
- бинарник `docker-compose` **1.29.2** (V1, команда через дефис).

**Compose V1 (`docker-compose` 1.29.2) не поддерживает Docker Engine 25 и новее** (`KeyError: 'ContainerConfig'` на Postgres). Бинарник 1.29.2 с движком 25+ не запустит `docker-compose.yml`.

**По ТЗ (п. 3.2) стенд - Engine не выше 24.0.9 и V1 1.29.2.** При `docker --version` 25+ понизьте Engine. Скрипты `export-flat.sh` / `export-depth.sh` с флагом `--docker` и `deploy.sh` вызывают `docker-compose` (V1).

```bash
docker --version
docker-compose --version
curl --version
```

Ожидание: `Docker version 24.0.9` (или ниже, не 25+) и `docker-compose version 1.29.2, build 5becea4c`.

Дальше команды выполняются из корня проекта. Конкурсный датасет: `dataset/dataset_updated.geojson`. Рядом лежат `docker-compose.yml`, `config/` и `scripts/`.

```bash
cd heatnet-tracing-service-clean
chmod +x deploy.sh scripts/export-flat.sh scripts/export-depth.sh
sed -i 's/\r$//' deploy.sh scripts/export-flat.sh scripts/export-depth.sh
```

`chmod` нужен, чтобы `./deploy.sh`, `./scripts/export-flat.sh` и `./scripts/export-depth.sh` запускались как программы. Без него оболочка отвечает `Permission denied`.

`sed` нужен тем же трём файлам, если они приехали с переводами строк Windows (`bash\r`). Shebang читается как `bash\r`, и скрипт не стартует. В git для `*.sh` зафиксирован LF (`.gitattributes`). Копия с диска Windows или из архива может прийти с `\r`.

Пользователь в группе `docker` либо команды с правами, которыми на этой машине разрешён демон. Порты 8080 и 5432 на localhost свободны:

```bash
ss -ltnp | grep -E ':8080|:5432' || true
```

Память: лимиты compose 10 ГБ + 4 ГБ. На хосте с 8 ГБ демон может убить контейнер по OOM в середине `PLAN`. Для конкурсного набора ориентир - 16 ГБ, как в ТЗ.

### Старт

```bash
docker-compose up --build -d
```

То же одной командой: `./deploy.sh`. Скрипт вызывает `docker-compose up -d --build`, до 3 минут ждёт `"status":"UP"` и печатает `GET /api/info`. Конкурсный файл он не считает. Если за 3 минуты health не пришёл, печатает хвост логов backend. Первая сборка Maven часто длиннее: тогда дождитесь окончания `docker-compose up --build -d` и запустите `./deploy.sh` ещё раз.

Первый запуск качает `postgis/postgis:16-3.4`, `maven:3.8-eclipse-temurin-11`, `eclipse-temurin:11-jre-focal` и зависимости из `pom.xml`. Без сети сборка остановится на `RUN mvn`.

Проверка:

```bash
docker-compose ps
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/api/info
```

`docker-compose ps` показывает `healthy` у обоих сервисов. Пока Postgres в `health: starting`, backend ещё не стартовал: это нормальные первые две минуты.

Логи:

```bash
docker-compose logs -f --tail 100 backend
docker-compose logs --tail 50 postgres
```

В логе backend после загрузки справочников слушатель на 8080. Во время задачи видны стратегии `A`, `B` и `C` (в GeoJSON к ним добавляется префикс `v`) и финальная строка с S.

Остановка, база на диске Docker сохраняется:

```bash
docker-compose down
```

Остановка и удаление тома Postgres:

```bash
docker-compose down -v
```

Пересборка после изменения Java:

```bash
docker-compose up --build -d
```

Перечитать YAML без пересборки JAR:

```bash
docker-compose up -d --force-recreate backend
```

### Флаг `--docker` (и `-Docker` на Windows)

Скрипты `export-flat` и `export-depth` всегда берут вход `dataset/dataset_updated.geojson`.

| Режим | Linux | Windows |
|---|---|---|
| **С Docker** | `./scripts/export-flat.sh --docker` | `.\scripts\export-flat.ps1 -Docker` |
| **Без Docker** | `./scripts/export-flat.sh` | `.\scripts\export-flat.ps1` |

**С `--docker` / `-Docker`:** `docker-compose up -d` (если стек ещё не поднят), ожидание health, `POST /api/files`, `POST /api/jobs`, опрос до `DONE`, сохранение в `data/`. JDK и Maven на хосте не нужны - расчёт в контейнере. Это путь проверки по ТЗ.

**Без флага:** Docker не используется. Скрипт при необходимости собирает `backend/target/*.jar` через Maven, затем `java -jar` с `heatnet.cli.*` и профилем `local` - тот же `CalculationPipeline`, что в API, но без HTTP. Нужны JDK 11 и Maven на машине.

`deploy.sh` / `deploy.ps1` только health-check стека, расчёт не запускают.

### Плоский GeoJSON

```bash
./scripts/export-flat.sh --docker
```

Скрипт делает `docker-compose up -d` (образ заново не собирает, если он уже есть), ждёт health до 8 минут, шлёт `"enableDepth": false`, ждёт `DONE` до 15 минут и пишет:

- `data/result.geojson`;
- `data/result_vA.geojson`;
- `data/result_vB.geojson`;
- `data/result_vC.geojson`.

В консоли строка `Режим: Docker, без глубины`, затем стадии `INGEST` и `PLAN`. Слова «без глубины» про содержимое файла, не про обход Docker. Расчёт на конкурсном наборе занимает порядка одной минуты плюс время загрузки.

Ручной цикл, если стек уже `UP` и скрипт не нужен:

```bash
mkdir -p data
UPLOAD=$(curl -fsS -F "file=@dataset/dataset_updated.geojson" \
  http://localhost:8080/api/files)
echo "$UPLOAD"
FILE_ID=$(printf '%s' "$UPLOAD" | sed -n 's/.*"fileId":"\([^"]*\)".*/\1/p')
test -n "$FILE_ID"

JOB=$(curl -fsS -H "Content-Type: application/json" \
  -d "{\"fileId\":\"$FILE_ID\",\"enableDepth\":false}" \
  http://localhost:8080/api/jobs)
echo "$JOB"
JOB_ID=$(printf '%s' "$JOB" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')
test -n "$JOB_ID"

for i in $(seq 1 180); do
  ST=$(curl -fsS "http://localhost:8080/api/jobs/$JOB_ID")
  echo "$ST"
  printf '%s' "$ST" | grep -q '"status":"FAILED"' && exit 1
  printf '%s' "$ST" | grep -q '"status":"DONE"' && break
  sleep 5
done

curl -fsS -o data/result.geojson "http://localhost:8080/api/jobs/$JOB_ID/result"
for v in vA vB vC; do
  curl -fsS -o "data/result_${v}.geojson" \
    "http://localhost:8080/api/jobs/$JOB_ID/result?variant=${v}"
  echo "data/result_${v}.geojson"
done
ls -lh data/result.geojson data/result_vA.geojson data/result_vB.geojson data/result_vC.geojson
```

Копия полного ответа ещё и здесь: `data/jobs/$JOB_ID/result.geojson`. Её пишет сам процесс внутри контейнера. Скачивание через `curl -o` нужно, чтобы получить предсказуемые имена `result_vA.geojson` и т.д. Схему входного файла проверяет сам сервис при `POST /api/files`. В плоском файле `depth_start` и `depth_end` равны `null`.

### Файлы с глубиной

Отдельная задача. Тело запроса отличается одним полем: `"enableDepth": true`. Имена файлов другие, чтобы не смешать с плоским результатом.

Скрипт `scripts/export-depth.sh --docker` делает то же по шагам, что плоский, но шлёт `"enableDepth": true` и пишет `data/m11-sample*.geojson`. Compose - `docker-compose` 1.29.2 (V1).

```bash
./scripts/export-depth.sh --docker
```

Ручной вариант и проверка чисел - в [10-glubina.md](10-glubina.md). Кратко, стек уже поднят:

```bash
UPLOAD=$(curl -fsS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files)
FILE_ID=$(printf '%s' "$UPLOAD" | sed -n 's/.*"fileId":"\([^"]*\)".*/\1/p')
JOB=$(curl -fsS -H "Content-Type: application/json" \
  -d "{\"fileId\":\"$FILE_ID\",\"enableDepth\":true}" \
  http://localhost:8080/api/jobs)
JOB_ID=$(printf '%s' "$JOB" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')

for i in $(seq 1 300); do
  ST=$(curl -fsS "http://localhost:8080/api/jobs/$JOB_ID")
  echo "$ST"
  printf '%s' "$ST" | grep -q '"status":"FAILED"' && exit 1
  printf '%s' "$ST" | grep -q '"status":"DONE"' && break
  sleep 3
done

mkdir -p data
curl -fsS -o data/m11-sample.geojson "http://localhost:8080/api/jobs/$JOB_ID/result"
for v in vA vB vC; do
  curl -fsS -o "data/m11-sample_${v}.geojson" \
    "http://localhost:8080/api/jobs/$JOB_ID/result?variant=${v}"
done
ls -lh data/m11-sample.geojson data/m11-sample_vA.geojson data/m11-sample_vB.geojson data/m11-sample_vC.geojson
```

В статусе должна мелькнуть стадия `DEPTH`. Если её не было и файл всё же `DONE`, проверьте тело запроса: ушло ли `"enableDepth": true`. При `false` сервис честно пишет `null` в поля глубины.

### Если стек не поднялся

| Симптом | Куда смотреть |
|---|---|
| `KeyError: 'ContainerConfig'` | Docker Engine 25+: V1 1.29.2 не поддерживается. Понизить Engine до **24.0.9 или ниже**, compose оставить **1.29.2** |
| `docker-compose` не найден | Поставить бинарник 1.29.2 в `/usr/local/bin/docker-compose` |
| postgres долго `starting` | `docker-compose logs postgres`. Первый init PostGIS до 2 минут. Повторный старт быстрее |
| backend `Exit` сразу | `docker-compose logs backend`. Частая причина - Postgres ещё не healthy, тогда `depends_on` не пустит backend; если пустил и упал - строка исключения в логе |
| health `Connection refused` дольше 3-8 минут | Порт 8080 занят другим процессом. `ss -ltnp \| grep 8080` |
| `no space left` на сборке | `docker system df`, образы Maven тяжёлые |
| OOM в середине расчёта | `dmesg` или `docker inspect` по OOMKilled. Поднять память Docker или хоста. Задача в API станет `FAILED`, в `message` будет текст ошибки |
| `403` / нет прав к сокету | Группа `docker` и новый логин |
| файл результата пустой | Задача не `DONE`. Смотреть `GET /api/jobs/{id}`: `FAILED` и `message` |
| `depth_start: null` в m11-sample | Задача создана с `enableDepth: false` либо скачан не тот job |

Повтор после правки кода: `docker-compose up --build -d`. Старые контейнеры compose заменяет сам.

---

## Windows (Docker Desktop)

Дополнение к пути Linux. Образ и API те же. Нужен запущенный Docker Desktop и `docker-compose` 1.29.2 (V1) в `PATH`. Команды - из корня репозитория.

Docker Desktop с Engine **25+** несовместим с **V1 1.29.2**. Для ТЗ на Windows - Engine ≤24.0.9 и бинарник `docker-compose` 1.29.2 в `PATH`, либо Ubuntu-ВМ из раздела Linux выше.

### Старт и остановка

```powershell
.\deploy.ps1
docker-compose ps
docker-compose logs -f --tail 100 backend
docker-compose down
```

`deploy.ps1` делает то же, что `deploy.sh`: `docker-compose up -d --build`, ожидание health до 3 минут, печать `/api/info`. Расчёт не запускает.

Проверка из браузера на этой же машине: <http://localhost:8080/actuator/health>, <http://localhost:8080/swagger-ui.html>, <http://localhost:8080/api/info>.

Плоский файл и файл с глубиной:

```powershell
.\scripts\export-flat.ps1 -Docker
.\scripts\export-depth.ps1 -Docker
```

### Плоский результат вручную

Если стек уже поднят и скрипт не нужен. PowerShell открыт в корне репозитория.

```powershell
$upload = curl.exe -sS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files | ConvertFrom-Json
$body = @{ fileId = $upload.fileId; enableDepth = $false } | ConvertTo-Json -Compress
$job = curl.exe -sS -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -d $body | ConvertFrom-Json
do {
  Start-Sleep -Seconds 5
  $st = curl.exe -sS "http://localhost:8080/api/jobs/$($job.jobId)" | ConvertFrom-Json
  Write-Host "$($st.status) $($st.stage) $($st.progress)"
} while ($st.status -eq "QUEUED" -or $st.status -eq "RUNNING")
if ($st.status -ne "DONE") { throw "Расчёт: $($st.status) $($st.message)" }
New-Item -ItemType Directory -Force -Path data | Out-Null
curl.exe -sS -o data/result.geojson "http://localhost:8080/api/jobs/$($job.jobId)/result"
foreach ($v in @("vA", "vB", "vC")) {
  curl.exe -sS -o "data/result_$v.geojson" "http://localhost:8080/api/jobs/$($job.jobId)/result?variant=$v"
}
Get-ChildItem data\result*.geojson | Select-Object Name, Length
```

`ConvertTo-Json` без `-Compress` в Windows PowerShell 5 вставляет переводы строк. Для этого тела это допустимо: сервер читает JSON целиком. Если клиент ругается, оставьте `-Compress`.

Поле `enableDepth` в классе запроса - `boolean`. Jackson принимает `false` / `true`. Не передавайте строку `"false"`.

### Файлы с глубиной

```powershell
.\scripts\export-depth.ps1 -Docker
```

Ручной цикл - [10-glubina.md](10-glubina.md).

### Если на Windows контейнер не видит файлы

- Docker Desktop не запущен.
- Репозиторий на диске, который не расшарен в File sharing.
- Команда запущена не из корня: `docker-compose` ищет `docker-compose.yml` в текущем каталоге.
- Антивирус блокирует `dockerDesktop.exe` или проброс порта 8080.
- WSL2 не установлен: Desktop на этой ОС использует его как движок. Установщик Desktop обычно предлагает поставить сам.

---

## Что лежит в `data/` после обоих прогонов

```
data/
├── result.geojson              все варианты, глубина null
├── result_vA.geojson
├── result_vB.geojson
├── result_vC.geojson
├── m11-sample.geojson          все варианты, depth_start/depth_end - числа
├── m11-sample_vA.geojson
├── m11-sample_vB.geojson
├── m11-sample_vC.geojson
├── uploads/<fileId>/           исходник, как его принял API
└── jobs/<jobId>/result.geojson полный ответ конкретной задачи
```

Два прогона - два `jobId`. Плоский и глубинный результаты в `jobs/` лежат в разных каталогах. Имена `result_*` и `m11-sample_*` появляются только после `curl -o` или после скрипта выгрузки. Сам контейнер их так не называет: он пишет `jobs/<uuid>/result.geojson`.

Для QGIS берите `result_vA.geojson` или `m11-sample_vA.geojson`, подложка - `dataset/dataset_updated.geojson`.

## Проверка набора

Конкурсный вход: 144 объекта. Ответ загрузки содержит `totalFeatures`, `errorCount`, `warningCount`. `errorCount` больше нуля значит, что часть объектов не вошла в модель; на штатном `dataset_updated.geojson` ошибок схемы нет.

Сводка лучшего варианта на плоском снимке 26.09.2026: `variant_id` = `vA`, `score` = 13.1055, 17 точек подключены, `unconnected_oks_ids` пуст.

Юнит-тесты к запуску контейнера не относятся. Их гоняют на JDK 11 отдельно: `mvn -f backend/pom.xml test` (273 теста на снимке 26.09.2026) и `mvn -f backend/pom.xml test -Pcontest-full`. Профиль `contest-full` перезаписывает `data/result.geojson`. Скачанную выгрузку перед ним лучше скопировать в сторону.
