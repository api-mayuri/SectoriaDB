# Разработка: сборка, тесты, стиль кода

Нужны JDK 21 и Maven. Проект — четыре модуля (`sectoriadb-metastore`, `sectoriadb-engine`, `sectoriadb-core`, `sectoriadb-server`), Spring Boot 3.3,
Spring Shell 3.3. Устройство — [overview.md](../architecture/overview.md).

## Команды

```bash
mvn -B verify                         # то, что должен пройти CI: checkstyle (validate), тесты, spotless (verify)
mvn test                              # все модули
mvn -pl sectoriadb-engine test        # один модуль (metastore, engine, core, server)
mvn -pl sectoriadb-core -am test      # модуль вместе с тем, от чего он зависит
mvn -DskipTests package               # исполняемый jar: sectoriadb-server/target/SectoriaDB-1.0-SNAPSHOT.jar
mvn spotless:apply                    # расставить импорты и убрать неиспользуемые
mvn checkstyle:check                  # правила Checkstyle
```

Плагины скачиваются из Maven Central при первом запуске; офлайн (`-o`) нужен заполненный `~/.m2`.

## Тесты

- Юнит-тесты в каждом модуле; интеграционные тесты `sectoriadb-server` поднимают сервер на случайном порту и во временных каталогах (рабочие данные не трогают);
  порт управления в тестах случайный (`management.server.port=0`).
- Тесты сбоев: имитация потери несброшенных записей (`CrashSimulation` из test-jar движка), внедрение отказов диска в метахранилище (`MetaStoreFaults` из test-jar метахранилища),
  модельные и случайные тесты (групповой коммит, индекс чанков, GC против писателей).
- `ModuleDependencyGuardTest` в каждом модуле читает импорты исходников и проверяет правила зависимостей: `metastore` и `engine` знают только JDK и slf4j; в `core` модуль
  `metastore` импортирует лишь `catalog.metastore`, Spring только в `config`, слои `catalog` < `storage` < `maintenance`; в `server` пакеты `s3.*` образуют слои
  `xml` < `error` < `http` < `access` < `auth` < `api` < `multipart`, а `metastore` сервер не импортирует. Новый пакет требует правила в тесте.
- `DocsConsistencyTest` (`sectoriadb-server`): относительные ссылки markdown разрешаются, метрики `sectoriadb_*` из документации существуют в коде,
  свойства `sectoriadb.*` из [configuration.md](configuration.md) совпадают со свойствами в коде. Добавили свойство или метрику — обновите документацию.
- Нагрузочные, совместимость и отказы — в [docs/testing](../testing/benchmarking.md).

## Стиль кода

Требование: «импорты красивые, код чистый» проверяет сборка, а не ревьюер.

Порядок импортов один во всех файлах (main и test): все, кроме `java.*`/`javax.*`, по алфавиту ASCII; пустая строка; `java.*` и `javax.*`; пустая строка; `import static`.
Правила: нет импортов с `*`, неиспользуемых и повторных; нет полных имён классов вместо импортов (кроме настоящего конфликта имён); без табуляций и пробелов в конце строки;
файл заканчивается переводом строки; в основном коде нет `System.out`/`System.err`/`printStackTrace` (логгер; исключения — пакет `shell` и `Application`, они пишут пользователю);
комментарии и сообщения в коде — на английском (документация в `docs/` — на русском), без истории разработки («этап 07 добавил»), ссылки на документацию — путём `docs/architecture/<имя>.md`.

| Инструмент | Что делает | Фаза |
|---|---|---|
| `spotless-maven-plugin` 3.10.4 | `removeUnusedImports` + `importOrder` (`,java\|javax,\#`); только импорты, форматтер кода сознательно не включён | `verify` |
| `maven-checkstyle-plugin` 3.6.0 + Checkstyle 14.3.0 | `AvoidStarImport`, `RedundantImport`, `UnusedImports`, `IllegalImport`, `NewlineAtEndOfFile`, `FileTabCharacter`, пробелы в конце строки, консольный вывод, `EmptyStatement`, `EqualsHashCode`, `UpperEll`, `ArrayTypeStyle`, `ModifierOrder`, `RedundantModifier`; конфигурация `config/checkstyle/` | `validate` |

Звёздочки и полные имена Spotless не раскрывает — их ловит Checkstyle; в IDE выставьте порог «import on demand» 999.
Механическая переделка импортов записана в `.git-blame-ignore-revs`; один раз на клон: `git config blame.ignoreRevsFile .git-blame-ignore-revs`.
