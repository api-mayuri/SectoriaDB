# bench/: стенд наблюдаемости, бенчмарки, совместимость, отказы

Всё, что связано с измерением SectoriaDB, лежит здесь. Подробное описание методики, схемы стенда и результатов:
[docs/architecture/08-observability-bench.md](../docs/architecture/08-observability-bench.md), раздел «Стенд и бенчмарки».

```
bench/
  observability/   docker-compose: Prometheus, Grafana, InfluxDB v2, node_exporter (+ SectoriaDB под тестом)
  warp/            run.sh, matrix.sh, compare.sh, preload.sh: нагрузка minio/warp и сохранение результатов;
                   validate_metrics.py (сверка с warp), window_metrics.py (p50/p99 серверных метрик за окно прогона)
  compat/          ceph/s3-tests и minio/mint: проверка совместимости с S3
  faults/          kill -9, полный диск, порча чанка, сетевые сбои, проверки безопасности
  results/         результаты прогонов (локально, не в git), см. results/README.md
```

Хранилищ ровно три: **Prometheus** (метрики сервера), **InfluxDB v2** (живые клиентские результаты warp) и **Grafana**
(дашборды), плюс node_exporter для хоста. Loki, GitLab и другие базы не используются; сбор логов в Loki можно добавить
позже, но он не нужен для этапа.

## Быстрый старт

Нужны Docker с плагином compose, `python3`, `curl`. warp берётся из образа `minio/warp` (если на хосте есть бинарник
`warp`, он используется вместо контейнера: `WARP_RUNNER=local`).

```bash
# 1. стенд
cd bench/observability
cp .env.example .env             # поменяйте пароли и токен; .env в git не попадает
docker compose up -d             # SectoriaDB (профиль server), Prometheus, Grafana, InfluxDB, node_exporter
#   Grafana    http://localhost:3000   (admin / GRAFANA_ADMIN_PASSWORD из .env), папка SectoriaDB
#   Prometheus http://localhost:9090
#   InfluxDB   http://localhost:8086
#   метрики сервера: http://localhost:9464/actuator/prometheus

# 2. один прогон (30 с по умолчанию: проверка стенда, не измерение)
cd ../warp
./run.sh --mode mixed --size small --conc 50 --duration 5m --influx

# 3. матрица режимов x размеров x параллелизма (последовательно)
MODES="get put mixed" SIZES="small medium" CONCS="20 50 100" DURATION=2m ./matrix.sh --influx

# 4. сравнение двух прогонов
./compare.sh <каталог-до> <каталог-после>
```

Каждый прогон кладёт в `bench/results/<UTC>_<sha>_<режим>_<размер>_c<N>/` данные warp, `analyze.txt` и `meta.json`
(коммит, конфигурация, аргументы warp, железо). В Grafana на дашбордах появляется аннотация с тегами
`mode:`, `size:`, `conc:`, `commit:` на время прогона.

## Совместимость, отказы, безопасность

```bash
bench/compat/s3tests.sh          # ceph/s3-tests (нужны git, python3-venv, доступ к GitHub и PyPI при первом запуске)
bench/compat/mint.sh             # minio/mint: SDK и утилиты (awscli, aws-sdk-*, minio-*, s3cmd, mc)
bench/faults/run-all.sh          # kill -9, полный диск, порча чанка, сеть, безопасность; печатает PASS/FAIL
```

Стенд совместимости и отказов использует отдельный контейнер/каталоги данных и порт 8090, трогать стенд на 8080 не нужно.
Сводки: `bench/compat/work/*/summary.txt`, `bench/faults/work/*.log` (каталоги `work/` не в git).

## Образ SectoriaDB

`docker compose` собирает образ из корневого `Dockerfile` (`image: sectoriadb:bench`). Чтобы использовать готовый образ
(например, собранный иначе), соберите его заранее: `docker build -t sectoriadb:bench .`, затем `docker compose up -d`
без `--build`. Для фиксации версий в `docker-compose.yml` образы Prometheus, Grafana, InfluxDB и node_exporter и образ warp
в `warp/lib.sh` указаны с digest: те версии, на которых проводились измерения.
