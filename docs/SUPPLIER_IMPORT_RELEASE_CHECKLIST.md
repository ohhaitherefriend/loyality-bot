# Supplier Import Automation — Release Checklist (обновлено после третьего раунда — 6 bug fixes)

Итог финальной ревизии (`prompts/10-final-review.md`) поверх end-to-end supplier-import pipeline
(Prompt 01-09), обновлён после второго hardening-раунда ("Stage 1-10 automatic supplier-import
hardening": `SupplierSource` PATCH/graduate, `NEEDS_ATTENTION` approve, FULL snapshot scope fix,
large-catalog matching + brand aliases, DeepSeek hardening, Postgres/Flyway, role-aware auth,
security findings closure, CI/lint, production ops — `docs/DECISIONS.md` ADR-011…021), и **снова
обновлён** после третьего раунда — шесть существенных, независимо воспроизведённых пользователем
багов в областях, ранее объявленных "resolved" вторым раундом (FULL snapshot, article matching,
candidate search limit, autoApply gates, CloudPayments/billing, CI branch — `docs/DECISIONS.md`
ADR-022…027). Это операционный чеклист для человека, включающего автоматизацию для реального
поставщика/окружения — не описание архитектуры (см. `docs/ARCHITECTURE.md`) и не журнал решений
(см. `docs/DECISIONS.md`).

## 1. Build/test gate (обязательно перед релизом)

Все команды выполняются из корня репозитория.

| Проверка | Команда | Ожидаемый результат (на момент этого прогона, 2026-09-10) |
| --- | --- | --- |
| Backend компиляция | `./mvnw -q -o compile` | без ошибок |
| Backend unit+`@DataJpaTest`+Testcontainers suite | `./mvnw -o test` | `Tests run: 337, Failures: 0, Errors: 1` (1 error = `FlywayPostgresSchemaTest`, требует локальный Docker — pre-existing environment limitation, не регрессия; на CI runner'е с Docker ожидается `Errors: 0`) |
| Backend package | `./mvnw -o package -DskipTests` | success |
| Frontend lint | `npm run lint` (в `admin-panel/`) | `0 errors` (несколько pre-existing warnings, не блокирующие) |
| Frontend build | `npm run build` (в `admin-panel/`) | success (bundle warning про chunk size — известный, не блокирующий) |
| Frontend tests | `npx vitest run` (в `admin-panel/`) | `Test Files 6 passed (6)`, `Tests 21 passed (21)` |
| CI (автоматически на push/PR) | `.github/workflows/backend.yml` + `.github/workflows/frontend.yml` | те же команды выше, теперь на `push`/`pull_request` к `master` (исправлено в раунде 3, ADR-027 — ранее ошибочно указывало `main`, из-за чего CI никогда не запускался на push) |

Нет отдельного E2E-suite вне `mvn test` — три сквозных сценария (`SupplierImportEndToEndTest`,
Prompt 09/ADR-009) запускаются в составе обычного backend suite, не отдельной командой.

Если любая из этих проверок красная — релиз этой автоматизации **не может** считаться готовым,
независимо от того, что ниже.

## 2. Findings этой ревизии — что исправлено

Полный разбор с точными файлами и severity — в транскрипте этого prompt; здесь только сводка
исправленного (см. ADR-010 в `docs/DECISIONS.md` за деталями реализации каждого пункта).

### Critical

- `ImportBatchApplyWriter` не обновлял `SupplierOffer.supplierSource` при повторном апдейте offer —
  offer мог остаться привязанным к устаревшему `SupplierSource`, из-за чего будущий FULL apply из
  правильного scope не видел его через join и не деактивировал/не реактивировал корректно.
- Unconditional `finalizeFailed`/`finalizeQuarantine` во всех пяти batch writer'ах могли перезаписать
  уже терминальный статус batch (`APPLIED`/`FAILED`/...) обратно на `FAILED`/`QUARANTINED` при
  проигранной race — заменено на условный `WHERE status = :expectedStatus` UPDATE во всех пяти
  writer'ах (`ImportBatchRepository.finalizeFailedFrom`/`finalizeQuarantineFrom`).
- Claim lease (`ImportJobClaimService`) никогда не продлевался во время обработки — долгий batch/
  mailbox poll мог пережить свой lease, и вторая реплика легитимно забирала тот же claim
  (настоящий concurrent double-processing, не гипотетический). Добавлены `ActiveClaimRegistry` +
  `ClaimHeartbeatSweeper` (`@Scheduled`, продлевает лизы всех активных claims этой JVM).
- `DataIntegrityViolationException`-recovery в `ImportJobClaimService.tryClaim`/
  `ImportFileBatchWriter.getOrCreate` повторно использовал уже aborted PostgreSQL transaction для
  retry-запроса (работало на H2, ломалось на реальном Postgres) — insert выделен в отдельный
  transactional bean (`ImportJobClaimInsertWriter`/`ImportFileBatchInsertWriter`), так что
  проигранная unique-constraint race откатывает только свою транзакцию, а retry-lookup идёт в
  свежей.

### High

- Volume unit conversion не применялся — `0.5 л` и `500 мл` считались разными объёмами
  (`CriticalAttributeConflictChecker` мог ложно посчитать это конфликтом, `CandidateScorer` — не
  засчитать совпадение). `RowAttributeNormalizer` теперь конвертирует к базовой единице (л → мл,
  кг/мг → г) до сравнения.
- Одно повреждённое (malformed MIME) письмо в mailbox навсегда стопорило весь mailbox — курсор не
  продвигался за него, и то же письмо переопрашивалось и вновь падало на каждом следующем poll.
  `ImapMailboxClient`/`MailboxPollingService` теперь треируют такое сообщение как «без вложений» и
  продвигаются дальше.
- Row-count-collapse guard (`BatchApplyGuardEvaluator`) сравнивал текущие appliable-строки с
  `validRows` предыдущего batch (метрика с этапа парсинга) вместо реального числа офферов,
  примененных предыдущим `APPLIED` batch — метрики разных смысловых величин сравнивались напрямую,
  давая ложные срабатывания/пропуски guard'а. Исправлено на `offersAddedCount + offersUpdatedCount`.

### Medium (безопасные, исправлены в scope)

- Corrupted `normalizedData`/`candidateSearchResult` JSON в строке роняли весь batch на
  `ImportBatchMatchingService.processRow` — теперь такая строка уходит в `NEEDS_REVIEW`, остальной
  batch продолжает обрабатываться.
- Operator queue endpoints (`ImportOperationsController`) не ограничивали `page`/`size` — теперь
  clamp'ятся до безопасных границ вместо `IllegalArgumentException`/unbounded query.
- Не были сконфигурированы `spring.servlet.multipart.max-file-size`/`max-request-size` — большие
  manual-upload файлы могли получать неожиданную ошибку до собственной валидации размера сервисом;
  добавлены явные лимиты (40MB/45MB) выше `SupplierImportProperties.Storage.maxFileSizeBytes`.
- N+1: `SimpleProductCandidateFetcher` выполнял идентичный запрос каталога на каждую строку batch —
  добавлено кэширование на время одной `normalizeBatch()`, с явным сбросом кэша перед началом
  каждого batch (`ProductCandidateFetcher.invalidateForNewBatch`), чтобы каждый batch видел свежий
  каталог (в т.ч. в тестах, где singleton bean переживает несколько тестовых методов).
- `NEW_PRODUCT` без имени/бренда создавал товар с синтетическим именем `"Import row " + id` —
  теперь это `IllegalStateException` (должно быть недостижимо по существующим gate'ам; защита от
  того, чтобы будущий manual review action тихо опубликовал товар с мусорным именем).
- Mailbox cursor advance был безусловным UPDATE/INSERT — конкурентная реплика с истёкшим, но ещё не
  освобождённым lease могла откатить курсор назад поверх более свежего значения. Теперь курсор
  продвигается только если это настоящий resync (сменился `uidValidity`) или новый `lastSeenUid` не
  меньше уже сохранённого (`MailboxCursorRepository.advanceCursorIfNotBehind`).

### Low

- `CatalogAvailabilityService.recompute` не сбрасывал `Product.salePrice` при потере последнего
  active offer — товар становился невидимым, но хранил устаревшую цену; теперь `salePrice` тоже
  очищается.
- ~~`supplier-import.ai.deepseek.timeout-ms` — не подключён к реальному таймауту~~ — **Resolved
  (Stage 5/ADR-011):** dedicated `DeepSeekClientConfig`/`deepSeekRestTemplate` bean, separate from
  the shared Telegram/payments/image-search `RestTemplate`, with its own configurable connect/read
  timeouts.
- `reference/deepseek-layout-response.schema.json` описывал устаревшую, отличающуюся от реального
  runtime-контракта форму ответа — синхронизирован с фактической `src/main/resources/supplier-import/
  layout-rule.schema.json`.

### Не найдено (проверено явно)

- Cross-tenant access: все supplier-import repository-методы и оба контроллера
  (`ImportOperationsController`/`SupplierImportAdminController`) уже scoped по `shopId`/
  `ShopAccessService.hasAccess(...)` — не найдено ни одного endpoint'а без этой проверки.
- Secret leakage: `MailboxConnection.encryptedSecret` никогда не сериализуется в DTO/лог; DeepSeek
  API key читается только из конфигурации, не логируется.
- Public/admin DTO leaks: storefront-запросы (`StorefrontService`) не пересекаются с
  supplier-import DTO/`supplierPrice`/AI decision данными — раздельные слои не были нарушены ни
  одним prompt'ом.
- Invented AI IDs / prompt injection: `CatalogMatchResponseValidator`/`LayoutRuleValidator` уже
  структурно отклоняют любой `candidate_id`, не входящий в переданный список, и любой JSON,
  отклоняющийся от closed-vocabulary schema; system prompts (ADR-009) явно помечают cell-контент
  как untrusted data.

## 2b. Раунд 3 (2026-09-10) — шесть пользователем воспроизведённых багов в "resolved" областях

В отличие от §2 (внутренняя ревизия), эти шесть багов были обнаружены и воспроизведены пользователем
**после** того как раунд 2 объявил соответствующие области закрытыми — прямое доказательство, что
"нет находок при внутренней ревизии" не равно "багов нет". Полные детали, включая тест, точно
воспроизводящий каждый репорт — `docs/DECISIONS.md` ADR-022…027; `docs/STATE.md` → «Third round» для
таблицы root cause/fix. Кратко:

1. **FULL-импорт скрывал товар с невалидной строкой** — строка становится `INVALID` до этапа
   apply, но FULL-деактивация не отличала «строки для этого товара реально нет в файле» от «строка
   есть, но не прошла обработку» → товар пропадал с витрины. **Исправлено (ADR-022).**
2. **Совпадающие артикулы разных поставщиков склеивались как `EXACT` match** — точное сопоставление
   по артикулу было scoped только по `shopId`, не по поставщику. **Исправлено (ADR-023).**
3. **Лимит кандидатов 300 был исправлен частично** — для бренда, уже имеющего 300+ товаров,
   поиск по имени вообще не выполнялся (бренд-запрос сам заполнял лимит). **Исправлено (ADR-024).**
4. **Проверки автоматики обходились простым PATCH** — `PATCH` мог включить `autoApply` в обход
   всех проверок `/graduate`; пустой список доверенных отправителей допускался даже при
   `autoApply=true`. **Исправлено (ADR-025).**
5. **Платёжная защита не была полной** — отсутствие секрета CloudPayments приводило к
   безусловному пропуску проверки подписи; `activate-stub`/`extend-trial` были доступны любому
   участнику магазина, не только владельцу. **Исправлено (ADR-026).**
6. **CI не запускался на реальной рабочей ветке** — оба workflow триггерились на `main`, а
   реальная ветка — `master`; `npm audit` никогда не мог провалить сборку. **Исправлено (ADR-027).**

Все шесть покрыты новым/обновлённым regression-тестом, точно воспроизводящим репорт (не просто
проверкой, что код компилируется). Build/test gate §1 подтверждает отсутствие регрессий в остальном
пайплайне после этих изменений.

## 3. Automation rate — как измерить перед приёмкой

`docs/ARCHITECTURE.md` §15 называет **automation rate** главным продуктовым показателем: доля
файлов/строк, обработанных от письма до обновления каталога без участия человека. В этом релизе
нет отдельного UI-виджета «automation rate» — считать по существующим данным:

1. **По batches** (`ImportBatchRepository`/`import_batches`):
   `automationRate = count(status IN (AUTO_APPROVED, APPLIED)) / count(status NOT IN (STORED, PARSING, NORMALIZING, MATCHING, VALIDATING, APPLYING))`
   за окно наблюдения — знаменатель исключает промежуточные, ещё не завершённые статусы.
2. **По строкам** (`ImportRow`/`MatchDecision.decisionType`):
   `rowAutomationRate = count(decisionType IN (EXACT, LEARNED, AI_MATCH, NEW_PRODUCT) AND status = AUTO_APPROVED) / count(status IN (AUTO_APPROVED, NEEDS_REVIEW, INVALID))`.
3. **Через Prometheus** (`/actuator/prometheus`, ADR-009): `supplier_import_batch_validation_total`
   по `decision` (`auto_approved` vs `needs_attention`/`quarantined`) даёт ту же метрику как
   time series, без ручного SQL.

Приёмочный порог **не откалиброван на реальном ассортименте** (см. `docs/STATE.md` → «Known
failures/blockers», thresholds ADR-004/005/006) — перед включением `autoApply=true` для нового
поставщика нужно явно решить целевой automation rate для этого поставщика (рекомендация: не ниже
70-80% строк без ручного review после 2-3 прогонов в shadow mode; конкретное число — бизнес-решение,
не techncial default).

## 4. Shadow-mode acceptance перед `autoApply=true`

`SupplierSource.shadowMode=true` (default при создании нового источника) — pipeline полностью
считает решения (`MatchDecision`, guard evaluation) и пишет их в аудит, но не создаёт/не обновляет
`SupplierOffer`/`Product` (docs/ARCHITECTURE.md §9.4/§14). Обязательная последовательность перед
переключением на `autoApply=true`:

1. **Минимум 2-3 реальных файла от этого поставщика** прошли полный pipeline в shadow mode (не
   синтетические фикстуры) — включая как минимум один файл, отличающийся по объёму строк от
   первого (проверка, что layout rule реально переиспользуется, а не совпало один раз случайно).
2. **Automation rate** (см. §3) посчитан по этим прогонам и явно принят ответственным оператором —
   не «выглядит нормально», а зафиксированное число.
3. **Exception queue** (`ExceptionsQueuePage`/`ImportExceptionQueueService`) вручную просмотрена для
   каждого shadow-прогона — каждая строка/batch в `NEEDS_REVIEW`/`NEEDS_ATTENTION`/`QUARANTINED`
   проверена, что причина остановки понятна и ожидаема (не системная ошибка).
4. **Нулевые false-positive auto-match** — вручную выборочно проверено, что ни один `AUTO_APPROVED`/
   `EXACT`/`LEARNED` matched товар не является ошибочным сопоставлением (особенно: разный объём/
   концентрация/оттенок под похожим именем — ровно та категория, которую
   `CriticalAttributeConflictChecker` должен блокировать; shadow mode — последняя проверка перед
   тем, как это стало бы реальным изменением каталога).
5. **Guard thresholds** (`SupplierImportProperties.Reconciliation`, ADR-006) синхронизированы с
   реальным профилем волатильности этого поставщика — если у поставщика прайс обычно скачет на
   15-20% строк, `priceDeltaMaxAnomalousRowRatio` по умолчанию может квалифицировать нормальный
   прогон как anomaly; свериться перед первым `autoApply=true` прогоном, не после первого
   неожиданного `QUARANTINED`.
6. Только после явного прохождения пп. 1-5 — оператор переключает `SupplierSource.shadowMode=false`
   (или ставит `autoApply=true`, если это раздельные флаги в момент релиза — см. текущую схему
   `SupplierSource` перед переключением).

## 5. Безопасное FULL snapshot reconciliation — чеклист перед первым реальным FULL

`SnapshotMode.FULL` деактивирует (снимает с продажи) любой offer этого supplier+scope, не увиденный
в текущем batch (docs/ARCHITECTURE.md §14.7, D-012). Это единственная операция в pipeline, которая
может **массово** убрать товары со storefront за одну транзакцию — обязательно пройти перед первым
реальным FULL snapshot для нового источника:

1. **`snapshotScope` подтверждён правильным** для этого источника — деактивация scoped по
   `supplier + SupplierSource.snapshotScope` (не по `supplierSourceId` напрямую), см.
   `ImportBatchApplyWriter`/`findStaleActiveOffersInScope`. Два разных файла/листа одного поставщика
   с разным `snapshotScope` не должны друг друга деактивировать; проверить konfig перед первым
   боевым прогоном, не полагаться на предположение.
2. **Row-count-collapse guard** (`rowCountCollapseMinRatio`, default см. `application.yml`) —
   первый реальный FULL batch сравнивается с предыдущим `APPLIED` batch того же источника; если это
   первый FULL вообще (нет предыдущего `APPLIED`), guard не срабатывает (нет базы для сравнения) —
   значит первый FULL batch для нового источника **не защищён** этим guard'ом. Компенсировать
   вручную: свериться, что число строк в файле разумно совпадает с ожидаемым размером ассортимента
   поставщика, прежде чем разрешать первый реальный `autoApply=true` FULL apply.
3. **Duplicate-identifier explosion guard** и **anomalous price delta guard** (те же 4 guard'а,
   `BatchApplyGuardEvaluator`) — прогнать первый реальный FULL файл через shadow mode (см. §4) и
   явно посмотреть на посчитанные guard-метрики в `BatchDetailPage`, а не просто «guard не сработал,
   значит ок» — guard threshold defaults не откалиброваны (см. §3), могут быть слишком мягкими для
   конкретного поставщика.
4. **План отката**: если FULL apply деактивировал больше товаров, чем ожидалось, — offer не
   удаляется физически (D-005), только `active=false`/`deactivatedAt`; следующий корректный
   FULL/DELTA batch с тем же товаром автоматически реактивирует offer (docs/ARCHITECTURE.md §14.8) —
   ручного восстановления из backup для этого конкретного сценария не требуется, только повторный
   корректный импорт. Backup (docs/ARCHITECTURE.md §22) остаётся нужен для восстановления после
   потери данных в целом, не как основной план отката для ошибочного FULL apply.
5. **manualHidden override** — если оператор ранее скрыл конкретные товары вручную
   (`ProductVisibilityOverrideService.setManualHidden`), подтвердить, что после первого FULL apply
   они остались скрытыми (`manual_hidden` имеет приоритет над automatic sync, docs/ARCHITECTURE.md
   §14.8) — один выборочный ручной прогон перед тем, как доверять этому по умолчанию для конкретного
   магазина.

## 6. Известные ограничения (обновлено после раунда 3 — 6 bug fixes)

Не переоткрывать/не пере-исследовать без нового измеренного повода — уже задокументированы, ссылки
для контекста. **Оговорка после раунда 3**: несколько пунктов ниже, помеченных как "Resolved"
вторым раундом, оказались резолвены не полностью — см. §2b и `docs/DECISIONS.md` ADR-022…027 для
того, что именно было упущено и как исправлено сейчас. Формулировка "Resolved" в этом файле означает
"нет известной открытой проблемы на момент этого ADR", а не гарантию отсутствия багов.

**Закрыто раундом 3** (2026-09-10, не открывать заново без нового измеренного повода):

- ~~FULL-импорт мог скрыть товар, чья строка стала `INVALID`~~ — **Resolved (ADR-022):** raw-identity
  cross-check по всем строкам batch перед деактивацией, независимо от статуса строки.
- ~~Точное сопоставление по артикулу не учитывало поставщика~~ — **Resolved (ADR-023):** проверка
  существующего `SupplierProductLink` на другого поставщика перед авто-матчем.
- ~~Лимит 300 кандидатов блокировал поиск по имени для брендов с 300+ товарами~~ — **Resolved
  (ADR-024):** комбинированный brand+name запрос + безусловный запуск name-widening запроса.
- ~~PATCH мог включить `autoApply` в обход проверок `/graduate`; пустой allowlist разрешён~~ —
  **Resolved (ADR-025):** общий `assertReadyForAutoApply` gate для обоих endpoint'ов + проверка
  непустого `senderAllowlist`.
- ~~CloudPayments HMAC пропускал проверку при отсутствии секрета безусловно; billing bypass
  endpoints доступны любому участнику~~ — **Resolved (ADR-026):** fail-closed в `prod` профиле;
  `OWNER`-only для `activate-stub`/`extend-trial`.
- ~~CI указывал `main`, реальная ветка `master`; `npm audit` не блокировал~~ — **Resolved
  (ADR-027):** `push.branches: [master]`; blocking `--audit-level=critical` gate.

**Закрыто вторым hardening-раундом** (не открывать заново без нового измеренного повода):

- ~~Нет circuit breaker для DeepSeek~~ — **Resolved (Stage 5/ADR-011)**, per-JVM-instance
  `DeepSeekCircuitBreaker` + concurrency limiter; state still not shared across replicas (см. ниже).
- ~~`ddl-auto: update`/Flyway remains unchanged~~ — **Resolved (Stage 6/ADR-012/013):** Flyway
  включён в обоих профилях, `ddl-auto: validate` в prod, baseline cutover на `V23`,
  `FlywayPostgresSchemaTest` против реального Postgres.
- ~~Alert-правила только как рекомендация в тексте~~ — **Resolved (Stage 10/ADR-018):**
  `docs/monitoring/prometheus-alerts.yml`, реальный Prometheus rule-file.
- ~~Backup процедура не автоматизирована~~ — **Resolved (Stage 10/ADR-017):**
  `scripts/backup/pg-backup.sh`/`pg-restore.sh`.
- ~~Нет CI~~ — **Resolved (Stage 9/ADR-021):** `.github/workflows/backend.yml`/`frontend.yml`.
- ~~`ShopAccessService` role-blind~~ — **Resolved (Stage 7/ADR-019):** role-aware
  `AuthorizationService` (`OWNER`/`ADMIN`/`STAFF`), `graduate` (autoApply toggle) requires `OWNER`.
- ~~Multi-replica local file storage invisible across replicas~~ — **Resolved (Stage 10/ADR-014):**
  opt-in S3-compatible `ImportFileStorage` (`SUPPLIER_IMPORT_STORAGE_PROVIDER=s3`).
- ~~Cross-tenant platform-wide admin endpoints / unused HMAC validator / unconditional
  confirm-payment~~ — **Resolved (Stage 8/ADR-020):** `SYSTEM_ADMIN_EMAILS` allowlist, HMAC
  actually enforced, `confirm-payment` gated by `isLiveGatewayConfigured()`.

**Остаются открытыми** (не закрыты вторым hardening-раундом):

- OAuth2 mailbox auth не реализован (только IMAP + app password) — `docs/STATE.md`, ADR-002.
- `pg_trgm` candidate fetcher — extension теперь гарантированно установлен через `V27` (Stage 6), но
  сам fetcher по-прежнему не покрыт integration-тестом против реального объёма продакшн-каталога.
- Fuzzy/AI/guard thresholds — статические config defaults, не откалиброваны на реальном
  ассортименте/волатильности — `docs/STATE.md`, ADR-004/005/006 (см. также §3-5 выше).
- Concurrency (несколько реплик/потоков) не проверена настоящей многопоточной гонкой ни в одном
  prompt — только conditional-`UPDATE`/claim-lease на уровне кода; см. ADR-009 п.7.
- DeepSeek circuit breaker/concurrency limiter — state per-JVM-instance, не shared между репликами
  (ADR-011 п.1) — не изменилось Stage 10.
- Retention (`ImportRetentionJob`, ADR-015) — `fileRetentionDays=180` не откалиброван на реальных
  compliance-требованиях; disabled by default, оператор должен явно решить.
- S3 storage (ADR-014) — нет инструмента миграции уже сохранённых local-файлов при переключении
  provider; нет integration-теста против реального S3/MinIO.
- Correlation ID (ADR-016) — не покрывает `@Scheduled` jobs и исходящие HTTP-вызовы (DeepSeek/
  CloudPayments/Telegram).
- Prometheus alert-правила (ADR-018) требуют отдельно развёрнутого Alertmanager для routing, и
  `AppNotReady` — отдельно развёрнутого `blackbox_exporter`; ни то ни другое не входит в репозиторий.
- 4 из 15 frontend dependency vulnerabilities остаются (требуют breaking upgrade) — Stage 9/ADR-021.

## 7. Sign-off

Перед тем как считать эту автоматизацию готовой к продакшену для конкретного магазина/поставщика:

- [ ] §1 build/test gate — все команды зелёные на актуальном коммите (кроме `FlywayPostgresSchemaTest`
      без локального Docker — на реальном CI runner'е с Docker ожидается 0 ошибок).
- [ ] §2 — прочитан список исправлений (или ADR-010 целиком), нет открытых Critical/High.
- [ ] §2b — прочитан список из шести раунд-3 багов (ADR-022…027); ответственный оператор понимает,
      что "Resolved" во втором раунде для этих же областей оказалось неполным, и относится к текущим
      "Resolved" пометкам как к «нет известной проблемы сейчас», а не как к гарантии.
- [ ] §3 — automation rate измерен и явно принят для этого поставщика.
- [ ] §4 — shadow-mode acceptance пройден по всем пяти пунктам перед `autoApply=true`.
- [ ] §5 — FULL snapshot чеклист пройден перед первым реальным FULL apply для этого источника.
- [ ] §6 — унаследованные ограничения прочитаны, ответственный оператор осознаёт остаточный риск.
