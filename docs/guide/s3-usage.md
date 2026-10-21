# Работа с S3 API SectoriaDB

Руководство для тех, кто подключает к SectoriaDB `aws cli`, SDK или веб-интерфейс. Адресация только path-style:
`https://host/<bucket>/<key>` (за TLS-прокси; без него `http://host:8080/…`). Авторизация — AWS Signature Version 4 (заголовок `Authorization` и presigned-ссылки).

## Главные понятия

- **Бакет** — «папка верхнего уровня». **Объект** — файл внутри бакета; ключ — полный путь (`photos/2026/cat.jpg`).
  Настоящих папок нет: `photos/` — общий префикс ключей. Ключ до 1024 байт UTF-8 (`KeyTooLongError` иначе).
- **Access Key ID + Secret Key** — «логин и пароль» программы. Секрет по сети не передаётся, им подписывается запрос.
  Ключи создаются в [консоли администратора](admin-shell.md) (`mk-key`).
- **Всё приватно по умолчанию.** Запрос без подписи получает `403 AccessDenied`.
- **Владелец один, права ключа задаёт IAM-политика.** Пользователей, групп и ролей нет, но у каждого ключа может быть стандартная IAM-политика
  ([ниже](#iam-политики-ключей)); ключ **без** политики имеет полный доступ ко всем бакетам. Для изоляции арендаторов — один ключ на арендатора
  с политикой на его бакеты (см. [безопасность](../architecture/security.md#авторизация-подписанных-запросов-iam)).

## Поддерживаемые операции

| Область | Операции |
|---|---|
| Сервис | `ListBuckets` |
| Бакеты | `CreateBucket` (повторное создание существующего даёт `200`, его ACL не меняется; заголовки `x-amz-acl`/`x-amz-grant-*` применяются только к новому бакету и требуют `s3:PutBucketAcl`), `HeadBucket`, `DeleteBucket` (`409 BucketNotEmpty`, если есть объекты; незавершённые multipart-загрузки отбрасываются), `GetBucketLocation` |
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
  Из условий вычисляются `"Bool":{"aws:SecureTransport":"true"|"false"}` (запрос пришёл по HTTPS или нет; ключ без учёта регистра, значение — строка, булево или массив) и `StringEquals`/`StringLike` на `s3:prefix`, `s3:delimiter` (только листинг). Остальные условия **не вычисляются**:
  `Allow` с ними не действует, `Deny` с ними действует всегда. Пример «анонимам только по HTTPS»: `Deny` на `s3:*` с `"Bool":{"aws:SecureTransport":"false"}` ([operations.md](operations.md#tls-через-nginx)).
  Политика, не разбирающаяся как JSON, игнорируется. Размер до 20 КиБ.
- **ACL действуют только на анонимные запросы.** Политика бакета действует и на подписанные: `Deny` на неё побеждает всё, `Allow` для `"*"` или для ключа (см. [IAM-политики ключей](#iam-политики-ключей)) даёт право
  даже ключу, у которого политики нет или она этого не разрешает.
- Анонимный запрос с `x-amz-copy-source`, не-`private` ACL или `x-amz-grant-*` — `403` (иначе открытый бакет стал бы способом
  вытащить приватные объекты). Подресурсы `acl`, `policy`, `uploads`, `uploadId`, `partNumber`, `tagging`, `delete`, `location` и т. п.
  анонимно недоступны.
- Параметры `response-content-type` и другие `response-*` разрешены только подписанным запросам.

Пример политики «читать всё в бакете»:
`{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::B/*"}]}`.

Публичная ссылка: `https://ВАШ-АДРЕС/бакет/ключ`. Не открывайте приватный бакет как `public-read` и не используйте
`public-read-write` без нужды.

## IAM-политики ключей

Что может ключ, решает его IAM-политика — обычный документ AWS. Подключение (рядом с работающим сервером, без перезапуска):

```bash
sed 's/example-bucket/photos/g' deploy/iam/read-write.json > /tmp/photos.json
java -jar sectoriadb.jar key-policy --id ACCESS_KEY_ID --file /tmp/photos.json    # key-policy --id ID --show | --remove
```

Готовые примеры лежат в [deploy/iam](../../deploy/iam/): `read-only.json` (список и чтение одного бакета), `read-write.json` (плюс запись, удаление и multipart), `backup-writer.json` (только добавление объектов, удалять нельзя).

**Правила вычисления** (как в AWS для одного аккаунта): явный `Deny` в политике ключа или бакета запрещает; иначе `Allow` в любой из двух разрешает; иначе, если у ключа есть политика, `403 AccessDenied`, а у ключа без политики доступ полный.
Анонимные запросы по-прежнему подчиняются ACL и политике бакета с `Principal: "*"`.

**Что поддерживается.** `Effect` `Allow`/`Deny`; `Action` и `Resource` строкой или массивом с `*` и `?`; `NotAction`/`NotResource` только в `Deny`; `Condition`: `Bool` на `aws:SecureTransport`,
`StringEquals`/`StringLike` на `s3:prefix` и `s3:delimiter`. Остальное (`Principal` в политике ключа, переменные `${...}`, другие условия, не-S3 действия) `key-policy` отвергает с понятной ошибкой, чтобы политика не значила тихо другое.

**Какое действие нужно операции:**

| Операция | Действие | Ресурс |
|---|---|---|
| `GetObject`, `HeadObject` | `s3:GetObject` | `arn:aws:s3:::бакет/ключ` |
| `PutObject`, `CreateMultipartUpload`, `UploadPart`, `CompleteMultipartUpload` | `s3:PutObject` (плюс `s3:PutObjectAcl`, если у `PutObject`/`CreateMultipartUpload`/`CopyObject` есть `x-amz-acl` не `private` или `x-amz-grant-*`) | `arn:aws:s3:::бакет/ключ` |
| `DeleteObject` (и каждый ключ `DeleteObjects`) | `s3:DeleteObject` | `arn:aws:s3:::бакет/ключ` |
| `CopyObject`, `UploadPartCopy` | `s3:GetObject` на источнике **и** `s3:PutObject` на приёмнике | |
| `AbortMultipartUpload`, `ListParts` | `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts` | `arn:aws:s3:::бакет/ключ` |
| `GetObjectAcl`, `PutObjectAcl` | `s3:GetObjectAcl`, `s3:PutObjectAcl` | `arn:aws:s3:::бакет/ключ` |
| `GetObjectTagging`, `PutObjectTagging`, `DeleteObjectTagging` | `s3:GetObjectTagging`, `s3:PutObjectTagging`, `s3:DeleteObjectTagging` | `arn:aws:s3:::бакет/ключ` |
| `ListObjects(V2)`, `HeadBucket` | `s3:ListBucket` (`s3:ListBucketVersions` для `?versions`) | `arn:aws:s3:::бакет` |
| `ListMultipartUploads` | `s3:ListBucketMultipartUploads` | `arn:aws:s3:::бакет` |
| `CreateBucket`, `DeleteBucket` | `s3:CreateBucket` (плюс `s3:PutBucketAcl` при `x-amz-acl` не `private` или `x-amz-grant-*`), `s3:DeleteBucket` | `arn:aws:s3:::бакет` |
| `GetBucketAcl`, `PutBucketAcl` | `s3:GetBucketAcl`, `s3:PutBucketAcl` | `arn:aws:s3:::бакет` |
| `GetBucketPolicy`, `PutBucketPolicy`, `DeleteBucketPolicy` | `s3:GetBucketPolicy`, `s3:PutBucketPolicy`, `s3:DeleteBucketPolicy` | `arn:aws:s3:::бакет` |
| `GetBucketLocation`, `GetBucketVersioning`, `GetBucketTagging` | `s3:GetBucketLocation`, `s3:GetBucketVersioning`, `s3:GetBucketTagging` | `arn:aws:s3:::бакет` |
| `ListBuckets` | `s3:ListAllMyBuckets` | `arn:aws:s3:::*` |

Особенности: с правом `s3:ListAllMyBuckets` `ListBuckets` возвращает имена **всех** бакетов (как AWS; содержимое остаётся закрытым), без права — `403`. Для «листинга только своего префикса» (`aws s3 ls s3://бакет/home/alice/`)
разрешите `s3:ListBucket` на `arn:aws:s3:::бакет` с условием `"StringLike":{"s3:prefix":["home/alice/*"]}` и `s3:GetObject` на `arn:aws:s3:::бакет/home/alice/*`. `DeleteObjects` не отвечает ошибкой целиком: запрещённые ключи
приходят как `<Error><Key>…</Key><Code>AccessDenied</Code>…`, остальные удаляются. Запрещённая операция — `403 AccessDenied` с текстом «User: arn:aws:iam::sectoriadb:user/ID is not authorized to perform: s3:… on resource: arn:aws:s3:::…».

**Политика бакета для ключа.** Чтобы дать ключу доступ к чужому бакету не меняя его политику, добавьте в политику бакета оператор с `Principal` `{"AWS":"arn:aws:iam::sectoriadb:user/ID"}` (допустим и просто `ID`).
`Deny` на бакете побеждает любые `Allow` ключа и действует также на ключи без политики.

**Миграция.** Ключи, созданные до этого этапа, политики не имеют и сохраняют полный доступ — обновление ничего не ломает. Подключите политики на ключи приложений и арендаторов, а ключ администратора оставьте без политики или с широкой.

## Загрузка и скачивание

- `PutObject`/`UploadPart` **обязаны** сообщить размер (`Content-Length` или `x-amz-decoded-content-length`): иначе `411 MissingContentLength`.
  Предел одного запроса — 5 ГиБ (`400 EntityTooLarge`); больше — только multipart. Тело сначала пишется во временный каталог
  (`sectoriadb.upload-tmp-dir`), поэтому там нужно свободное место не меньше объекта плюс запас
  (`sectoriadb.storage.min-free-bytes`, 1 ГиБ) с учётом уже идущих загрузок; не хватает — `503 SlowDown` (занято другими загрузками) или `507 InsufficientStorage`. `x-amz-decoded-content-length` учитывается только у aws-chunked-тел. `CopyObject` и `UploadPartCopy` проходят ту же проверку по логическому размеру источника (диапазона) и не принимают источник больше 5 ГиБ (`400 InvalidRequest`).
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
- Веб-интерфейс s3manager (только в `docker-compose.dev.yml`) не умеет менять доступ, не передаёт `Range` (видео не перематывается) и публикуется
  на `127.0.0.1:8081`; он работает с полными правами ключа из `.env`.
- Не публикуйте сервер в интернет без обратного прокси с TLS: HTTPS сервер не терминирует. В `docker-compose.yml` TLS уже настроен через nginx ([operations.md](operations.md#tls-через-nginx)).
