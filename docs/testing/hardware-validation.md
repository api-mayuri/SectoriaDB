# Проверка на своём железе (этап 3)

Программа для машины владельца с диском 2 ТБ. В песочнице разработки выполнены сценарии отказов и фаззинг (ниже);
объём, заполнение и длительные прогоны там невозможны: свободно меньше 10 ГБ, контейнер перезапускается. Всё ниже — существующие
скрипты репозитория, по порядку. Результаты — в `docs/reports/` новым отчётом с датой, железом и коммитом (шаблон в конце).

## Подготовка

```bash
mvn -B verify                                        # сборка и тесты
docker build -t sectoriadb:bench .                   # образ из текущего коммита
cd bench/observability && cp .env.example .env && docker compose up -d   # сервер + Prometheus + Grafana + InfluxDB
```

Том `/data` стенда должен лежать на проверяемом диске (2 ТБ). Дашборд «SectoriaDB» в Grafana (`127.0.0.1:3000`) открыт всё время.

## 1. Отказы на физическом диске

```bash
cd bench/faults && sudo ./run-all.sh                 # kill -9 при PUT и multipart, полный диск, порча чанка, снимок, сеть, безопасность
```
**Критерий:** все 7 сценариев `SUMMARY PASS`. `full-disk.sh` и `snapshot-backup.sh` нужен root (loop-устройство).

## 2. Загрузка 1 ТБ и заполнение 70–90 %

```bash
cd bench/warp
for i in $(seq 1 10); do ./preload.sh load --bucket warp-1tb --size medium --objects 100000 --conc 50 --prefix part$i; done
# на 70, 80 и 90 % заполнения диска (df на томе /data):
./preload.sh run --bucket warp-1tb --mode get --conc 50 --duration 15m
./run.sh --mode mixed --size small --conc 20 --duration 15m --extra "--get-distrib 40 --delete-distrib 15"
```
**Критерий:** ни одного 5xx кроме задуманных `503 SlowDown`/`507 InsufficientStorage` у порога; p99 GET и PUT на 90 % не хуже чем в 1,5 раза
против 70 %; `verify-all` после загрузки — `RESULT: OK`; размер `sectoria.db` растёт пропорционально числу объектов (дашборд, панель метаданных).

## 3. Длительные прогоны

```bash
./soak.sh --duration 8h --sample 5m --conc 20 --size small
./soak.sh --duration 24h --sample 5m --conc 20 --size small      # перед релизом
```
**Критерий:** отчёт дрейфа в конце `soak.sh` — пропускная способность и p99 PUT/GET последнего окна в пределах ±15 % от первого,
`rss_mb`, `meta_file_mb`, `tombstones` выходят на плато (`grew, then levelled off`, без `WARN: monotonic growth`, кроме `store_du_mb`,
который следует за данными).

## 4. Сравнение с другими S3-серверами (необязательно)

```bash
cd bench/compare && ./compare.sh --duration 4m --fsync both
```
Результат — `bench/results/compare_<время>/summary.tsv`; правила честного сравнения — [benchmarking.md](benchmarking.md#сравнение-с-другими-s3-серверами).

## 5. Фаззинг на часы (ночной прогон)

```bash
for t in "sectoriadb-metastore PageFormatFuzzTest#treePageDecode" "sectoriadb-core RecordFormatFuzzTest#manifestRecord" \
         "sectoriadb-core RecordFormatFuzzTest#tombstoneRow" "sectoriadb-server CompleteXmlFuzzTest#completeBody"; do
  set -- $t; JAZZER_FUZZ=1 mvn -pl $1 test -Dtest="$2" -Djazzer.max_duration=1h
done
```
Найденные входы (`crash-*` в каталоге модуля) — в `src/test/resources/<пакет>/<Тест>Inputs/<метод>/` и исправление ([fault-injection.md](fault-injection.md)).

## Что уже сделано в песочнице (2026-10-29, ветка `23-hardware-validation`)

| Проверка | Итог |
|---|---|
| `bench/faults/run-all.sh` (виртуальный диск, Docker) | 7 из 7: kill-put 10/10, kill-multipart 5/5, full-disk 7/7, corrupt-chunk 5/5, snapshot-backup 9/9, network-faults 4/4, security 69/69 |
| найдено при этом | скрипты отказов отстали от образа (непривилегированный пользователь, синтаксис ключей, порог свободного места больше тестового диска) — исправлены; Tomcat 11 обрывал соединение на теле 18 МБ вместо `400` — `max-swallow-size=32MB` |
| фаззинг Jazzer, 9 целей по 1–2 мин | одна настоящая находка (повреждённая строка очереди надгробий останавливала сборщик) — исправлена с регрессионным тестом |
| ceph/s3-tests, mint | этап 21: те же результаты, что у предыдущей версии |
| soak 45 мин | этап 21: 3433 → 3551 запросов/с, p99 PUT 41 → 42,5 мс |

## Шаблон отчёта

```
# Проверка на железе <дата>
Коммит: <sha>   Железо: <CPU, RAM, диск (модель, NVMe/SATA), ФС>   ОС/ядро: <...>   Docker: <...>
1. Отказы: <7/7 или что упало>
2. 1 ТБ: загрузка <часы>, <МиБ/с>; на 70/80/90 %: p99 GET <..>, p99 PUT <..>, 5xx <..>; verify-all <..>; sectoria.db <ГБ>
3. Soak 8 ч / 24 ч: отчёт дрейфа (вставить вывод)
4. Сравнение (если было): summary.tsv
5. Фаззинг: цели, часы, находки
```
