# Этап 14. Стиль кода

Требование владельца: «импорты красивые, код чистый», причём проверяет это сборка, а не ревьюер. Этап не меняет поведение:
все изменения — импорты, комментарии и конфигурация сборки.

## 1. Соглашения

Порядок импортов один во всех файлах (main и test):

1. все импорты, кроме `java.*`/`javax.*` (`jakarta.*`, `org.*`, `com.*` ...), по возрастанию в порядке ASCII;
2. пустая строка, затем `java.*` и `javax.*`;
3. пустая строка, затем `import static` (тоже по порядку).

Правила:

* нет импортов с `*` — ни обычных, ни статических. Исключений нет: даже `org.junit.jupiter.api.Assertions.*` развёрнут
  (45 тестовых файлов; список `assertEquals`, `assertTrue` ... в начале файла короткий и показывает, что тест реально
  использует);
* нет неиспользуемых, повторных и «лишних» (тот же пакет, `java.lang`) импортов, нет `sun.*`;
* в коде нет полных имён классов вместо импортов (`java.util.Arrays.fill` → `import java.util.Arrays`). Полное имя оставляют
  только при настоящем конфликте простых имён. После перехода таких мест в коде нет;
* без табуляций и пробелов в конце строк, файл заканчивается переводом строки;
* в основном коде нет `System.out`/`System.err`/`printStackTrace` (логгер). Исключения по месту, не по файлам: команды Spring
  Shell (`org.example.sectoriadb.shell`) и `Application` (CLI-режим) печатают пользователю, тесты — тоже;
* комментарии и сообщения в коде — на английском (документация в `docs/` — на русском); в комментариях нет истории
  разработки («этап 07 добавил», «doc 09 говорит»): остаётся только причина решения, а ссылки на документацию даны путём
  `docs/architecture/NN-*.md`.

Полный форматтер кода (google-java-format и т. п.) сознательно **не** включён: он переписал бы весь код и сделал бы diff-ы
нечитаемыми. Spotless трогает только блок импортов.

## 2. Инструменты

| Инструмент | Версия | Что делает | Фаза |
|---|---|---|---|
| `spotless-maven-plugin` | 3.10.4 | `removeUnusedImports` + `importOrder` (`,java\|javax,\#`); только импорты | `verify` (`spotless:check`) |
| `maven-checkstyle-plugin` + Checkstyle | 3.6.0 + 14.3.0 | правила ниже, конфигурация `config/checkstyle/checkstyle.xml` | `validate` (`checkstyle:check`, падает при нарушении) |

Правила Checkstyle: `AvoidStarImport`, `RedundantImport`, `UnusedImports` (с `processJavadoc`), `IllegalImport` (`sun`, `com.sun`),
`NewlineAtEndOfFile`, `FileTabCharacter`, `RegexpSingleline` для пробелов в конце строки и для консольного вывода
(`System.out.`, `System.err.`, `printStackTrace(`), `EmptyStatement`, `EqualsHashCode`, `UpperEll`, `ArrayTypeStyle`,
`ModifierOrder`, `RedundantModifier`. Исключения для консольного вывода — в `config/checkstyle/suppressions.xml`.

Сознательно не включены `OneStatementPerLine` и `MultipleVariableDeclarations`: компактный стиль (`int a = 1, b = 2;`, лямбды
из двух операторов в одну строку, поля-счётчики через запятую) встречается в сотнях мест тестов и кода, и его переписывание —
это уже не «чистая база», а переформатирование. Отдельные правила можно добавить позже вместе с переписыванием.

## 3. Команды

```bash
mvn spotless:apply        # привести импорты к порядку и убрать неиспользуемые (правит файлы)
mvn spotless:check        # только проверить (то же самое выполняется в mvn verify)
mvn checkstyle:check      # проверить правила Checkstyle
mvn -B verify             # то, что должен пройти CI: checkstyle, тесты, spotless
```

`spotless:check` привязан к `verify`, поэтому `mvn test` его не запускает; Checkstyle привязан к `validate` и срабатывает
раньше компиляции в любой команде сборки. Плагины скачиваются из Maven Central при первом запуске, офлайн-режим требует
предварительно заполненного `~/.m2`. Настройки JVM для Spotless не нужны (`removeUnusedImports` работает на JDK 21 без
`--add-exports`).

Новые импорты IDE может расставлять как угодно: перед коммитом достаточно `mvn spotless:apply`. Звёздочки и полные имена
`spotless` не раскрывает — их ловит Checkstyle (`AvoidStarImport`), раскрывать нужно вручную или настройкой IDE
(порог «import on demand» — 999).

## 4. git blame

Механическая переделка импортов (2b926b6) затронула сто с лишним файлов. Чтобы `git blame` её пропускал, коммит записан в
`.git-blame-ignore-revs`. Один раз на клон:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

GitHub читает этот файл сам, если он лежит в корне репозитория.

## 5. Что было сделано

* раскрыто 58 импортов с `*` в 54 файлах: 45 `Assertions.*`, 3 `SourceGuard.*` (включая вложенный `SourceGuard.Import`),
  1 `SmallBlobLayout.*`, 4 `org.springframework.web.bind.annotation.*`, 4 `java.util.concurrent.*`, 1 `java.util.*`;
* убрано 6 неиспользуемых импортов (среди них один из того же пакета);
* 454 полных имени в коде заменены импортами в 88 файлах; конфликтов простых имён не нашлось, исключений нет;
* порядок импортов приведён к одному виду во всех файлах;
* из комментариев убраны ссылки на этапы, номера пунктов ревью и «doc NN»; единственный русский javadoc
  (`BlobFile`) переведён;
* исправлен отсутствовавший перевод строки в конце `BlobFile.java`.

## 6. Остающиеся исключения

* консольный вывод в `shell` и `Application` — это интерфейс пользователя, а не диагностика;
* тестовые данные с кириллицей и эмодзи (`S3ApiRegressionTest`, `SigV4UtilsTest`, `ObjectListingTest`) остаются:
  они проверяют кодирование ключей;
* `bench/` (shell и Python) под Spotless/Checkstyle не попадает.
