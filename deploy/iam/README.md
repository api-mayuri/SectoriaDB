# Примеры IAM-политик

Готовые политики для команды `key-policy` (см. docs/guide/admin-shell.md). Файлы написаны для бакета `example-bucket`:
перед подключением подставьте своё имя.

    sed 's/example-bucket/my-bucket/g' deploy/iam/read-only.json > /tmp/policy.json
    java -jar sectoriadb.jar key-policy --id ACCESS_KEY_ID --file /tmp/policy.json

| Файл | Что можно ключу |
|---|---|
| `read-only.json` | смотреть список и читать объекты одного бакета |
| `read-write.json` | список, чтение, запись и удаление в одном бакете |
| `backup-writer.json` | только добавлять объекты в один бакет: без чтения и без удаления (перезапись существующего ключа остаётся возможной, версионирования нет) |

В примерах нет `s3:PutObjectAcl`, `s3:PutBucketAcl`, `s3:PutBucketPolicy`: ключ с `read-write.json` не может открыть объекты анонимам.
Это проверяется и для заголовков: `PUT` / `CopyObject` / `CreateMultipartUpload` с `x-amz-acl` (кроме `private`) или `x-amz-grant-*` получают `403 AccessDenied`,
пока в политике нет `s3:PutObjectAcl` (для `CreateBucket` — `s3:PutBucketAcl`).
