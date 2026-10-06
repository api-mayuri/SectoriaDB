# Этап 05 — контрольные суммы объекта (сквозная целостность)

Ветка `05-object-checksums`, растёт из `04-small-objects`. Обратная совместимость данных на диске не требуется (решение
владельца), но манифесты без новых полей по-прежнему читаются: у них просто нет сохранённых сумм, и соответствующая проверка
пропускается.

Требование владельца: «сделай проверку контрольной суммы» — целостность должна проверяться **от клиента до диска и обратно**,
а не только внутри блоба.

## 1. Слои целостности

| # | Слой | Что защищает | Кто считает / проверяет | Когда |
|---|------|--------------|--------------------------|-------|
| 1 | Транспорт: SigV4 | тело запроса не подменено в пути | `x-amz-content-sha256` (`Sha256VerifyingInputStream`), подписи фрагментов и trailer в `aws-chunked` (`AwsChunkedInputStream`) | этап 02, при загрузке |
| 2 | S3 additional checksums | клиент и сервер видят одни и те же байты | `x-amz-checksum-*` (заголовок или trailer), `Content-MD5`; считает `MultiDigestInputStream` | **этап 05**, при загрузке, до фиксации |
| 3 | CRC32C чанка / записи | диск не исказил чанк кукушки (`CHUNKED`) или запись `.sob` (`SMALL`) | `CuckooHashTable.readChunkByKey`, `SmallObjectBlob.read` | этап 03/04, при каждом чтении (в том числе `Range`) |
| 4 | CRC32C всего объекта | состав и порядок чанков целы, манифест указывает на «те» данные | `FileStorageService` (поле `crc32c` манифеста) | **этап 05**: пишется всегда; проверяется при каждом полном чтении |
| 5 | CRC метаданных | манифесты, реестры блобов не повреждены | MetaStore (страницы с CRC) | этап 06–07 |

Слой 3 ловит порчу байтов на диске. Слой 4 ловит то, чего слой 3 не видит: чанк целый, но манифест ссылается не на тот
(перепутанный ключ, подмена списка ключей, обрезанный список, неверный размер последнего чанка, коллизия ключей). Слой 2
замыкает кольцо с клиентской стороны: сервер доказывает, что сохранил ровно то, что клиент отправил.

## 2. Алгоритмы (`org.example.sectoriadb.checksum`, модуль core)

`ChecksumAlgorithm` — перечисление S3-алгоритмов; значение передаётся как **base64 от digest в big-endian**:

| Алгоритм | Заголовок | Длина | Реализация | Контрольное значение |
|---|---|---|---|---|
| `CRC32` | `x-amz-checksum-crc32` | 4 | `java.util.zip.CRC32` | `"123456789"` → `0xCBF43926` |
| `CRC32C` | `x-amz-checksum-crc32c` | 4 | `java.util.zip.CRC32C` | `"123456789"` → `0xE3069283` |
| `CRC64NVME` | `x-amz-checksum-crc64nvme` | 8 | `Crc64Nvme` (таблица, полином `0xAD93D23594C93659` в отражённой форме, init/xor `~0`) | `"123456789"` → `0xAE8B14860A799888` |
| `SHA1` | `x-amz-checksum-sha1` | 20 | `MessageDigest` | |
| `SHA256` | `x-amz-checksum-sha256` | 32 | `MessageDigest` | |

CRC64NVME реализован полностью (не заглушка). Остальные классы:

* `ChecksumCalculator` — инкрементальный расчёт одного digest;
* `MultiDigest` — **один проход** по байтам: MD5 (ETag) + любое число алгоритмов; `MultiDigestInputStream` /
  `MultiDigestOutputStream` — обёртки над потоками;
* `UploadChecksums` — что клиент просил проверить: `Content-MD5` и не более одного алгоритма с (возможно, ещё не известным —
  trailer) ожидаемым значением;
* `ChecksumMismatchException` (`IOException`) — несовпадение, превращается в `BadDigest`;
* `ChecksumType` — `FULL_OBJECT` или `COMPOSITE`.

## 3. Загрузка: PutObject и тело `aws-chunked`

### 3.1 Что читается из запроса (`S3Checksums.parse`)

* `x-amz-checksum-crc32|crc32c|crc64nvme|sha1|sha256` — ожидаемое значение;
* `x-amz-sdk-checksum-algorithm` и/или `x-amz-checksum-algorithm` — алгоритм (оба, если даны, должны совпадать);
* `x-amz-trailer: x-amz-checksum-…` — значение придёт в trailer после тела;
* `Content-MD5` — base64 от 16 байт.

Ошибки разбора (до чтения тела): несколько checksum-заголовков (или заголовок + trailer) → `400 InvalidRequest` («Expecting a
single x-amz-checksum- header…»); алгоритм из `x-amz-sdk-checksum-algorithm` не совпадает с заголовком значения → `400
InvalidRequest`; неизвестный алгоритм → `400 InvalidRequest`; значение не base64 или не той длины → `400 InvalidRequest`
(«Value for x-amz-checksum-… header is invalid.»); `Content-MD5` не base64 / не 16 байт → `400 InvalidDigest`.

Если указан алгоритм **без значения** (`x-amz-sdk-checksum-algorithm: SHA1`), сервер сам считает его и сохраняет.

### 3.2 Trailer

`AwsChunkedInputStream` уже разбирал trailer и проверял его подпись. Теперь после последнего фрагмента (и после проверки
`x-amz-trailer-signature`, если он подписан) разобранные заголовки отдаются слушателю `TrailerListener`
(`S3Checksums.Declared.acceptTrailers`), который кладёт ожидаемое значение в `UploadChecksums`. Правила:

* checksum-заголовок в trailer, который **не объявлен** в `x-amz-trailer`, или с неверным base64 → `400 InvalidRequest`;
* объявлен, но в trailer отсутствует → `400 InvalidRequest`;
* значение совпало по форме — оно сравнивается с digest, посчитанным за один проход вместе с MD5 и CRC32C (см. ниже).

Порядок проверок для подписанного trailer: подпись фрагментов → подпись trailer → значение checksum. Так подмена trailer
даёт `403 SignatureDoesNotMatch`, а честно подписанный, но неверный checksum — `400 BadDigest`.

### 3.3 Проверка до фиксации (`FileStorageService.storeStream`)

```
тело ─► MultiDigestInputStream ─► временный файл          (MD5 + CRC32C + алгоритм клиента, один проход)
                                  │
                       UploadChecksums.verify(...)         ← несовпадение: ChecksumMismatchException
                                  │
                      store(tmp, pool, crc32c)             ← только теперь: запись .sob / чанки кукушки / манифест
```

Проверка стоит **между** копированием во временный файл и `store(...)`, поэтому при несовпадении не создаётся ничего:
ни записи в `small_*.sob` (для `SMALL` она не дописывается), ни чанков в кукушке, ни блоба, ни манифеста; временный файл
удаляется в `finally`. Это покрыто тестами для размеров 0, 100, 4095, 4096 и 50 000 байт. Проверка `Content-MD5` перенесена
из контроллера (где объект сначала записывался, а потом «удалялся») в это же место: теперь и она срабатывает до фиксации.
При неудачной перезаписи прежняя версия объекта остаётся нетронутой.

Ошибка: **`400 BadDigest`**, текст — `The CRC32C you specified did not match the calculated checksum.` (имя алгоритма
подставляется); для MD5 — `The Content-MD5 you specified did not match what we received.`

## 4. Сохранённые контрольные суммы (манифест)

| Поле `ManifestEntity` | Значение |
|---|---|
| `crc32c` | **всегда**: CRC32C всего объекта, base64 big-endian (формат S3 `x-amz-checksum-crc32c`, 8 символов; пустой объект — `AAAAAA==`). Считается за тот же проход, что и MD5. Выбран base64, а не hex — значение можно отдавать клиенту без преобразования. |
| `checksumAlgorithm` | алгоритм клиента (`CRC32`, `CRC32C`, `CRC64NVME`, `SHA1`, `SHA256`) или отсутствует |
| `checksumValue` | его значение (base64); для `COMPOSITE` — с суффиксом `-N` |
| `checksumType` | `FULL_OBJECT` или `COMPOSITE` |

Старые манифесты без этих полей читаются (поля `null`): `crc32c == null` значит «проверка целого объекта для него не
выполняется», `verify` пишет `whole-object CRC32C: not stored`. Для объектов, записанных через shell `store`, `crc32c`
считается отдельным проходом по файлу; `etag` у них по-прежнему пуст.

`CopyObject` сохраняет алгоритм источника (если у него `FULL_OBJECT`) и пересчитывает значение по скопированным байтам;
`x-amz-checksum-algorithm` в запросе копирования выбирает алгоритм назначения.

## 5. Multipart

### 5.1 CreateMultipartUpload

`x-amz-checksum-algorithm` (или `x-amz-sdk-checksum-algorithm`) и `x-amz-checksum-type` сохраняются в `meta.properties`
загрузки. Тип по умолчанию — как в S3: `COMPOSITE` для CRC32/CRC32C/SHA1/SHA256, `FULL_OBJECT` для CRC64NVME. Ошибки
`400 InvalidRequest`: `FULL_OBJECT` с SHA1/SHA256, `COMPOSITE` с CRC64NVME, тип без алгоритма. В ответе —
`x-amz-checksum-algorithm` и `x-amz-checksum-type`.

### 5.2 UploadPart и UploadPartCopy

* Проверяются `Content-MD5`, `x-amz-checksum-*` и trailer части — по тем же правилам, что у PutObject; плохая часть не
  сохраняется (временный файл удаляется, в `etags.properties` ничего не появляется) → `400 BadDigest`.
* Если у загрузки есть алгоритм, а часть пришла с **другим** алгоритмом → `400 InvalidRequest`.
* Значение части записывается рядом с ETag в `checksums.properties` (`<номер>=<АЛГОРИТМ>:<base64>`), атомарно (временный
  файл + `ATOMIC_MOVE`, под тем же замком, что и `etags.properties`). `ListParts` отдаёт `<ChecksumCRC32C>…` у частей, а
  также `ChecksumAlgorithm`/`ChecksumType` загрузки.
* UploadPartCopy считает алгоритм загрузки по скопированным байтам и возвращает его в `CopyPartResult`. Источник читается
  через проверяемый `streamToOutput`: если целиком объект повреждён — `500 InternalError`, часть не сохраняется.

### 5.3 CompleteMultipartUpload

1. Для каждой части из XML: если указан `<ChecksumXXX>`, он сравнивается с записанным в `checksums.properties`;
   другое значение → `400 BadDigest` («The CRC32C you specified for part N did not match what we received.»), часть без
   такого алгоритма → `400 InvalidPart`. Если загрузка создана с алгоритмом, у каждой части он обязан быть записан.
2. **COMPOSITE**: значение = алгоритм от конкатенации бинарных checksum частей (в порядке номеров), base64, суффикс `-N`
   (`N` — число частей). Например, для CRC32C: `base64(CRC32C(c1 ‖ c2 ‖ c3)) + "-3"`; для SHA256 — SHA-256 от
   `d1 ‖ d2 ‖ d3`. Это значение хранится в `checksumValue` (`checksumType = COMPOSITE`) и отдаётся в ответе и в
   `x-amz-checksum-*` при `x-amz-checksum-mode: ENABLED`. Если в Complete-запросе пришёл заголовок
   `x-amz-checksum-<alg>`, он должен совпасть с составным значением, иначе `400 BadDigest` до фиксации.
3. **FULL_OBJECT** (CRC32/CRC32C/CRC64NVME): значение — алгоритм, посчитанный по итоговому склеенному потоку (тот же
   проход, что и MD5/CRC32C); заголовок `x-amz-checksum-<alg>` Complete-запроса, если он есть, проверяется до фиксации.
4. **Всегда**: `crc32c` всего объекта считается по итоговому потоку; ETag — как раньше (`md5(md5₁‖…)-N`).

Загрузка при ошибке Complete остаётся на месте (можно повторить Complete); ничего не фиксируется.

## 6. Чтение

### 6.1 Заголовки (`x-amz-checksum-mode: ENABLED`)

`HEAD` и полный `GET` отдают `x-amz-checksum-<alg>` и `x-amz-checksum-type`: значение клиента, если он выбирал алгоритм,
иначе всегда имеющийся CRC32C (`FULL_OBJECT`). Для `GET` с `Range` заголовки **не** отдаются (как в AWS). Без
`x-amz-checksum-mode` заголовков нет. `PutObject` и `CompleteMultipartUpload` возвращают заголовок только если алгоритм
выбрал клиент.

### 6.2 Проверка при каждом полном чтении

`FileStorageService.streamToOutput` (GET, источник CopyObject / UploadPartCopy, `restore`) пересчитывает CRC32C всего
объекта по ходу чтения и в конце сравнивает его с `crc32c` манифеста. Для `SMALL` проверка идёт до единственной записи в
выход, для `CHUNKED` — **до записи последнего чанка**: при несовпадении потребитель не получил последние ≤ `chunkSize`
байт. Это важно для HTTP: при заданном `Content-Length` клиент, получивший все байты, считает ответ полным. Здесь он
получает меньше — перелом виден клиенту как сбой передачи, а не как «удачный» повреждённый файл.

При несовпадении: `log.error` с бакетом, ключом и id манифеста (`Whole-object CRC32C mismatch: bucket=… key=… manifest=…`) и
`ObjectCorruptedException`. Дальше:

| Ситуация | Результат |
|---|---|
| GET, ответ ещё не отправлен (объект до размера буфера Tomcat ≈ 8 КиБ, `SMALL`, повреждение в начале) | `response.reset()`, `500 InternalError` в XML |
| GET, заголовки уже ушли | `ResponseAbortedException` не превращается в XML-ответ (его пропускает `S3ExceptionHandler`); исключение доходит до Tomcat, он закрывает соединение (`CLOSE_NOW`): клиент видит обрыв, длина тела меньше `Content-Length` |
| то же самое для `ChunkCorruptedException`, `SmallObjectCorruptedException`, `ChunkNotFoundException` | то же поведение, в журнал — `ERROR` |
| CopyObject / UploadPartCopy с повреждённым источником | `500 InternalError`, ничего не фиксируется (временный файл удаляется) |
| shell `get` (restore) | исключение, частично записанный выходной файл удаляется |

`Range`-чтения целый объект не читают и опираются **только** на CRC32C чанка/записи (слой 3). Это сознательно: проверка
всего объекта на диапазон потребовала бы прочитать его целиком.

## 7. Консоль

* `verify --id ID` — перечитывает объект и проверяет: CRC32C чанков/записи (при чтении), сохранённый CRC32C объекта,
  checksum клиента (только `FULL_OBJECT`; составное значение без границ частей не пересчитать — пропускается), MD5 против
  ETag (для не-multipart ETag). Вывод: `OK`/`CORRUPT`/`MISSING` и по строке на каждую проверку. Объекты без ETag (shell) или
  multipart больше не отвергаются — проверяются остальные слои.
* `verify-all [--pool NAME]` — то же для всех живых объектов (или одного пула/бакета); печатает проблемные объекты, итог
  `Verified N object(s): ok=… corrupt=… missing=…` и последнюю строку `RESULT: OK` либо `RESULT: FAILED (corrupt=…,
  missing=…)`. «Missing» — чанк/блоб, на который ссылается манифест, не найден; «corrupt» — любое несовпадение CRC/MD5.
* `info` показывает `CRC32C` и checksum клиента.

Логика — в `ObjectVerificationService` (core), поэтому проверяется юнит-тестами без shell.

## 8. Отличия от AWS и ограничения

* Если у multipart-загрузки есть алгоритм, а часть пришла без checksum, AWS отвечает ошибкой; здесь сервер сам считает
  значение части (мягче). Часть с **другим** алгоритмом отвергается, как в AWS.
* При `x-amz-checksum-mode: ENABLED` для объектов без выбранного клиентом алгоритма отдаётся CRC32C (AWS в этом случае
  отдаёт CRC64NVME по умолчанию). Для старых манифестов без `crc32c` заголовков нет.
* `FULL_OBJECT` для multipart разрешён только для CRC32/CRC32C/CRC64NVME (как в AWS); значение считается по итоговому
  потоку, а не комбинацией CRC частей — результат тот же.
* Составные checksum не пересчитываются в `verify` (нужны границы частей); для таких объектов проверяются CRC32C
  и MD5/ETag (ETag multipart — не MD5 тела, поэтому пропускается).
* Trailer без объявления в `x-amz-trailer` отвергается (AWS SDK всегда объявляют).
* Удержание последнего чанка гарантирует лишь, что клиент не получит **полное** тело повреждённого объекта; уже отправленные
  начальные байты вернуть нельзя (HTTP). Клиент, который не сверяет длину, может принять обрыв за конец — поэтому клиентам
  стоит включать `x-amz-checksum-mode: ENABLED` и сверять значение.
* CRC метаданных (манифестов) этот этап не вводит — это задача хранилища метаданных (этапы 06–07).
* `Content-Encoding: aws-chunked` по-прежнему сохраняется в манифесте как есть (вне задач этапа).

## 9. Тесты

Core (+22 теста: 77 вместо 55):
* `ChecksumAlgorithmTest` (9): известные значения CRC32C/CRC32/CRC64NVME/SHA-1/SHA-256, пустой вход, base64 big-endian,
  инкрементальный = одноразовый расчёт, CRC64 против побитовой эталонной реализации, составной checksum против
  независимого `java.util.zip.CRC32`, `MultiDigest` за один проход.
* `ObjectChecksumStorageTest` (13): `crc32c` и checksum клиента в манифесте для размеров 0…3·chunk+17; несовпадение **каждого**
  алгоритма на каждом размере → исключение, ни манифеста, ни блоба; то же для `Content-MD5`; значение, поступающее во время
  чтения (trailer); порча `crc32c` в манифесте → ошибка полного чтения и `restore` (файл удалён) для всех видов; последний
  чанк не отдаётся; Range игнорирует `crc32c`; манифест без новых полей; `verify` (ОК и все виды несовпадений),
  `verifyAll` (ok/corrupt/missing, порча байта прямо в файле блоба).

Server (+24 теста: 98 вместо 74):
* `S3ChecksumsTest` (20): все алгоритмы × размеры 0/100/4095/4096/50 000 — PUT ok / mismatch (объекта нет); неудачная перезапись
  сохраняет старую версию; несколько заголовков; некорректные значения; алгоритм без значения; `Content-MD5` ok/BadDigest/
  InvalidDigest; `crc32c` и checksum в манифесте; checksum-mode на HEAD/GET и отсутствие на Range; multipart: составной
  CRC32C (эталон считается в тесте независимо), составной SHA-256, FULL_OBJECT CRC32, `ListParts`, неверные части/Complete,
  неверные сочетания типа; GET: порча `crc32c` у большого объекта — клиент получает `IOException`, а «сырой» сокет — тело
  короче `Content-Length`; у малого — чистый 500; порча чанка на диске (поздняя — обрыв, ранняя — 500); CopyObject и
  UploadPartCopy повреждённого источника — 500 и ничего не фиксируется; CopyObject сохраняет checksum клиента; trailer без подписи
  (`STREAMING-UNSIGNED-PAYLOAD-TRAILER`) для всех алгоритмов, на малом и большом теле, и ошибки trailer.
* `S3SigV4PayloadTest` (+3): подписанный trailer — ok/BadDigest для всех алгоритмов, тело из нескольких фрагментов, trailer без
  объявления в `x-amz-trailer`.
* `VerifyCommandsTest` (1): `verify` и `verify-all` (в том числе строка `RESULT`).
