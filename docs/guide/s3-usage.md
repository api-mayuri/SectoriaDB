# Работа с S3 API SectoriaDB

Руководство для тех, кто подключает к SectoriaDB `aws cli`, SDK или веб-интерфейс. Адресация только path-style:
`http://host:8080/<bucket>/<key>`. Авторизация — AWS Signature Version 4 (заголовок `Authorization` и presigned-ссылки).

## Главные понятия

- **Бакет** — «папка верхнего уровня». **Объект** — файл внутри бакета; ключ — полный путь (`photos/2026/cat.jpg`).
  Настоящих папок нет: `photos/` — общий префикс ключей. Ключ до 1024 байт UTF-8 (`KeyTooLongError` иначе).
- **Access Key ID + Secret Key** — «логин и пароль» программы. Секрет по сети не передаётся, им подписывается запрос.
  Ключи создаются в [консоли администратора](admin-shell.md) (`mk-key`).
- **Всё приватно по умолчанию.** Запрос без подписи получает `403 AccessDenied`.
- **Владелец один.** IAM, пользователей и ролей нет: любой действующий ключ имеет полный доступ ко всем бакетам
  (см. [безопасность](../architecture/security.md)). Для изоляции нужны отдельные экземпляры.

## Поддерживаемые операции

| Область | Операции |
|---|---|
| Сервис | `ListBuckets` |
| Бакеты | `CreateBucket` (повторное создание существующего даёт `200`; заголовки `x-amz-acl`/`x-amz-grant-*` применяются), `HeadBucket`, `DeleteBucket` (`409 BucketNotEmpty`, если есть объекты; незавершённые multipart-загрузки отбрасываются), `GetBucketLocation` |
| Листинг | `ListObjects` (v1) и `ListObjectsV2`: `prefix`, `delimiter`, `max-keys` (не более 1000, `0` даёт пустую страницу), `marker`/`continuation-token`/`start-after`, `encoding-type=url`, `fetch-owner`; `ListObjectVersions` (каждый объект как версия `null`) |
| Объекты | `PutObject`, `GetObject` (`Range`, условные заголовки, `response-*`), `HeadObject`, `DeleteObject` (идемпотентно, `204`), `DeleteObjects` (до 1000 ключей, `Quiet`), `CopyObject` |
| Multipart | `CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `CompleteMultipartUpload`, `AbortMultipartUpload`, `ListParts`, `ListMultipartUploads`, `GET`/`HEAD ?partNumber=N` |
| Доступ | `Get/PutBucketAcl`, `Get/PutObjectAcl`, `Get/Put/DeleteBucketPolicy`, presigned-ссылки |
| Контрольные суммы | `x-amz-checksum-crc32/crc32c/crc64nvme/sha1/sha256`, `Content-MD5`, trailer `aws-chunked`, `x-amz-checksum-mode: ENABLED` |

### Заглушки и неподдерживаемое

| Запрос | Ответ |
|---|---|
| `GetBucketVersioning` | пустая конфигурация (версионирование выключено); `PutBucketVersioning` — `501` |
| `GetBucketTagging`, `GetBucketCors`, `GetBucketLifecycle`, `GetBucketEncryption`, `GetPublicAccessBlock` | `404` с кодом «конфигурации нет» (`NoSuchTagSet`, `NoSuchCORSConfiguration`, ...) |
| `GetObjectTagging` | пустой `TagSet`; `PutObjectTagging` — `501` (теги не хранятся и не теряются молча); `DeleteObjectTagging` — `204` |
| `?object-lock`, `?ownershipControls`, `?website`, `?logging`, `?notification`, `?policyStatus`, `?accelerate`, `?replication`, `?analytics`, `?inventory`, `?metrics`, `?requestPayment`, `?intelligent-tiering` у бакета; `?attributes`, `?retention`, `?legal-hold`, `?torrent`, `?select`, `?restore` у объекта | `501 NotImplemented` |
| `PUT`/`DELETE` с неизвестным подресурсом (`DELETE /b?tagging`, `PUT /b/k?retention`) | `501`, а не удаление бакета или перезапись объекта; разрешены только `x-id` и параметры `X-Amz-*` presigned-ссылок |
| `x-amz-copy-source-if-*` у `CopyObject` | `501` (не вычисляются, и игнорировать их небезопасно) |
| POST-форма (`POST /bucket` с политикой), SigV2, SigV4a (`STREAMING-*` ECDSA), версионирование, блокировки, CORS, шифрование на стороне сервера, S3 Select, уведомления | не реализованы |

## Как отдать файл другим людям

| Нужно | Способ | Команда |
|---|---|---|
| Файл доступен **всем, всегда** | публичный объект | `object-acl --bucket B --key K --acl public-read` |
| Бакет открыт на **листинг** | публичный бакет | `bucket-acl --bucket B --acl public-read` |
| Файл **на время** одному человеку | presigned-ссылка | `aws s3 presign s3://B/K --expires-in 3600` |
| **Все файлы** бакета или папка | политика бакета (`s3:GetObject`) | `aws s3api put-bucket-policy ...` |

`object-acl`/`bucket-acl` — команды консоли: рядом с запущенным сервером они **не работают** (нужен доступ к метахранилищу),
см. [admin-shell.md](admin-shell.md). Те же ACL ставятся через S3 API (`aws s3api put-object-acl --acl public-read`).

### Семантика ACL и политик

- **Только готовые (canned) ACL**: `private`, `public-read`, `public-read-write`. `authenticated-read`, `aws-exec-read`,
  `bucket-owner-*`, `log-delivery-write` сохраняются как `private` (владелец один); неизвестное имя — `400 InvalidArgument`.
  XML-ACL и заголовки `x-amz-grant-*` сводятся к тем же трём значениям: учитывается только группа `AllUsers` (READ и WRITE).
  Некорректный документ — `400 MalformedACLError`.
- **`public-read` на бакете открывает только листинг** (`ListObjects`, `HeadBucket`), но не чтение объектов (как в AWS).
  Объект читается анонимно, если он сам `public-read` либо политика бакета разрешает `s3:GetObject`.
- **`public-read-write` на бакете** даёт анонимные `PUT` и `DELETE` объектов (но не чтение, ACL, политики и multipart).
- **Политика бакета** вычисляется как в AWS: явный `Deny` побеждает любой `Allow` и любые ACL. Поддерживаются `Principal`
  `"*"`, `Action`/`Resource` со строками и массивами и подстановками `*`/`?`, `NotAction`/`NotResource`/`NotPrincipal` только в `Deny`.
  Условия (`Condition`) **не вычисляются**: `Allow` с условием не действует, `Deny` с условием действует всегда.
  Политика, не разбирающаяся как JSON, игнорируется. Размер до 20 КиБ.
- **Политики и ACL действуют только на анонимные запросы.** Подписанный запрос любым действующим ключом имеет полный доступ.
- Анонимный запрос с `x-amz-copy-source`, не-`private` ACL или `x-amz-grant-*` — `403` (иначе открытый бакет стал бы способом
  вытащить приватные объекты). Подресурсы `acl`, `policy`, `uploads`, `uploadId`, `partNumber`, `tagging`, `delete`, `location` и т. п.
  анонимно недоступны.
- Параметры `response-content-type` и другие `response-*` разрешены только подписанным запросам.

Пример политики «читать всё в бакете»:
`{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::B/*"}]}`.

Публичная ссылка: `http://ВАШ-АДРЕС:8080/бакет/ключ`. Не открывайте приватный бакет как `public-read` и не используйте
`public-read-write` без нужды.

## Загрузка и скачивание

- `PutObject`/`UploadPart` **обязаны** сообщить размер (`Content-Length` или `x-amz-decoded-content-length`): иначе `411 MissingContentLength`.
  Предел одного запроса — 5 ГиБ (`400 EntityTooLarge`); больше — только multipart. Тело сначала пишется во временный каталог
  (`sectoriadb.upload-tmp-dir`), поэтому там нужно свободное место не меньше объекта плюс запас
  (`upload-free-space-reserve-bytes`, 64 МиБ); не хватает — `503 SlowDown`.
- Способ хранения выбирается по размеру: пустой объект — только метаданные; меньше 16 КиБ — одна запись в файле малых объектов;
  остальное — чанки с дедупликацией по всему бакету.
- `GET`: одиночный диапазон `Range: bytes=a-b | a- | -n` (`206`, `Content-Range`); конец за пределами объекта обрезается, начало за пределами — `416 InvalidRange`;
  несколько диапазонов и мусор игнорируются (отдаётся весь объект). Условные `If-None-Match`/`If-Match` (одно точное значение ETag,
  без списков и `*`), `If-Modified-Since`/`If-Unmodified-Since`.
- Метаданные: `x-amz-meta-*`, `Content-Type`, `Cache-Control`, `Content-Disposition`, `Content-Encoding`, `Content-Language`.
  Лимит S3 на размер пользовательских метаданных (2 КБ) не проверяется. `Expires` и `x-amz-storage-class` не сохраняются.
- `CopyObject` **читает и записывает данные заново** (дедупликация не пишет повторно уже имеющиеся чанки, но поток проходит через сервер).
  `x-amz-metadata-directive` `COPY`/`REPLACE`; копирование объекта в себя без `REPLACE` — `400`. Лимита в 5 ГиБ на копирование нет.
- ETag обычного объекта — MD5 в кавычках; у multipart — `"md5(md5 частей)-N"`.
- `ListBuckets` не постраничный и показывает все бакеты любому ключу.

### Условные записи

`PutObject` и `CompleteMultipartUpload` понимают `If-None-Match: *` (создать только если ключа нет) и `If-Match: "<etag>"`
(заменить только эту версию). Условие проверяется атомарно внутри транзакции коммита; не выполнено — `412 PreconditionFailed`
(`404 NoSuchKey` для `If-Match` на отсутствующий ключ). Другие формы (`If-None-Match` с ETag, списки ETag) — `501`.

## Контрольные суммы

Сервер хранит CRC32C каждого объекта и проверяет его при каждом полном чтении; при порче данных скачивание **обрывается**
(клиент видит ошибку или неполный файл), а в логе появляется `ERROR`. Подробности — [integrity.md](../architecture/integrity.md).

```bash
aws s3 cp big.iso s3://B/big.iso --checksum-algorithm SHA256        # сервер сверит сумму, иначе 400 BadDigest
aws s3api head-object --bucket B --key K --checksum-mode ENABLED    # получить сумму обратно
```

- Алгоритмы: CRC32, CRC32C, CRC64NVME, SHA1, SHA256 (заголовок или trailer `aws-chunked`) и `Content-MD5`. Несколько сумм в одном запросе — `400 InvalidRequest`.
  Несовпадение — `400 BadDigest`, объект не сохраняется, прежняя версия остаётся.
- `x-amz-content-sha256` и подписи фрагментов `aws-chunked` проверяются (`400 XAmzContentSHA256Mismatch`); `UNSIGNED-PAYLOAD` разрешён, пока
  `sectoriadb.s3.auth.allow-unsigned-payload=true`.
- Без выбора алгоритма клиентом `checksum-mode` отдаёт CRC32C объекта (AWS отдал бы CRC64NVME). Для `Range` и `?partNumber` суммы не возвращаются.
- Проверка всего хранилища: `verify` / `verify-all` в консоли (офлайн).

## Multipart

`aws s3 cp` и SDK сами режут большие файлы на части.

- Части — обычные данные (нарезка на чанки, дедупликация). `Complete` **не копирует данные**: одна транзакция метаданных собирает объект
  из частей, поэтому завершение занимает миллисекунды независимо от размера.
- Номера частей 1–10000. Повтор части с тем же номером заменяет прежнюю (побеждает последняя). `Complete` требует номера строго по возрастанию
  (`InvalidPartOrder`), совпадения ETag (`InvalidPart`).
- **Минимальный размер части — 5 МиБ** (`sectoriadb.multipart.min-part-size`, `0` отключает): каждая часть, кроме последней, меньше — `400 EntityTooSmall`
  (с `PartNumber`, `ProposedSize`, `MinSizeAllowed`); загрузка остаётся открытой, часть можно перезалить. Проверка выполняется при `Complete`, а не при `UploadPart`.
- Повторный `Complete` с тем же списком частей возвращает тот же результат (запись о завершении хранится 24 часа, `completed-retention`); другой список — `404 NoSuchUpload`.
- Брошенные загрузки старше 7 суток (`stale-after`) отменяются сами. Вручную: `aws s3api list-multipart-uploads`, `abort-multipart-upload`.
- `GET`/`HEAD ?partNumber=N` объекта, собранного multipart, отдаёт байты части: `206`, `Content-Range`, `x-amz-mp-parts-count`. С `Range` одновременно — `400`;
  номер вне объекта — `416 InvalidPartNumber`. У обычного объекта `partNumber=1` — весь объект (`200`), больше — `416`.
- Контрольная сумма с `--checksum-algorithm`: `COMPOSITE` (`...-N`) для CRC32/CRC32C/SHA1/SHA256, `FULL_OBJECT` для CRC64NVME (и по запросу CRC32/CRC32C).
  Часть с другим алгоритмом, чем у загрузки, — `400 InvalidRequest`.
- `UploadPartCopy` копирует данные (`x-amz-copy-source-range` поддержан), повторного использования чанков источника нет.
- В `ListParts`/`ListMultipartUploads` нет `Initiator`/`Owner`.

## Ограничения запросов и коды ошибок

| Ограничение | Значение | Ответ |
|---|---|---|
| Тело `PutObject`/`UploadPart` | до 5 ГиБ, размер обязателен | `400 EntityTooLarge` / `411 MissingContentLength` |
| Политика / ACL / XML (`DeleteObjects`, `Complete`) | 20 КиБ / 64 КиБ / 2 МиБ | `400 PolicyTooLarge` / `MalformedACLError` / `MalformedXML` |
| `DeleteObjects` | до 1000 ключей | `400 MalformedXML` |
| Скорость тела | не медленнее 1 КиБ/с после 30 с | `400 RequestTimeout` |
| Одновременные запросы одного ключа доступа | 64 записи, 128 чтений | `503 SlowDown` (`Retry-After: 1`) |
| Часы клиента | ±15 минут | `403 RequestTimeTooSkewed` |
| Presigned `X-Amz-Expires` | 1–604800 с | `403 ExpiredToken` после срока |
| Имя бакета | 3–63 символа `[a-z0-9.-]`, не IP, без `..` | `400 InvalidBucketName` |
| Регион в `Credential` | равен `sectoriadb.s3.region` (`enforce-region`) | `400 AuthorizationHeaderMalformed` |
| `host` и все `x-amz-*` | должны входить в `SignedHeaders` | `403` |

Особые ответы, о которых стоит знать клиентам:

- `503 ServiceUnavailable` + `Retry-After: 1` — загрузка не уложилась в период ожидания сборщика мусора или блоб был расширен под ней: повторите запрос.
- `503 SlowDown` — метахранилище восстанавливается после сбоя записи, либо превышен лимит на ключ, либо не хватает места под временный файл.
- `507 InsufficientStorage` — закончилось место на диске; чтение продолжает работать, запись возобновляется после освобождения места
  (см. [operations.md](operations.md)).
- `500 InternalError` на `GET` — порча данных обнаружена до отправки заголовков; если заголовки уже ушли, соединение просто обрывается.
- Ошибки Spring (405, 415, неизвестный путь) отдаются S3-XML (`MethodNotAllowed`, `UnsupportedMediaType`, `NotFound`).

## Практические советы

- Один ключ — одному приложению или человеку: так любой можно отозвать (`disable-key`/`rm-key`), не ломая остальных.
- Не храните ключи в git; засветился ключ — удалите и создайте новый.
- Веб-интерфейс s3manager (в `docker-compose.yml`) не умеет менять доступ, не передаёт `Range` (видео не перематывается) и публикуется
  на `127.0.0.1:8081`; он работает с полными правами ключа из `.env`.
- Не публикуйте сервер в интернет без обратного прокси с TLS: HTTPS сервер не терминирует.
