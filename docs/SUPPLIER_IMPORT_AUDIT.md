# Аудит supplier import

Дата аудита: 2026-08-31  
Проверенный commit: `0ab53722b8b4413d54fb9c5ba72430f91d0f0bbb` (`feat: add Telegram commerce storefront`)

## Scope и итог

Проверено фактическое состояние backend, admin panel, commerce, storefront,
Flyway/config profiles, фоновых задач, storage, шифрования, тестов и Docker.
Production code в рамках аудита не менялся.

Главный вывод: существующий commerce MVP уже пригоден как потребитель будущего
канонического каталога, но текущий импорт является синхронным ручным XLSX
upsert прямо в `products`. Email ingestion, supplier/source domain, immutable
source files, supplier offers, versioned rules, reconciliation, job claims и
exception audit отсутствуют.

До начала Prompt 01 есть два обязательных архитектурных ограничения:

1. `Product` нужно оставить канонической товарной записью, а supplier identity,
   закупочную цену, stock и active state вынести в отдельный `SupplierOffer`.
2. Нельзя полагаться на текущую production schema management: в `prod` Flyway
   отключён, а Hibernate настроен на `ddl-auto: update`.

## Фактический стек

| Область | Фактическое состояние | Evidence |
| --- | --- | --- |
| Java | source/release 21; локально Corretto `21.0.6`; Docker build/runtime на Temurin 21 | `pom.xml:21-24`, `Dockerfile:4,24` |
| Spring Boot | `3.1.5` | `pom.xml:8-13` |
| Apache POI | `poi-ooxml 5.2.5` | `pom.xml:129-134` |
| React | `18.3.1` в lockfile | `admin-panel/package.json:32-33`, `admin-panel/package-lock.json:4678-4695` |
| Vite | declared `^5.4.6`, resolved `5.4.21` | `admin-panel/package.json:57`, `admin-panel/package-lock.json:5442-5450` |
| TypeScript | declared `~5.6.2`, resolved `5.6.3` | `admin-panel/package.json:55`, `admin-panel/package-lock.json:5298-5303` |
| Node | repository pin/`engines` отсутствует; frontend Docker использует `node:20-alpine`; локально `v24.1.0`, npm `11.3.0` | `admin-panel/Dockerfile:4`, `.nvmrc`/`.node-version` отсутствуют |
| Database | default profile — in-memory H2; `prod` — PostgreSQL; compose — PostgreSQL 15 | `application.yml:5-21`, `application-prod.yml:5-21`, `docker-compose.yml:4-10` |

Node 24 — только версия текущей audit-машины, а не контракт проекта. Для
повторяемых frontend builds следует зафиксировать поддерживаемую Node version;
наиболее близкий к текущему deployment baseline — Node 20 из Dockerfile.

## Auth и shop access

### Admin API

- Spring Security stateless; `/api/auth/**`, storefront, webhooks и health
  разрешены без JWT, остальные `/api/**` требуют authentication
  (`SecurityConfig.java:39-77`).
- JWT подписан HS256-secret, содержит admin user id, email и name, действует по
  умолчанию 24 часа; приложение не стартует без секрета длиной минимум 32
  символа (`JwtService.java:24-41,47-58`).
- Пароли хешируются BCrypt (`SecurityConfig.java:93-96`,
  `AuthService.java:91-100`).
- `ShopAccessService` разрешает доступ owner либо любой записи
  `shop_members(user_id, shop_id)` (`ShopAccessService.java:16-25`).
- Все commerce admin handlers в `CommerceAdminController` явно вызывают
  `shopAccessService.hasAccess`. Сервисы и repositories дополнительно принимают
  `shopId`, например `ProductRepository.findByShopIdAndId`.
- Роль `OWNER`/`ADMIN`/`STAFF` при доступе не учитывается: любой member получает
  одинаковый commerce access. Для supplier automation нужно отдельно решить,
  какие изменения разрешены STAFF, не ослабляя текущую проверку membership.
- Frontend хранит bearer token только в памяти, не в `localStorage`
  (`admin-panel/src/api/client.ts:43-54,76-84`).

### Storefront

- Storefront GET API публичен и ограничивает выборку конкретным `shopId`.
- Создание заказа требует Telegram `initData`; signature и `auth_date`
  проверяются backend с bot token выбранного shop
  (`StorefrontService.java:108-143`,
  `TelegramInitDataValidator.java:30-84`).
- Order service повторно загружает product с `shopId` под pessimistic write
  lock и проверяет `active`, `visible`, availability и stock; frontend price не
  используется (`OrderService.java:133-149,273-303`).

### Tenant guarantees и ограничения

Shop scope является устойчивой convention в commerce services/repositories, но
это не глобальная DB-гарантия:

- нет PostgreSQL RLS;
- `order_items` не содержит `shop_id`;
- FK `order_items.product_id` и `product_images.product_id` не являются
  composite FK с `shop_id`;
- часть старых таблиц получила nullable `shop_id` в V7, а NOT NULL оставлен
  комментарием (`V7__add_multi_tenant_support.sql:31-98`).

Новые supplier tables обязаны иметь `shop_id` в FK/index/unique constraints, а
не только controller filtering.

### Дополнительные находки безопасности (вне commerce/supplier scope)

Независимая повторная проверка `AdminApiController`/`BillingController` нашла
находки, которые не связаны напрямую с supplier import, но важны для общей
auth/shop-access модели, на которой будет строиться import backoffice:

- `POST /api/admin/webhooks/update-all` доступен любому аутентифицированному
  пользователю и обновляет webhook у всех активных ботов всех магазинов, без
  `shopId` scoping (`AdminApiController.java:239-272`).
- `GET /api/stats` отдаёт платформенную статистику (кол-во активных ботов) без
  привязки к shop любому authenticated пользователю
  (`AdminApiController.java:403-411`).
- `CloudPaymentsService.validateHmac()` определён, но ни разу не вызывается из
  `BillingController`; webhook endpoints `/api/billing/cloudpayments/**`
  публичны (`permitAll`) без проверки подписи (`BillingController.java:164-206`).
- `POST /api/billing/confirm-payment` активирует подписку по вызову
  аутентифицированного клиента, не проверяя факт оплаты через CloudPayments API
  (`CloudPaymentsService.java:196-209`).
- `ShopMember.MemberRole` (`OWNER`/`ADMIN`/`STAFF`) нигде не проверяется:
  `hasAccessToShop`/`ShopAccessService` дают одинаковый доступ любому member
  (`AdminApiController.java:416-423`, `ShopAccessService.java:16-26`).
- Несколько code-lookup repositories (`PurchaseCodeRepository.findByCode`,
  `RedeemCodeRepository.findByCode`, `SpendCodeRepository.findByCode`,
  `DiscountCodeRepository.findByCode`) ищут по коду без `shopId`, что
  теоретически допускает cross-tenant погашение кода при коллизии значений.

Ни один из этих endpoints не используется текущим commerce/import flow, поэтому
они не блокируют Prompt 01. Но если import backoffice будет переиспользовать
`AdminApiController`/`ShopAccessService` как есть, эти пробелы наследуются, и
их стоит зафиксировать как отдельный tech-debt item, а не чинить неявно попутно
с supplier automation.

## Текущая commerce model

### Product сейчас смешивает две модели

`products` одновременно является:

- публичной/канонической карточкой: brand, name, description, category,
  `sale_price`, image, visible/active;
- supplier row: `supplier_guid`, `source_sheet`, `source_row`,
  `supplier_article`, `supplier_price`, `price_list_date`,
  `last_imported_at`;
- inventory projection: `stock_quantity`, `availability_mode`.

Evidence: `V12__add_commerce_catalog_orders.sql:3-30`,
`Product.java:24-103`.

Отдельных `Supplier`, `SupplierSource`, `SupplierOffer` и
`SupplierProductLink` нет. Поэтому один product фактически поддерживает только
один набор supplier identity/price/stock.

### Supplier identity и upsert

Текущий match order:

1. `(shopId, supplierGuid)`;
2. `(shopId, barcode)`;
3. `(shopId, sourceSheet, supplierArticle)`.

Evidence: `ProductImportService.java:370-456`.

Только GUID защищён partial unique index
`(shop_id, supplier_guid)` (`V12__add_commerce_catalog_orders.sql:36-38`).
Barcode и source/article не имеют unique constraint. Supplier id отсутствует,
поэтому одинаковый barcode из разных источников может перезаписать одну запись,
а одинаковый article на одинаково названном sheet нельзя безопасно разделить
по поставщикам.

### Public price

- Публичная цена — единственный `Product.salePrice`.
- Для нового import она вычисляется как supplier price плюс request markup и
  округляется до целых `HALF_UP`
  (`ProductImportService.java:415-435,459-466`).
- Для существующего product `salePrice` меняется только при
  `overwriteManualFields=true`; иначе остаётся ручное значение
  (`ProductImportService.java:389-411`).
- Bulk admin operation повторяет ту же формулу
  (`ProductService.java:102-108`).
- Pricing policy/version, supplier override и configurable rounding
  отсутствуют.
- Несколько offers и стратегия `LOWEST_ACTIVE_OFFER` отсутствуют.

### Stock

- Import всегда создаёт `stockQuantity=null` и
  `availabilityMode=PREORDER`; колонка «Заказ» не читается
  (`ProductImportService.java:415-435`).
- Для `PREORDER` количественный остаток не проверяется.
- Для `IN_STOCK` order creation проверяет stock под pessimistic lock и
  уменьшает его; cancel восстанавливает stock
  (`OrderService.java:138-149,189-209,257-293`).

## Visible, active, delete и order history

### Текущие правила

- Новый imported product: `active=true`; `visible` равен входному
  `makeImportedVisible`, который endpoint/UI по умолчанию передаёт `false`
  (`CommerceAdminController.java:49-65`,
  `CatalogPage.tsx:82-85,146-161`,
  `ProductImportService.java:415-435`).
- Повторный import всегда возвращает `active=true`, но выставляет
  `visible=true` только при явном flag; существующий `visible=true` не
  сбрасывается (`ProductImportService.java:389-411`).
- Отсутствующие в следующем файле товары не скрываются и не деактивируются:
  snapshot reconciliation отсутствует.
- Bot catalog и Mini App storefront показывают только
  `shopId + visible=true + active=true`
  (`ProductRepository.java:35,67-95`,
  `CommerceBotService.java:184-230`,
  `StorefrontService.java:71-105`).
- Текущий `visible` — ручной/publication flag. В target model его нельзя
  автоматически переиспользовать как offer availability. Нужен отдельный
  `manualHidden` override плюс вычисляемая доступность по active offers.

### Delete и история

Product delete endpoint/service отсутствует. Физическое удаление также
блокируется FK из `cart_items`, `order_items` и `product_images` без
`ON DELETE CASCADE` (`V12__add_commerce_catalog_orders.sql:81-109`,
`V13__add_product_images.sql:3-23`).

`order_items` хранит snapshots SKU, barcode, brand, name, price, availability и
quantity, но всё ещё имеет обязательную ссылку на `Product`
(`OrderItem.java:25-56`). Следовательно:

- скрытие/деактивация Product не ломает отображение исторического заказа;
- физическое удаление Product недопустимо и обычно завершится FK violation;
- future reconciliation должно деактивировать `SupplierOffer`, а не удалять
  `Product`.

## Текущий ProductImportService и Apache POI

### Реализовано

- Синхронный multipart endpoint:
  `POST /api/shops/{shopId}/catalog/import`.
- Только `XSSFWorkbook`, то есть фактически XLSX
  (`ProductImportService.java:82-97`).
- Standard layout:
  - sheets «Косметика и уход» / «Парфюмерия»;
  - header row 6 (zero-based index 5);
  - price date row 4 (index 3);
  - A GUID, D brand, E article, F barcode, G name, H price;
  - product row требует непустые brand/article/name и numeric price;
  - строка с заполненным brand и пустыми article/name/price становится
    category path.
  Evidence: `ProductImportService.java:36-46,151-225,468-504`.
- Simple layout: первые три строки сканируются на article/name/price; brand
  угадывается по началу name (`ProductImportService.java:228-367`).
- Barcode сохраняется string-like значением и обрабатывает scientific notation
  (`ProductImportService.java:507-575`).
- Admin Catalog UI имеет upload, markup, publish и overwrite-price flags,
  product filters/editing и image actions
  (`admin-panel/src/features/catalog/CatalogPage.tsx:68-170,182-283`).

### Нельзя использовать как automation pipeline без переработки

- source file не сохраняется, SHA-256/idempotency key отсутствует;
- mailbox/source/snapshot scope неизвестны;
- batch содержит только filename/date/counters/string status;
- raw/normalized rows и row-level errors/audit отсутствуют;
- layout hardcoded, rule versions/schema validation отсутствуют;
- parser загружает workbook целиком и не задаёт явные limits на размер, sheets,
  rows, cells и ZIP/XML expansion;
- formula cells читаются по cached result, но политика formula/schema drift не
  фиксируется в audit;
- не выполняются batch guards, quarantine, anomaly detection или FULL/DELTA
  reconciliation;
- ошибки неподходящих строк превращаются в `skipped` без объяснимого row audit;
- один `@Transactional` охватывает batch и весь import. Runtime exception
  откатывает и batch update; `FAILED` запись при IOException также будет
  rollback после повторного throw
  (`ProductImportService.java:53-130`).

Текущий direct-to-Product writer следует изолировать за future apply stage.
Его нельзя вызывать из mailbox happy path.

## Flyway и schema management

- Последняя migration: `V16__add_order_source.sql`.
- Всего найдено 16 versioned migrations: V1–V16.
- Default profile: H2, `ddl-auto: update`, Flyway disabled
  (`application.yml:5-25`).
- `prod`: PostgreSQL, `ddl-auto: update`, Flyway disabled
  (`application-prod.yml:5-21`).
- Других `application-*` profiles в repository нет.
- Migration history смешивает dialect assumptions: например V7 использует
  `AUTO_INCREMENT`, а V8+ — PostgreSQL `BIGSERIAL`/`ON CONFLICT`.
  Комментарий prod config прямо утверждает, что migrations «для MySQL».

Prompt 01 не должен просто добавить V17 и считать schema управляемой. Сначала
нужно подтвердить применённую production schema/baseline, сделать новые
migrations PostgreSQL-compatible и перевести production на Flyway с
`ddl-auto: validate` (либо `none` после отдельного решения). Автоматически
переписывать старые уже применённые migrations нельзя.

## Readiness существующих consumers

| Компонент | Готовность | Что уже можно reuse | Gap для supplier sync |
| --- | --- | --- | --- |
| Catalog/admin | Частично готов | shop-scoped list/edit/bulk operations, XLSX upload UI | import dashboard, exceptions, suppliers/sources/rules отсутствуют |
| Telegram bot catalog | Готов как consumer/fallback | visible+active catalog, short callback IDs, text fallback при photo failure, cart/orders | availability должна перейти на offer projection без удаления bot flow |
| Orders | Готов как consumer | backend price validation, pessimistic stock lock, snapshots, status UI, bonus on completion | product projection должна оставаться совместимой; supplier data не нужна order DTO |
| Images | Функциональный MVP | optional Brave search, rule/DeepSeek image ranker, normalization, explicit approval, approved-only storefront output | local mutable filesystem не подходит для immutable supplier files; image AI не является catalog matcher |
| Mini App storefront | Функциональный MVP | public DTO без `supplierPrice`, visible+active filter, approved-only image, Telegram initData validation | visibility/public price сейчас берутся прямо из Product, не из active offers |

Storefront mapper отдаёт `salePrice`, stock и approved image, но не
`supplierPrice`, supplier identifiers или import audit
(`StorefrontProductMapper.java:20-67`).

## Scheduler, locking, mail, encryption и storage

### Scheduler/job locking

- Scheduling включён в `LoyaltyBotApplication` и повторно в `AsyncConfig`.
- Есть несколько обычных `@Scheduled` jobs для loyalty/support/reporting.
- ShedLock, DB lease/claim, advisory lock и resumable import job abstraction
  отсутствуют.

Текущий scheduler запускает job в каждом replica. Для mailbox/import требуется
DB-backed claim/lease с expiry и idempotent transition.

### Mail

Mail/IMAP dependencies, mailbox entities, cursor, provider adapter, polling,
sender/subject/filename filters и attachment ingestion отсутствуют полностью.
Первый provider не подтверждён repository/config.

### Encryption

`TokenEncryptionService` реализует AES-256-GCM с random 12-byte IV и внешним
Base64 key (`TokenEncryptionService.java:16-35,71-139`). Криптографический
primitive можно reuse/обобщить для mailbox secrets, но текущая operational
policy недостаточна:

- default profile генерирует временный key при отсутствии config, после restart
  старые secrets не расшифруются;
- encryption можно отключить и хранить plaintext;
- key version/rotation и associated data отсутствуют;
- `docker-compose.full.yml` допускает пустой `ENCRYPTION_KEY`.

Для mailbox credentials production должен fail closed; plaintext secret нельзя
возвращать в DTO или логировать.

### Storage

Есть только local filesystem storage изображений под
`./data/product-images/{shopId}/{productId}`. Save использует
`REPLACE_EXISTING`, поэтому storage не immutable
(`ImageStorageService.java:27-55`). S3/MinIO SDK и source-file storage
отсутствуют. Этот сервис можно использовать как пример shop-scoped path
convention, но не как реализацию `ImportFileStorage`.

## Tests и Docker baseline

### Tests/build

- Backend: только 2 test classes / 9 tests:
  `ProductImportServiceTest` и `TelegramInitDataValidatorTest`.
- Нет Spring integration tests, repository/tenant tests, PostgreSQL
  Testcontainers, mailbox fake server, concurrency/idempotency tests.
- `ProductImportServiceTest` содержит environment-dependent путь в
  `~/Downloads/Telegram Desktop/...`; при отсутствии файла test молча
  возвращается, а не считается skipped
  (`ProductImportServiceTest.java:159-178`).
- Frontend имеет build/lint scripts, но test script/framework отсутствует
  (`admin-panel/package.json:6-10`).

Audit verification 2026-08-31:

- `mvn test` — PASS, 9/9; optional local XLSX был найден и test обработал 528
  rows, поэтому результат не полностью hermetic;
- `npm run build` — PASS; Vite предупредил о JS chunk `889.09 kB` и устаревшем
  Browserslist data.

### Docker

- Backend multi-stage image: Maven 3.9 + Temurin 21; tests явно пропускаются
  (`Dockerfile:4-24`).
- Frontend build: Node 20, nginx runtime
  (`admin-panel/Dockerfile:4-35`).
- Compose варианты поддерживают PostgreSQL 15, backend/frontend и optional
  rembg, но не MailHog/GreenMail, MinIO или test services.
- `docker-compose.full.yml` содержит небезопасные development defaults
  (`password`, admin code, optional empty JWT/encryption values); его нельзя
  считать production-ready secret policy.

## Незакоммиченные и частично реализованные участки

На момент аудита branch `master` tracking `origin/master`. Production source
files не изменены, но working tree уже содержит отдельную незавершённую
документационную работу:

- modified: `README.md`;
- deleted: `docs/image-pipeline-local.md`,
  `docs/telegram-mini-app-storefront.md`, `docs/zabotik-commerce-mvp.md`;
- untracked: `.cursor/rules/docs/`, `docs/ACCEPTANCE_CRITERIA.md`,
  `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`,
  `docs/PROJECT_CONTEXT.md`, `docs/STATE.md`, `prompts/`, `reference/`.

Эти изменения не восстанавливались, не удалялись и не перезаписывались.
Особенно нельзя считать отсутствие трёх deleted docs доказательством, что
соответствующий production code не существует: catalog, image pipeline и Mini
App присутствуют в HEAD.

Частично реализованный production scope, который нужно сохранять:

- commerce catalog/cart/orders и bonus completion;
- Telegram bot catalog как обязательный fallback;
- Mini App routes/backend/storefront auth;
- image search/normalization/manual approval;
- ручной synchronous import и его admin API/UI до Prompt 08 migration path.

## Сверка assumptions из PROJECT_CONTEXT.md

| Assumption | Результат аудита |
| --- | --- |
| Spring Boot 3.1.5 / Java 21 / package `com.plstk.loyaltybot` | Подтверждено |
| React/Vite в `admin-panel` | Подтверждено; React 18.3.1, Vite 5.4.21 |
| PostgreSQL и Flyway migrations | Частично: dependency/files есть, но Flyway отключён во всех profiles; default runtime — H2 |
| Multi-tenant isolation по `shopId` | Подтверждено как application convention, но не как полная DB guarantee |
| Apache POI использовался или планировался | Подтверждено: POI 5.2.5 и работающий `ProductImportService` |
| Standard workbook sheets/header row 6 | Подтверждено hardcoded parser rules |
| Около 2481 позиций | Не подтверждено repository fixtures/tests; число нельзя считать verified |
| Commerce catalog/orders/Mini App/image pipeline существовали или проектировались | Подтверждено как фактически реализованные MVP-компоненты |
| Email provider | Не подтверждён и не реализован |
| DeepSeek first provider | Есть только optional DeepSeek image ranker; catalog matcher/layout detector отсутствуют |
| S3-compatible production storage / MinIO local | Только target assumption; в коде есть лишь local image filesystem |

## Mapping: reuse / extend / replace / missing

| Статус | Текущий компонент | Решение для supplier automation |
| --- | --- | --- |
| reuse | JWT + `ShopAccessService` | Использовать для всех backoffice endpoints; добавить role policy только явно |
| reuse | `Product` identity/public fields | Оставить каноническим catalog product и order/image anchor |
| reuse | `ProductRepository` shop-scoped patterns | Следовать naming/query convention, но усилить DB constraints |
| reuse | `OrderService`, snapshots, locks | Не менять order contract; получать public projection из canonical Product |
| reuse | Bot catalog и Mini App storefront DTO | Сохранить consumers и approved-image/supplier-price isolation |
| reuse | `TokenEncryptionService` AES-GCM primitive | Обобщить и сделать production fail-closed/versioned для mailbox secrets |
| reuse | optional provider/config patterns image pipeline | Применить к disabled-by-default mail/AI adapters, не смешивая image AI с catalog AI |
| extend | `Product` | Добавить явный `manualHidden`/projection semantics; не хранить новые supplier rows в Product |
| extend | `ProductImportBatch`/table | Миграционно сохранить старые records, добавить строгую state machine и links к immutable file/source/rule; либо adapter к новой `import_batches` table |
| extend | Flyway/config | Baseline реальной prod schema, включить Flyway, убрать production `ddl-auto:update` |
| extend | admin routes/API client | Добавить imports/suppliers/mailboxes UI после backend stages; manual upload оставить поздним fallback |
| extend | test/Docker setup | Добавить Testcontainers PostgreSQL, fake mail server и MinIO/local immutable storage fixtures |
| replace | `ProductImportService` как end-to-end engine | Разделить ingestion/parser/matcher/apply; сохранить legacy manual entry до Prompt 08 |
| replace | hardcoded direct POI-to-Product write | Versioned parser rules + raw rows + validation/quarantine + separate apply |
| replace | request-scoped markup → `Product.salePrice` | Versioned `PricingService`, per-offer calculated price и public strategy |
| replace | `visible` как импортная availability | Active offers projection + `manualHidden`; не снимать manual override |
| replace | local image storage как шаблон source storage | Отдельный immutable `ImportFileStorage`, S3-compatible in production |
| missing | supplier domain | `Supplier`, `SupplierSource`, snapshot mode/scope, commission/rounding/autoApply policy |
| missing | ingestion audit | `ImportFile`, SHA-256 dedupe, immutable metadata, row/audit entities and state enums |
| missing | offers/matching | `SupplierOffer`, `SupplierProductLink`, aliases, candidates, decisions |
| missing | mailbox | connection, encrypted credentials, cursor, source filters, IMAP adapter, polling job |
| missing | safe jobs | DB claim/lease, retry/recovery, bounded concurrency, idempotent transitions |
| missing | reconciliation | FULL/DELTA behavior, batch guards, scoped deactivate/reactivate, lowest-offer projection |
| missing | operations UI | automation dashboard, mailbox health, batch detail, exception queue |

## Кратчайший email-to-stored-batch vertical slice

Цель slice: допустимое вложение из письма автоматически и ровно один раз
создаёт immutable file record и batch в состоянии `STORED`. Парсинг Product и
manual upload в этот slice не входят.

### Минимальная последовательность

1. **Schema safety**
   - подтвердить production baseline;
   - следующая свободная migration version — V17;
   - включить PostgreSQL Flyway для production и исключить schema mutation
     через Hibernate.
2. **Minimal domain**
   - `Supplier`, `SupplierSource` с shop/snapshot scope и mail filters;
   - `MailboxConnection`, `MailboxCursor`;
   - immutable `ImportFile` (`shopId`, source, SHA-256, size, media type,
     original filename, storage key, mail identity);
   - `ImportBatch` со строгим status enum минимум `RECEIVED/STORED/FAILED`;
   - DB job claim/lease.
3. **Storage**
   - interface `ImportFileStorage`;
   - local atomic write implementation для tests/dev;
   - S3-compatible implementation/config can follow before production, но
     storage key/contract сразу должен быть provider-neutral и immutable.
4. **Single ingestion seam**
   - `AttachmentIngestionService.ingest(shopId, sourceId, mailIdentity,
     attachmentMetadata, InputStream)`;
   - stream в temp/object, SHA-256 и size limit;
   - transactionally create/find deduplicated `ImportFile` and exactly one
     `STORED` batch;
   - unique key минимум `(shop_id, supplier_source_id, sha256)`, а mail cursor
     отдельно защищает повторный poll;
   - storage failure не создаёт `STORED`; DB conflict возвращает прежний result.
5. **Mailbox adapter**
   - `MailboxClient` interface + первый подтверждённый IMAP auth mode;
   - read-only polling, не mark read/delete/move;
   - source filters и только supported attachment types;
   - cursor identity: mailbox + UIDVALIDITY + UID + attachment index/hash.
6. **Claimed poll job**
   - configurable 5-minute schedule;
   - одна replica получает lease, poll можно безопасно повторить после crash;
   - каждое attachment независимо передаётся в ingestion service.
7. **Verification**
   - fake mailbox: duplicate poll, two messages, multiple attachments,
     UIDVALIDITY change, wrong sender/type/oversize;
   - PostgreSQL Testcontainers: tenant unique constraints, duplicate races,
     expired lease;
   - storage failure/transaction recovery и secret redaction.

### Acceptance signal slice

После двух одинаковых polls:

- письмо остаётся неизменённым в mailbox;
- source bytes сохранены один раз;
- существует один `ImportFile` и один `STORED` batch для данного shop/source;
- cursor продвинут только после надёжного ingestion результата;
- другой `shopId` не может прочитать file/batch;
- parser, products, visibility и prices не изменены.

## Блокеры и риски перед Prompt 01

1. Неизвестна реально применённая production schema/Flyway history.
2. Не выбран первый mail provider и auth mode (OAuth2/app password).
3. Нет обезличенных committed fixtures писем/XLSX; текущий import test зависит
   от локального файла пользователя.
4. Existing `product_import_batches` требует migration/compatibility decision,
   чтобы не потерять ручную import history.
5. Target automatic publication конфликтует с legacy default hidden import;
   решать это нужно через active offers + `manualHidden`, а не переключением
   старого `visible` для всех записей.
6. Encryption/storage/job execution требуют production-grade contracts до
   подключения mailbox.
7. Existing deleted/untracked docs являются параллельной пользовательской
   работой и не должны быть затёрты следующими prompts.
