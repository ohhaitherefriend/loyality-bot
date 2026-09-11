# Supplier Import Automation — Release Checklist (обновлено после седьмого раунда — ADR-031, closing round-6 gaps: spelling-variant fingerprint miss, ambiguous-duplicate collapse, stale-normalization-version rows, and a real concurrent-creation race)

Итог финальной ревизии (`prompts/10-final-review.md`) поверх end-to-end supplier-import pipeline
(Prompt 01-09), обновлён после второго hardening-раунда ("Stage 1-10 automatic supplier-import
hardening": `SupplierSource` PATCH/graduate, `NEEDS_ATTENTION` approve, FULL snapshot scope fix,
large-catalog matching + brand aliases, DeepSeek hardening, Postgres/Flyway, role-aware auth,
security findings closure, CI/lint, production ops — `docs/DECISIONS.md` ADR-011…021), обновлён
после третьего раунда — шесть существенных, независимо воспроизведённых пользователем багов в
областях, ранее объявленных "resolved" вторым раундом (FULL snapshot, article matching, candidate
search limit, autoApply gates, CloudPayments/billing, CI branch — `docs/DECISIONS.md` ADR-022…027),
**снова обновлён** после четвёртого раунда — тот же пользователь воспроизвёл ещё четыре гэпа,
каждый из которых уже был явно назван как "Осознанное ограничение" в ADR-023/024/025/026 раунда 3
(`docs/DECISIONS.md` ADR-028), **обновлён в третий раз** после пятого раунда — тот же пользователь
подтвердил два из четырёх раунд-4 фиксов, но воспроизвёл ещё более точными репро, что фиксы для
article matching и candidate search (раунд 4, пп. 1-2) сами были неполными (`docs/DECISIONS.md`
ADR-029), **обновлён в четвёртый раз** после шестого раунда — новая, более точная вариация того же
класса бага в alias-идентичности плюс три архитектурных гэпа (search-completeness diagnostics,
apply-time duplicate guard, per-batch кэш) (`docs/DECISIONS.md` ADR-030), и **обновлён в пятый раз**
после седьмого раунда — четыре ещё более точные вариации того же класса бага (spelling-variant
fingerprint miss, ambiguous-duplicate collapse в "safe to create", stale-normalization-version
строки — теперь также для уже-`MATCHED` строк, реальная гонка параллельного создания товара) плюс
транзакционный баг в фоновом backfill job и Jackson-регрессия, найденные при реализации фикса, не
пользователем (`docs/DECISIONS.md` ADR-031). Это операционный чеклист для человека, включающего
автоматизацию для реального поставщика/окружения — не описание архитектуры (см.
`docs/ARCHITECTURE.md`) и не журнал решений (см. `docs/DECISIONS.md`).

## 1. Build/test gate (обязательно перед релизом)

Все команды выполняются из корня репозитория.

| Проверка | Команда | Ожидаемый результат (на момент этого прогона, 2026-09-11, раунд 7) |
| --- | --- | --- |
| Backend компиляция | `./mvnw -q -o compile` | без ошибок |
| Backend unit+`@DataJpaTest`+Testcontainers suite | `./mvnw -o clean verify` | `Tests run: 374, Failures: 0, Errors: 2` (2 ошибки = `FlywayPostgresSchemaTest` + новый `ProductCreationConcurrencyPostgresTest`, оба требуют локальный Docker — environment limitation, не регрессия; на CI runner'е с Docker ожидается `Errors: 0`, и именно там `ProductCreationConcurrencyPostgresTest` впервые реально подтвердит ADR-031 Section 4 cross-instance lock, а не только code review) |
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

## 2c. Раунд 4 (2026-09-10) — закрытие осознанных ограничений раунда 3

Раунд 3 (§2b) закрыл шесть багов, но четыре из шести фиксов (2, 3, 4, 5) сами явно перечисляли
оставшийся гэп в разделе «Осознанные ограничения» соответствующего ADR — и тот же пользователь
независимо воспроизвёл именно эти гэпы новым репортом. Полные детали — `docs/DECISIONS.md` ADR-028;
`docs/STATE.md` → «Fourth round» для таблицы root cause/fix. Кратко:

1. **Артикул всё ещё мог склеить товары разных брендов, если у существующего товара ещё нет
   `SupplierProductLink`** — проверка ADR-023 защищала только от привязки к ДРУГОМУ поставщику, но
   ничего не проверяла для товара без привязки вообще (Dior совпал с никогда не привязанным Chanel).
   **Исправлено (ADR-028):** обязательное подтверждение бренда (точное совпадение или алиас/
   транслитерация через `BrandAliasResolver`) — отсутствие или несовпадение бренда теперь тоже
   блокирует авто-матч.
2. **Лимит кандидатов всё ещё пропускал конкретный товар в бренде с 300+ товарами** — «самое длинное
   слово» оказывалось самим брендом (например, "chanel" среди 305 товаров Chanel), и результаты не
   ранжировались перед обрезкой по лимиту. **Исправлено (ADR-028):** поиск по КАЖДОМУ значимому
   токену (не только самому длинному), результаты ранжируются по накопленной релевантности перед
   обрезкой до `candidateFetchLimit`.
3. **У уже включённого `autoApply` источника можно было обнулить `senderAllowlist` без повторной
   проверки** — гейт ADR-025 срабатывал только при переключении `autoApply` false→true, а не при
   каждом изменении. **Исправлено (ADR-028):** постоянная проверка в `validateInvariants` — любое
   результирующее состояние с `autoApply=true` и пустым `senderAllowlist` теперь отклоняется, вне
   зависимости от того, что именно было изменено в запросе.
4. **Платёжная защита всё ещё была неполной** — `confirm-payment` активировал подписку без оплаты
   при отсутствующем секрете даже в production; `activate-stub`/`extend-trial` были ограничены до
   `OWNER` — роли клиента платформы, а не оператора платформы. **Исправлено (ADR-028):** новый
   `CloudPaymentsService#requiresWebhookConfirmation()` блокирует `confirm-payment` в `prod`-профиле
   независимо от того, настроен ли секрет; `activate-stub`/`extend-trial` теперь требуют
   `AuthorizationService#isSystemAdmin` (тот же `SYSTEM_ADMIN_EMAILS`-механизм, что и у
   platform-wide endpoints в `AdminApiController`) вместо `MemberRole.OWNER`.

Все четыре покрыты новым regression-тестом, точно воспроизводящим новый репорт. Build/test gate §1
подтверждает отсутствие регрессий в остальном пайплайне после этих изменений (frontend не менялся
в этом раунде).

## 2d. Раунд 5 (2026-09-11) — раунд-4 фиксы для article matching и candidate search сами оказались неполными

Тот же пользователь проверил коммит `18cd492` (раунд 4) и подтвердил пп. 3-4 раунда 4
(senderAllowlist, billing) как реально исправленные, но воспроизвёл ещё более точными репро, что пп.
1-2 раунда 4 (article matching, candidate search) сами были неполными — это уже ТРЕТИЙ раз подряд,
когда фикс для этих двух конкретных областей объявлялся "Resolved" и оказывался неполным при более
точной проверке. Полные детали — `docs/DECISIONS.md` ADR-029; `docs/STATE.md` → «Fifth round» для
таблицы root cause/fix. Кратко:

1. **Артикул всё ещё мог склеить два РАЗНЫХ товара ОДНОГО бренда** — проверка бренда (раунд 4)
   защищает только от коллизии МЕЖДУ разными брендами; она ничего не даёт, когда у товара того же
   бренда просто другая линейка/название ("Chanel Coco Mademoiselle 100 ml" совпал с "Chanel No 5
   100 ml" как `EXACT`, потому что `CriticalAttributeConflictChecker` не сравнивает текст
   названия/линейки — только объём/концентрацию/оттенок/тестер/набор). **Исправлено (ADR-029):**
   `resolveViaExactSupplierArticle` теперь ТАКЖЕ требует точного совпадения вычисленного
   `fingerprint` (который включает текст линейки/названия) — разные линейки одного бренда больше не
   проходят как `EXACT` по одному только совпадению артикула и бренда.
2. **Нужный товар всё ещё терялся за лимитом 300** — поиск по значимым токенам и ранжирование
   (раунд 4) не помогают, если каждый отдельный SQL-запрос сам по себе всё ещё ограничен
   `PageRequest.of(0, candidateFetchLimit)` ДО ранжирования: короткий числовой токен (например "5")
   совпадает по подстроке с "50" и заполняет лимит запроса сотнями нерелевантных товаров того же
   бренда раньше, чем до него доходит очередь у нужного товара. Простое увеличение лимита эту
   архитектурную проблему не решает. **Исправлено (ADR-029):** новый, полностью НЕОГРАНИЧЕННЫЙ (без
   `Pageable`/лимита вообще) запрос по бренду (`ProductRepository#findAllByShopIdAndBrandTokenIn`)
   теперь выполняется ДО ограниченного нечёткого поиска и проверяет точное совпадение fingerprint по
   ВСЕМ товарам этого бренда в магазине — независимо от `candidateFetchLimit` и от ранжирования.

Оба покрыты новым regression-тестом, точно воспроизводящим новый репорт
(`sameBrandDifferentLineArticleCollision_isNeverAutoMatchedAsExact`,
`brandWithMoreThan300Items_targetStillAutoMatchesExact_viaUnboundedSafeFingerprint`). Build/test gate
§1 подтверждает отсутствие регрессий в остальном пайплайне после этих изменений (frontend не
менялся в этом раунде).

## 2e. Раунд 6 (2026-09-11) — раунд-5 фикс сам был alias-слеп, плюс три архитектурных гэпа

Четвёртая ревизия раунда 5 нашла более точную вариацию того же класса бага (fingerprint identity
игнорировал настроенные alias'ы бренда) плюс три смежных архитектурных гэпа: не было диагностики,
отличающей "товар реально новый" от "поиск идентичности вообще не смог выполниться" перед
авто-созданием `NEW_PRODUCT`; не было apply-time повторной проверки против возможно изменившегося
каталога перед фактическим созданием товара; полный per-row (не per-batch) реload бренда из БД.
Полные детали — `docs/DECISIONS.md` ADR-030; `docs/STATE.md` → «Sixth round» для таблицы root
cause/fix. Кратко:

1. **Alias-написанная строка (`"Channel No 5"`/`"Шанель No 5"`) всё ещё не находила существующий,
   канонически иначе написанный товар (`"Chanel No 5"`), даже с настроенным алиасом магазина, после
   300+ товаров бренда** — fingerprint строки использовал raw-текст бренда, не alias-каноничную
   идентичность. **Исправлено (ADR-030):** `BrandAliasResolver#canonicalKey` + вычищение
   alias-написаний бренда из имени перед вычислением `line`; fingerprint версионирован
   (`NORMALIZATION_VERSION` 1→2) с колонкой `SupplierProductLink.normalizationVersion` (`V30`) +
   фоновый backfill service.
2. **Ноль fuzzy-кандидатов мог означать и "товар реально новый", и "сам поиск идентичности не
   смог выполниться" (нет бренда/fingerprint) — оба варианта одинаково авто-создавали
   `NEW_PRODUCT`**. **Исправлено (ADR-030):** новый `SearchCompleteness` record, прошитый через
   resolver → outcome → `ImportRow.candidateSearchDiagnostics` (`V31`, nullable) →
   `ImportBatchMatchingService`, который теперь требует `requiredStagesCompleted == true` (никогда
   не предполагается для legacy/отсутствующих строк) перед авто-подтверждением `NEW_PRODUCT`.
3. **Решение `NEW_PRODUCT` (принятое на этапе MATCHING) всё ещё могло создать дубликат `Product`,
   если идентичный товар уже существовал к моменту APPLY** (дубликат строки в том же файле, другой
   batch применился первым, или ручное редактирование между этапами). **Исправлено (ADR-030):** новый
   `reverifyStillNew` повторно выполняет неограниченную brand-scoped fingerprint-проверку против
   текущего состояния транзакции непосредственно перед созданием; подтверждённое совпадение
   переиспользуется (никогда не merge/delete), неоднозначный результат всё ещё создавал новый товар
   вместо угадывания — **этот конкретный остаточный гэп сам стал Section 1 сценарием B раунда 7, см.
   §2f ниже**.
4. **Бренд с 300+ товарами в одном файле импорта пере-запрашивался и пере-нормализовался из БД на
   КАЖДОЙ строке этого бренда внутри batch**. **Исправлено (ADR-030):** новый per-shop, per-brand
   `ConcurrentHashMap` кэш в `DeterministicMatchResolver`, сбрасываемый существующим вызовом
   `startNewBatch`.

Покрыто новым/обновлённым regression-тестом, точно воспроизводящим каждый репорт. Build/test gate
§1 (на момент раунда 6): `./mvnw -o clean verify` — 360 тестов, 0 ошибок, 1 error
(`FlywayPostgresSchemaTest`, Docker-only, environment limitation). Frontend не менялся в этом
раунде. **Эта секция добавлена в чеклист только сейчас, в раунде 7** — раунд 6 не обновил этот файл
при своём завершении; см. `docs/STATE.md` за фактической хронологией.

## 2f. Раунд 7 (2026-09-11) — раунд-6 фикс сам оказался неполным в четырёх точных сценариях, плюс
транзакционный баг фонового job'а и Jackson-регрессия

Пятая ревизия раунда 6 воспроизвела четыре оставшихся сценария того же класса бага (границы
"safe to create" vs "поиск не смог сказать однозначно") плюс два бага, найденных при реализации
фикса, не пользователем: транзакционный self-invocation баг в фоновом backfill job'е раунда 6, и
Jackson-регрессия сериализации `SearchCompleteness`, которую пришлось исправить ПЕРВОЙ до начала
остальной работы этого раунда (9 упавших тестов + 1 ошибка на старте раунда). Полные детали —
`docs/DECISIONS.md` ADR-031; `docs/STATE.md` → «Seventh round» для полной таблицы root cause/fix.
Кратко:

1. **`"Chanel No. 5 100 ml"` (написание поставщика, с точкой после "No") всё ещё не находил
   существующий `"Chanel No 5 100 ml"` (без точки) после 300+ товаров бренда** — не было никакой
   нормализации написания `"No"`/`"No."`. **Исправлено (ADR-031):** узко-целевой regex-паттерн,
   привязанный к буквам `"No"` (никогда не трогает настоящую десятичную точку типа `"1.5 oz"`);
   `NORMALIZATION_VERSION` 2→3.
2. **Два уже существующих, структурно идентичных товара (уже неоднозначность) всё ещё могли быть
   молча приняты за "нет совпадения, безопасно создать" — создавая ТРЕТИЙ дубликат товара** — и
   на этапе MATCHING, и на этапе APPLY проверка возвращала простой `Optional`/boolean, где `size()
   != 1` (0 или >1) схлопывалось в одно и то же значение. **Исправлено (ADR-031):**
   `DeterministicMatchResolver` теперь возвращает явный результат `NONE`/`UNIQUE`/`AMBIGUOUS`
   (никогда `Optional`); `SearchCompleteness` разделён на ортогональные enum'ы
   `IdentityOutcome`/`SearchState`; apply-time проверка в `ImportBatchApplyWriter` возвращает
   sealed-тип `ProductCreationCheck` (`ExistingMatch`/`SafeToCreate`/`Ambiguous`/`Unsafe`) —
   неоднозначный/небезопасный результат всегда прерывает batch для ручного review, никогда не
   проваливается в "создать всё равно".
3. **Строка со `normalizedData`, вычисленной СТАРОЙ версией нормализации, доверялась как есть на
   этапе APPLY — как для `NEW_PRODUCT`, так и (раньше вообще без проверки) для уже-`MATCHED`
   строк**. **Исправлено (ADR-031):** `refreshIfStale` (пересчитывает свежо из сырых данных строки
   перед решением о создании `NEW_PRODUCT`) и новый `verifyMatchedProductStillAgrees` (тот же
   пересчёт и повторная проверка — теперь применяется и к уже-`MATCHED` строкам; настоящее
   расхождение прерывает batch вместо слепого доверия старому решению).
4. **Два параллельных apply-транзакции (разные batch/поставщики, возможно разные инстансы) могли
   оба выполнить re-`SELECT` "товара пока нет" до того, как коммитнется хотя бы один `INSERT`** —
   явно названный открытым гэпом в ADR-030 §3. **Исправлено (ADR-031):** новый интерфейс
   `ProductCreationLock`, захватываемый один раз в начале `ImportBatchApplyWriter#applyBatch` и
   удерживаемый на всю apply-транзакцию; `PostgresAdvisoryProductCreationLock`
   (`pg_advisory_xact_lock`, cross-instance, без миграции схемы) автоматически выбирается, когда
   реальный datasource — PostgreSQL, иначе `LocalProductCreationLock` (JVM-local, только для одного
   инстанса).
5. **(Найдено, не репортовано пользователем) Фоновый пересчёт fingerprint
   (`SupplierLinkFingerprintMigrationService#backfill()`) никогда реально не открывал транзакцию в
   production** — self-invocation вызов (`this.recomputeOne(...)`) обходил Spring
   `@Transactional`-proxy целиком; собственная транзакция `@DataJpaTest` молча маскировала это в
   тестах. **Исправлено (ADR-031):** `recomputeOne` вынесен в отдельный bean
   `SupplierLinkFingerprintRecomputer`, вызываемый как настоящий cross-bean вызов через реальный
   proxy.
6. **(Найдено, не репортовано пользователем) Блокирующая регрессия на старте раунда: 9 упавших
   тестов + 1 ошибка** — Jackson сериализовал производные instance-методы `SearchCompleteness`
   (`isAmbiguous()` и т.д.) как дополнительные bean-property JSON-поля, которые собственный
   record-канонический десериализатор затем отвергал как нераспознанные. **Исправлено (ADR-031):**
   каждый метод, не являющийся record-компонентом, теперь помечен `@JsonIgnore`.

Также закрыто при воспроизведении сценария 1 в масштабе: brand-scoped запросы
`SimpleProductCandidateFetcher` всё ещё индивидуально ограничивались `Pageable`/`limit` ДО
cross-query merge/rank шага (дефект "преждевременное ограничение перед ранжированием", смежный с,
но отличный от, фикса ADR-029) — теперь полностью безлимитны per-query, с единственной финальной
обрезкой после ранжирования по полному объединённому пулу.

Также добавлено, закрывая гэп верификации из плана этого раунда (не сам баг):
`SupplierImportGreenMailEndToEndTest` — полный pipeline-тест с реальным embedded GreenMail
IMAP-сервером, PRODUCTION `ImapMailboxClient` (никогда `FakeMailboxClient`) и настоящим XLSX
вложением, от реального IMAP fetch до `APPLIED` с корректной комиссионной ценой.

Покрыто новым/обновлённым regression-тестом для каждого сценария (см. `docs/DECISIONS.md` ADR-031
"Тесты" за полным списком). Build/test gate §1 подтверждает отсутствие регрессий в остальном
пайплайне после этих изменений (frontend не менялся в этом раунде). **Важное явное ограничение**:
сценарий 4 (`ProductCreationConcurrencyPostgresTest`) не мог быть выполнен в этой sandbox-среде
(нет локального Docker daemon) — реализация lock'а и её тест написаны и прошли code review, но
кросс-инстанс поведение подтверждено ТОЛЬКО ревью кода в этой сессии, НЕ реальным запуском против
PostgreSQL; см. §6 ниже и `docs/DECISIONS.md` ADR-031 "Осознанные ограничения" п.1.

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

## 6. Известные ограничения (обновлено после раунда 7 — ADR-031, closing round-6 gaps: spelling-variant fingerprint miss, ambiguous-duplicate collapse, stale-normalization-version rows, concurrent-creation race)

Не переоткрывать/не пере-исследовать без нового измеренного повода — уже задокументированы, ссылки
для контекста. **Оговорка после раундов 3, 4, 5, 6 и 7**: несколько пунктов ниже, помеченных как
"Resolved" предыдущим раундом, оказались резолвены не полностью — см. §2b/§2c/§2d/§2e/§2f и
`docs/DECISIONS.md` ADR-022…031 для того, что именно было упущено и как исправлено сейчас. Для
article matching и candidate search это уже ТРЕТЬЯ подряд итерация "Resolved" → воспроизведён новый
гэп в той же области при более точной проверке (ADR-023 → ADR-028 → ADR-029 для article matching;
ADR-024 → ADR-028 → ADR-029 для candidate search); для apply-time product-creation safety это уже
ВТОРАЯ подряд итерация (ADR-030 → ADR-031: "no re-verification at all" → "re-verification collapses
ambiguous into safe-to-create, stale-version rows untrusted, no cross-instance lock"). Формулировка
"Resolved" в этом файле означает "нет известной открытой проблемы на момент этого ADR", а не
гарантию отсутствия багов.

**Закрыто раундом 7** (2026-09-11, не открывать заново без нового измеренного повода; но см. п.1
ниже — Scenario D's cross-instance lock is code-reviewed, NOT yet proven against real PostgreSQL in
this sandbox):

- ~~Написание с точкой после "No" (`"Chanel No. 5"`) не находило существующий товар без точки
  (`"Chanel No 5"`) после 300+ товаров бренда~~ — **Resolved (ADR-031):** узко-целевая
  `PRODUCT_NUMBER_ABBREVIATION_PATTERN`, `NORMALIZATION_VERSION` 2→3.
- ~~Два существующих структурно идентичных товара (уже неоднозначность) могли быть приняты за
  "нет совпадения, безопасно создать" — производя ТРЕТИЙ дубликат~~ — **Resolved (ADR-031):**
  явный `NONE`/`UNIQUE`/`AMBIGUOUS` результат (никогда `Optional`) и на matching-, и на apply-time;
  `ProductCreationCheck` sealed-тип заменяет старый `Optional<Product>`.
- ~~Строка со `normalizedData` старой версии доверялась как есть на APPLY — для `NEW_PRODUCT` и
  (вообще без проверки) для уже-`MATCHED` строк~~ — **Resolved (ADR-031):** `refreshIfStale` +
  новый `verifyMatchedProductStillAgrees`, оба пересчитывают свежо из сырых данных и прерывают
  batch при настоящем расхождении.
- ~~Два параллельных apply-транзакции могли оба увидеть "товара нет" до коммита любого из
  `INSERT`~~ — **"Resolved" (ADR-031) по коду и review, НЕ подтверждено реальным запуском** (см.
  п.1 ниже): новый `ProductCreationLock`
  (`PostgresAdvisoryProductCreationLock`/`LocalProductCreationLock`), захватываемый на весь apply
  batch.
- ~~Фоновый backfill job (`SupplierLinkFingerprintMigrationService`) никогда реально не открывал
  транзакцию в production (self-invocation баг)~~ — **Resolved (ADR-031):** `recomputeOne` вынесен
  в отдельный bean `SupplierLinkFingerprintRecomputer`, вызываемый через настоящий Spring proxy.

1. **`ProductCreationConcurrencyPostgresTest` (доказательство фикса гонки создания товара, ADR-031
   Section 4) не могло быть выполнено в этой sandbox-среде** — тот же Docker-daemon limitation, что
   у `FlywayPostgresSchemaTest`. Реализация lock'а и тест написаны и прошли code review (реальная
   двухпотоковая barrier/latch координация, реально задействован
   `PostgresAdvisoryProductCreationLock`, не JVM-local fallback), но реальное cross-instance
   поведение подтверждено ТОЛЬКО ревью кода в этой сессии, не настоящим запуском против PostgreSQL.
   **Обязательно прогнать против реального Docker/Postgres окружения (например, CI) до того, как
   этот фикс будет считаться доказанным, а не просто "должен работать".**
2. **Advisory lock scoped per-shop, не per-brand/per-product** — крупный магазин, импортирующий из
   нескольких поставщиков одновременно, теперь сериализует ВСЕ apply-фазы этих batch'ей друг
   относительно друга (никогда не выполняет их create-or-reuse критические секции параллельно),
   даже если они касаются полностью разных брендов/товаров. Это разумный trade-off корректности
   против throughput; если apply-фаза когда-либо станет измеренным bottleneck'ом для одного
   крупного магазина, более тонкая (per-brand) блокировка потребует отдельного аккуратного дизайна —
   не реализовано здесь, поскольку per-shop — минимальная гранулярность, доказуемо корректная
   против Scenario D.

**Закрыто раундом 6** (2026-09-11, не открывать заново без нового измеренного повода; §2e добавлена
в этот чеклист только сейчас, в раунде 7):

- ~~Alias-написанная строка (`"Channel No 5"`/`"Шанель No 5"`) не находила канонически иначе
  написанный товар (`"Chanel No 5"`), даже с настроенным алиасом, после 300+ товаров бренда~~ —
  **Resolved (ADR-030):** `BrandAliasResolver#canonicalKey` + вычищение alias-написаний из имени;
  `NORMALIZATION_VERSION` 1→2.
- ~~Ноль fuzzy-кандидатов означал и "товар новый", и "поиск не смог выполниться" одинаково~~ —
  **Resolved (ADR-030), дополнено ADR-031 (см. выше):** `SearchCompleteness` diagnostic threaded
  end-to-end, требуется перед авто-`NEW_PRODUCT`.
- ~~`NEW_PRODUCT` решение не перепроверялось на APPLY против изменившегося каталога~~ —
  **"Resolved" (ADR-030), оказалось неполным (ADR-031, см. выше)** — исходный `reverifyStillNew`
  использовал `Optional`, схлопывающий ambiguous в "safe to create"; теперь `ProductCreationCheck`.
- ~~Полный per-row reload бренда из БД на каждой строке batch~~ — **Resolved (ADR-030):**
  per-shop, per-brand кэш в `DeterministicMatchResolver`.

**Закрыто раундом 5** (2026-09-11, не открывать заново без нового измеренного повода):

- ~~Артикул мог склеить два РАЗНЫХ товара ОДНОГО бренда (проверка бренда раунда 4 защищает только
  от коллизии между разными брендами)~~ — **Resolved (ADR-029):** `resolveViaExactSupplierArticle`
  теперь также требует точного совпадения вычисленного `fingerprint` (включает текст линейки/
  названия, не только бренд и объём/концентрацию/оттенок).
- ~~Нужный товар всё ещё терялся за лимитом 300 кандидатов — ранжирование раунда 4 не спасало,
  потому что каждый отдельный SQL-запрос сам оставался ограничен лимитом до ранжирования~~ —
  **Resolved (ADR-029):** новый неограниченный (без `Pageable`), scoped-по-бренду запрос
  (`findAllByShopIdAndBrandTokenIn`) проверяет точное совпадение fingerprint по ВСЕМ товарам бренда
  ДО того, как вообще запускается ограниченный нечёткий поиск.

**Закрыто раундом 4** (2026-09-10, не открывать заново без нового измеренного повода):

- ~~Артикул мог склеить товары разных брендов, если у существующего товара ещё нет
  `SupplierProductLink`~~ — **Resolved (ADR-028, дополнено ADR-029):** обязательное подтверждение
  бренда (`brandsConfirmIdentity`) + (ADR-029) обязательное точное совпадение fingerprint — теперь
  закрывает и коллизию РАЗНЫХ товаров ОДНОГО бренда, не только разных брендов.
- ~~Лимит кандидатов пропускал конкретный товар в бренде с 300+ товарами, если самое длинное слово
  оказывалось самим брендом~~ — **Resolved (ADR-028, дополнено ADR-029):** поиск по всем значимым
  токенам + ранжирование по релевантности перед обрезкой лимита (ADR-028); (ADR-029) отдельный,
  полностью неограниченный по бренду запрос для точного fingerprint-совпадения, независимый от
  `candidateFetchLimit` вообще.
- ~~У уже включённого `autoApply` источника можно было обнулить `senderAllowlist` без повторной
  проверки~~ — **Resolved (ADR-028):** постоянная проверка в `validateInvariants` на каждый PATCH,
  не только на переключение `autoApply`.
- ~~`confirm-payment` активировал подписку без оплаты при отсутствующем секрете даже в production;
  `activate-stub`/`extend-trial` доступны обычному `OWNER`~~ — **Resolved (ADR-028):**
  `requiresWebhookConfirmation()` fail-closed в `prod`; `activate-stub`/`extend-trial` теперь требуют
  `AuthorizationService#isSystemAdmin`, а не `MemberRole.OWNER`.

**Закрыто раундом 3** (2026-09-10, не открывать заново без нового измеренного повода):

- ~~FULL-импорт мог скрыть товар, чья строка стала `INVALID`~~ — **Resolved (ADR-022):** raw-identity
  cross-check по всем строкам batch перед деактивацией, независимо от статуса строки.
- ~~Точное сопоставление по артикулу не учитывало поставщика~~ — **Resolved (ADR-023, дополнено
  ADR-028):** проверка существующего `SupplierProductLink` на другого поставщика + обязательное
  подтверждение бренда — перед авто-матчем.
- ~~Лимит 300 кандидатов блокировал поиск по имени для брендов с 300+ товарами~~ — **Resolved
  (ADR-024, дополнено ADR-028):** комбинированный brand+name запрос по каждому значимому токену +
  ранжирование по релевантности.
- ~~PATCH мог включить `autoApply` в обход проверок `/graduate`; пустой allowlist разрешён~~ —
  **Resolved (ADR-025, дополнено ADR-028):** общий `assertReadyForAutoApply` gate для обоих
  endpoint'ов + постоянная проверка непустого `senderAllowlist` на каждый PATCH.
- ~~CloudPayments HMAC пропускал проверку при отсутствии секрета безусловно; billing bypass
  endpoints доступны любому участнику~~ — **Resolved (ADR-026, дополнено ADR-028):** fail-closed в
  `prod` профиле для HMAC И для `confirm-payment`; `isSystemAdmin`-only (не `OWNER`) для
  `activate-stub`/`extend-trial`.
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
- [ ] §2c — прочитан список из четырёх раунд-4 гэпов (ADR-028); это уже ВТОРОЙ раз, когда "Resolved"
      для тех же четырёх областей (article matching, candidate search, autoApply gate, billing)
      оказалось неполным — ответственный оператор относится к текущему состоянию соответственно
      осторожно, а не как к окончательно закрытому вопросу.
- [ ] §2d — прочитан список из двух раунд-5 гэпов (ADR-029); для article matching и candidate
      search это уже ТРЕТИЙ раз подряд, когда "Resolved" для той же области оказалось неполным при
      более точной проверке — ответственный оператор относится к текущим "Resolved" пометкам для
      ИМЕННО ЭТИХ двух областей с повышенной осторожностью и рассматривает дополнительный
      независимый review/shadow-прогон перед первым `autoApply=true` для нового поставщика с
      большим (300+) каталогом на бренд.
- [ ] §2e — прочитан список из четырёх раунд-6 гэпов (ADR-030): alias-canonical fingerprint,
      search-completeness diagnostic, apply-time re-verification (`reverifyStillNew`), per-batch
      brand-кэш.
- [ ] §2f — прочитан список из раунд-7 сценариев (ADR-031); это уже ВТОРОЙ раз подряд, когда
      "Resolved" для apply-time product-creation safety (раунд 6) оказалось неполным — в частности,
      §2f п.4 (concurrent-creation lock, `ProductCreationLock`) **не подтверждён реальным запуском
      против PostgreSQL в этой sandbox-среде** (нет Docker) — ответственный оператор ОБЯЗАН
      прогнать `ProductCreationConcurrencyPostgresTest` на среде с реальным Docker/Postgres (CI или
      локально) и получить `Errors: 0` для этого конкретного теста до того, как первый реальный
      поставщик с несколькими одновременно активными источниками для одного магазина будет
      переведён на `autoApply=true`.
- [ ] §3 — automation rate измерен и явно принят для этого поставщика.
- [ ] §4 — shadow-mode acceptance пройден по всем пяти пунктам перед `autoApply=true`.
- [ ] §5 — FULL snapshot чеклист пройден перед первым реальным FULL apply для этого источника.
- [ ] §6 — унаследованные ограничения прочитаны, ответственный оператор осознаёт остаточный риск.
