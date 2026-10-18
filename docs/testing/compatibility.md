# Совместимость с S3

Два независимых набора тестов запускаются скриптами из `bench/compat/` против работающего SectoriaDB. Цель не «зелёная
таблица», а поиск настоящих ошибок: у каждого красного теста выяснялась причина, мелкие ошибки исправлены (с регрессионным
тестом), крупные записаны в списки ниже.

## ceph/s3-tests

`bench/compat/s3tests.sh` клонирует `ceph/s3-tests` (коммит `5522d1c`), создаёт virtualenv, генерирует `s3tests.conf` из
шаблона (`s3tests.conf.template`) с **двумя аккаунтами**, `main` и `alt` (в SectoriaDB это два обычных ключа:
`SECTORIADB_S3_CREDENTIALS=AK1:SK1,AK2:SK2`; IAM, STS и SNS нет, поэтому разделы `[iam*]` указывают на те же ключи), и
запускает `pytest` на `s3tests/functional/test_s3.py` и `test_headers.py` с таймаутом 60 с на тест.

Заранее отсеяны по маркерам (`-m`) тесты возможностей, которых в SectoriaDB нет сознательно: шифрование, теги и
жизненный цикл объектов, IAM/STS/SNS, S3 Select, веб-сайты, журналирование, облачные переходы, классы хранения,
checksum-режимы AWS, SigV2. Из 886 собранных тестов запущено **422**; остальные 464 в таблице не участвуют.

**Результат** (20261007, сервер на коммите этой ветки; 3 минуты на сервере после матрицы warp; предыдущие прогоны на сервере с накопленными бакетами и блобами занимали 15-22 минуты, причину не выясняли):

| | Тестов | Доля |
|---|---:|---:|
| Пройдено | 205 | 49 % |
| Не пройдено | 217 | |
| Запущено | 422 | |

Основные причины отказов (непройденные тесты сгруппированы по теме; группы не пересекаются):

| Группа | Тестов | Причина |
|---|---:|---|
| versioning | 37 | версионирования нет (`PutBucketVersioning` отвечает 501) |
| object lock / retention / legal hold | 38 | блокировок объектов нет (501) |
| ACL, grants, access by canned ACL (no owner model) | 41 | нет модели владельца: канонические ACL и гранты ведут себя не как в S3, любой ключ видит всё |
| POST Object (browser form) | 25 | загрузка HTML-формой (`POST /bucket` с политикой) не реализована |
| bucket policy, public access block | 16 | условия политик (`NotPrincipal`, `IfExists`, тенанты), `PutPublicAccessBlock`, `policyStatus` не реализованы |
| CORS | 12 | `PutBucketCors` не реализован (501) |
| multipart details | 13 | идемпотентность `Complete`, `partNumber`, `EntityTooSmall`, `Initiator`, копирование с диапазоном |
| presigned URL / x-amz-expires / expired | 7 | проверка диапазона `X-Amz-Expires`, коды отказов |
| ownership controls | 7 | `BucketOwnershipControls` не реализованы (501) |
| conditional writes (If-Match/If-None-Match on PUT/Copy) | 5 | условные PUT и Copy не реализованы |
| GetObjectAttributes / torrent | 3 | не реализованы (501) |
| other | 13 | разное, см. ниже |

Остальные (13 «прочих») это: `Expires` не сохраняется и не возвращается; не-ASCII значения пользовательских метаданных дают
`SignatureDoesNotMatch` (заголовок канонизируется не по тем байтам); `Content-Encoding: aws-chunked` в списке кодировок;
`GetBucketLocation` для `us-east-1` отдаёт пустое значение (так и должно быть для AWS, тест рассчитан на RGW);
`ListBuckets` без постраничности (`max-buckets`, `continuation-token`) и с чужими бакетами других тестов; `100-continue`
получает `100` раньше решения об аутентификации; `ListObjects` с «нечитаемым» префиксом.

### Исправлено по итогам s3-tests (с регрессионными тестами в `S3ApiRegressionTest`)

* Приёмка и очистка: `ListObjectVersions` для бакета без версионирования возвращал пустой листинг, поэтому «очистка»
  бакета в SDK и в самих тестах не находила объекты, а все последующие тесты падали в `BucketNotEmpty`. Теперь объекты
  перечисляются как версия `null` (`GET /bucket?versions`, с `key-marker` и разделителем).
* `DeleteObjects`: ключ из одних пробелов (`" "`, `"_ "`) обрезался до пустого и не удалялся; `<Quiet>true</Quiet>`
  игнорировался; больше 1000 ключей принимались.
* Листинг: `max-keys=0` возвращал всё (должен пустую страницу); в ответ V1 не попадали `Marker` и `Owner`, в ответ V2
  `StartAfter`; не было `encoding-type=url`; недопустимые `max-keys`, `list-type`, `encoding-type` давали 500.
* Ошибки Spring MVC (405, 400 на неверном параметре, 415, 404 на неизвестный путь) превращались в `500 InternalError`
  или в JSON страницу Spring; теперь это S3 XML: `MethodNotAllowed`, `InvalidArgument`, `UnsupportedMediaType`, `NotFound`.
  Добавлен `S3ErrorController` (путь `/.s3-error`, не `/error`: это допустимое имя бакета).
* Имена бакетов: код `InvalidBucketName` вместо `InvalidArgument`.
* Неподдерживаемые подресурсы `GET ?object-lock|?ownershipControls|?website|?logging|?notification|?policyStatus` у
  бакета и `?attributes|?retention|?legal-hold|?torrent` у объекта отвечали 200 листингом бакета или **байтами объекта**;
  теперь `501 NotImplemented`.
* Пустой или неразборчивый XML в `CompleteMultipartUpload` давал 500, теперь `400 MalformedXML`.

### Опасные ошибки, найденные mint и исправленные

* **`DELETE /bucket?tagging` (а также `?lifecycle`, `?cors`, `?encryption`, ...) удалял пустой бакет**, `DELETE
  /bucket/key?retention` удалял объект, `PUT /bucket/key?retention` или `?uploadId=...` без `partNumber` перезаписывал
  объект телом запроса. Запросы с подресурсом, который никто не обрабатывает, теперь отвечают `501`. Разрешены `x-id`
  (его добавляют AWS SDK для JavaScript и Go ко всем запросам) и параметры подписи presigned-URL (`X-Amz-*`).
* `GET`/`HEAD` с `?partNumber=N` для multipart-объекта возвращали **весь** объект с кодом 200 (warp в режиме `multipart`
  принимал его за часть: 204 «unexpected download size»). Манифест не хранит границы частей, поэтому теперь `501`; для
  обычного объекта допустим только `partNumber=1`, иначе `416 InvalidPartNumber`.
* Multipart-загрузка теряла пользовательские метаданные (`x-amz-meta-*`) и `Cache-Control`, `Content-Disposition`,
  `Content-Encoding`, `Content-Language`, заданные при `CreateMultipartUpload` (теперь сохраняются и применяются при
  завершении). Находка minio-py `test_put_object` (объекты больше 5 МиБ).

### Известные расхождения с S3

| Расхождение | Свидетельство |
|---|---|
| Нет модели владельца и IAM: `ListBuckets` и доступ видят все ключи; канонические ACL (`authenticated-read`, `bucket-owner-*`) сводятся к `private`; гранты — только `AllUsers` | 41 тест ACL s3-tests |
| Нет версионирования, блокировок объектов, CORS, public access block, ownership controls, `GetObjectAttributes`, POST-формы; условные записи только `If-None-Match: *` и одиночный `If-Match` | 37 + 38 + 12 + 16 + 7 + 3 + 25 тестов |
| `Initiator` / `Owner` отсутствуют в `ListMultipartUploads` и `ListParts`; метаданные больше 2 КБ принимаются | `test_list_multipart_upload_owner` |
| `x-amz-storage-class` и `Expires` не сохраняются и не возвращаются | mint: `awscli`, `mc` |
| `GET ?partNumber` за пределами объекта — `416 InvalidPartNumber` (как AWS), Ceph ждёт `400 InvalidPart` | 3 теста `*_get_part` |
| Проверка диапазона `X-Amz-Expires` и коды ошибок presigned-ссылок отличаются от S3 | 7 тестов |

Исправлено и больше не расхождение: повторный `CompleteMultipartUpload` идемпотентен (результат хранится 24 часа); `GET`/`HEAD ?partNumber` работают для multipart-объектов (`206`);
`EntityTooSmall` проверяется при `Complete` (`sectoriadb.multipart.min-part-size`); условный `PUT`; не-ASCII метаданные подписываются верно.

## minio/mint

`bench/compat/mint.sh` запускает образ `minio/mint:edge` (по digest) в режиме `core` по одному набору за раз с
таймаутом, против `sectoriadb:8080` внутри сети стенда. Сводка `bench/compat/work/mint-*/summary.txt`
строится из `log.json` каждого набора. Mint у части инструментов (awscli, mc, aws-sdk-go-v2) прекращает набор на первой
ошибке, поэтому числа PASS это нижняя граница.

| Набор | PASS | FAIL | NA |
|---|---:|---:|---:|
| aws-sdk-go-v2 | 3 | 1 | 0 |
| aws-sdk-java-v2 | 0 | 0 | 0 |
| aws-sdk-php | 10 | 1 | 0 |
| aws-sdk-ruby | 12 | 1 | 0 |
| awscli | 8 | 1 | 0 |
| mc | 8 | 1 | 0 |
| minio-go | 1 | 1 | 0 |
| minio-java | 31 | 3 | 28 |
| minio-py | 10 | 1 | 2 |
| s3cmd | 8 | 0 | 0 |
| minio-js | таймаут 7 мин (в предыдущем прогоне) | | |
| **Итого** | **91** | **10** | **30** |

Разбор отказов:

* **`minio-go`: `MakeBucket ... Access Denied`**: тест `testFunctionalV2` использует подпись SigV2, которой в SectoriaDB нет
  (только SigV4); отказ ожидаемый. Остальные тесты набора проходят.
* **awscli, mc: класс хранения** (`StorageClass was not applied`): `x-amz-storage-class` принимается, но не возвращается.
* **aws-sdk-go-v2 `PresignedPut`**: SDK сообщает `unmarshalling xml failed: EOF`; присланный через boto3 presigned PUT
  работает, причина в особенностях Go SDK не выяснена.
* **aws-sdk-ruby `presignedPost`**, **minio-java `getPresignedPostFormData`**: POST-форма с подписью не поддерживается
  (25 тестов s3-tests).
* **aws-sdk-php `uploadPart`**: тест в конце вызывает `AbortMultipartUpload` для загрузки, которой уже нет, и получает
  `404 NoSuchUpload` (S3 для уже завершённой или прерванной загрузки тоже отвечает `NoSuchUpload`, поэтому, вероятно, дело в
  порядке вызовов теста; не расследовано).
* **minio-java `listenBucketNotification`, `selectObjectContent`** и 28 пропущенных (NA) тестов: уведомления, S3 Select,
  шифрование, теги, блокировки, версионирование (нет в SectoriaDB).
* **minio-py `test_select_object_content`**: S3 Select.
* **minio-js**: набор не завершился за 7 минут (таймаут; зависание не расследовано).
* **aws-sdk-java-v2** выполнился без записей в журнале: набор не сообщил ни одного результата (не расследовано).

