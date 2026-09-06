# Supplier Import Automation — Release Checklist (Prompt 10)

Итог финальной ревизии (`prompts/10-final-review.md`) поверх end-to-end supplier-import pipeline
(Prompt 01-09). Это операционный чеклист для человека, включающего автоматизацию для реального
поставщика/окружения — не описание архитектуры (см. `docs/ARCHITECTURE.md`) и не журнал решений
(см. `docs/DECISIONS.md` → ADR-010 для деталей этого prompt).

## 1. Build/test gate (обязательно перед релизом)

Все команды выполняются из корня репозитория.

| Проверка | Команда | Ожидаемый результат (на момент этого прогона) |
| --- | --- | --- |
| Backend компиляция | `mvn -q compile` | без ошибок |
| Backend unit+`@DataJpaTest` suite | `mvn test` | `Tests run: 240, Failures: 0, Errors: 0` (35 классов) |
| Backend package | `mvn -o package -DskipTests` | success |
| Frontend build | `npm run build` (в `admin-panel/`) | success (bundle warning про chunk size — известный, не блокирующий) |
| Frontend tests | `npx vitest run` (в `admin-panel/`) | `Test Files 6 passed (6)`, `Tests 21 passed (21)` |

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
- `supplier-import.ai.deepseek.timeout-ms` — задокументирован как фактически не подключённый (весь
  DeepSeek HTTP трафик идёт через один общий `RestTemplate` с фиксированным таймаутом,
  `RestTemplateConfig`); не переподключался в этом prompt, чтобы не трогать общий HTTP-клиент,
  используемый Telegram/оплатами/поиском изображений.
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

## 6. Известные ограничения, унаследованные из Prompt 01-09 (не закрыты этим prompt)

Не переоткрывать/не пере-исследовать — уже задокументированы, ссылки для контекста:

- OAuth2 mailbox auth не реализован (только IMAP + app password) — `docs/STATE.md`, ADR-002.
- `pg_trgm` candidate fetcher не подтверждён на реальном Postgres — `docs/STATE.md`, ADR-004 п.1.
- Fuzzy/AI/guard thresholds — статические config defaults, не откалиброваны на реальном
  ассортименте/волатильности — `docs/STATE.md`, ADR-004/005/006 (см. также §3-5 выше).
- Нет circuit breaker для DeepSeek (только retry/backoff одного вызова) — ADR-005 п.5.
- Concurrency (несколько реплик/потоков) не проверена настоящей многопоточной гонкой ни в одном
  prompt, включая этот — только conditional-`UPDATE`/claim-lease на уровне кода; см. ADR-009 п.7.
- `ddl-auto: update`/Flyway остаётся unchanged decision — production schema baseline не
  подтверждён; см. ADR-001 п.1 (unchanged всеми последующими ADR).
- Alert-правила для `SupplierImportMetrics` задокументированы как рекомендация
  (`docs/ARCHITECTURE.md` §22), не как Prometheus/Alertmanager конфиг в репозитории.
- Backup процедура задокументирована, не автоматизирована (нет cron/скрипта в репозитории).

## 7. Sign-off

Перед тем как считать эту автоматизацию готовой к продакшену для конкретного магазина/поставщика:

- [ ] §1 build/test gate — все пять команд зелёные на актуальном коммите.
- [ ] §2 — прочитан список исправлений (или ADR-010 целиком), нет открытых Critical/High.
- [ ] §3 — automation rate измерен и явно принят для этого поставщика.
- [ ] §4 — shadow-mode acceptance пройден по всем пяти пунктам перед `autoApply=true`.
- [ ] §5 — FULL snapshot чеклист пройден перед первым реальным FULL apply для этого источника.
- [ ] §6 — унаследованные ограничения прочитаны, ответственный оператор осознаёт остаточный риск.
