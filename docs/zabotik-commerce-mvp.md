# Zabotik Commerce MVP

## Goal

Add a fast Telegram shop MVP to the existing Zabotik loyalty bot.

The product use case:

* We have a supplier price list for cosmetics and perfume.
* We want to import the price list.
* Admin selects products for the first drop.
* App searches real product images.
* AI or image processing normalizes real product photos to a simple white-background ecommerce format.
* Admin approves images.
* Users can browse products in Telegram.
* Users can add products to cart.
* Users can create an order.
* Admin processes orders manually.
* Bonuses are accrued after order completion.

This is not a full ecommerce platform yet. This is a Telegram-first order system with catalog, images, cart, orders and loyalty.

## Existing project

Backend:

* Spring Boot 3.1.5.
* Java 21.
* JPA/Hibernate.
* Flyway.
* Package: `com.plstk.loyaltybot`.
* Multi-tenancy via `shopId`.
* Existing loyalty user entity already has:

  * `chatId`
  * `shopId`
  * `phoneNumber`
  * `bonusBalance`
  * `totalSpend`
  * `purchasesCount`
  * `firstPurchaseAt`
  * `lastPurchaseAt`
* Existing Telegram service:

  * `LoyaltyBotService`
* Existing Telegram client:

  * `TelegramApiClient`
* Existing bonus logic:

  * `BonusService`
* Existing frontend:

  * `admin-panel/src`
  * React/Vite/TypeScript

## Price file

Supplier file path:

`imports/price-25-06.xlsx`

Sheets:

* `Косметика и уход`
* `Парфюмерия`

Structure:

* Header row: 6.
* Product rows start after row 6.
* Row 4 contains price date: `Цены указаны на: 25.06.2026`.
* Columns:

  * A: `GUID`
  * D: `Бренд`
  * E: `Артикул`
  * F: `Штрихкод`
  * G: `Номенклатура`
  * H: `Цена`
  * J: `Заказ`
  * K: `Сумма`

Product row:

* brand is not blank;
* supplier article is not blank;
* product name is not blank;
* supplier price is numeric.

Do not import category rows as products.

Do not use `Заказ` as stock.

Barcode must be string.

## MVP product logic

Imported products:

* hidden by default;
* active by default;
* preorder by default;
* no stock by default;
* sale price calculated from supplier price with default markup.

Admin then manually:

* edits sale price;
* sets old price if needed;
* toggles visible;
* sets availability;
* sets stock if product is physically in stock;
* finds/approves image.

Telegram users see only:

* `visible=true`
* `active=true`

## Backend entities

### Product

Create entity `Product`.

Fields:

* `Long id`
* `String shopId`
* `String supplierGuid`
* `String sourceSheet`
* `Integer sourceRow`
* `String brand`
* `String supplierArticle`
* `String barcode`
* `String name`
* `String description`
* `String categoryPath`
* `BigDecimal supplierPrice`
* `BigDecimal salePrice`
* `BigDecimal oldPrice`
* `String currency`, default `RUB`
* `Integer stockQuantity`
* `AvailabilityMode availabilityMode`
* `Boolean visible`, default `false`
* `Boolean active`, default `true`
* `String mainImageUrl`
* `ImageStatus imageStatus`
* `LocalDate priceListDate`
* `LocalDateTime lastImportedAt`
* `LocalDateTime imageUpdatedAt`
* `LocalDateTime createdAt`
* `LocalDateTime updatedAt`

Enum `AvailabilityMode`:

* `PREORDER`
* `IN_STOCK`
* `OUT_OF_STOCK`

Enum `ImageStatus`:

* `MISSING`
* `CANDIDATE_FOUND`
* `DOWNLOADED`
* `NORMALIZED`
* `NEEDS_REVIEW`
* `APPROVED`
* `REJECTED`
* `FAILED`

Rules:

* `PREORDER`: user can order, no stock reservation.
* `IN_STOCK`: user can order only if stock is enough.
* `OUT_OF_STOCK`: user cannot order.

### ProductImage

Create entity `ProductImage`.

Fields:

* `Long id`
* `String shopId`
* `Product product`
* `ImageType imageType`
* `ImageStatus status`
* `ImageSourceType sourceType`
* `String sourceUrl`
* `String sourcePageUrl`
* `String sourceDomain`
* `String originalUrl`
* `String normalizedUrl`
* `BigDecimal confidence`
* `String matchedBy`
* `Boolean approvedByAdmin`
* `Boolean aiNormalized`
* `String rejectReason`
* `LocalDateTime createdAt`
* `LocalDateTime updatedAt`

Enum `ImageType`:

* `MAIN`
* `CANDIDATE`
* `PLACEHOLDER`

Enum `ImageSourceType`:

* `SUPPLIER`
* `BRAND_OFFICIAL`
* `MARKETPLACE_CANDIDATE`
* `MANUAL_UPLOAD`
* `MANUAL_URL`
* `AI_PLACEHOLDER`

### CartItem

Create entity `CartItem`.

Fields:

* `Long id`
* `String shopId`
* `User user`
* `Product product`
* `Integer quantity`
* `LocalDateTime createdAt`
* `LocalDateTime updatedAt`

Unique:

* `user_id + product_id`.

### CustomerOrder

Create entity `CustomerOrder`.

Fields:

* `Long id`
* `String shopId`
* `User user`
* `OrderStatus status`
* `BigDecimal itemsTotal`
* `BigDecimal bonusSpent`
* `BigDecimal totalToPay`
* `BigDecimal bonusAccrued`
* `String customerPhone`
* `String customerName`
* `DeliveryType deliveryType`
* `String deliveryAddress`
* `String customerComment`
* `LocalDateTime createdAt`
* `LocalDateTime updatedAt`
* `LocalDateTime completedAt`
* `LocalDateTime cancelledAt`

Enum `OrderStatus`:

* `DRAFT`
* `CREATED`
* `CONFIRMED`
* `PACKING`
* `READY_FOR_PICKUP`
* `SHIPPED`
* `COMPLETED`
* `CANCELLED`

Enum `DeliveryType`:

* `PICKUP`
* `COURIER`
* `CDEK`
* `OTHER`

### OrderItem

Create entity `OrderItem`.

Fields:

* `Long id`
* `CustomerOrder order`
* `Product product`
* `String skuSnapshot`
* `String barcodeSnapshot`
* `String brandSnapshot`
* `String nameSnapshot`
* `BigDecimal priceSnapshot`
* `Integer quantity`
* `BigDecimal lineTotal`

Important:

* Store product snapshot at order creation.
* Do not calculate old orders from current product state.

### ProductImportBatch

Create entity `ProductImportBatch`.

Fields:

* `Long id`
* `String shopId`
* `String filename`
* `LocalDate priceListDate`
* `Integer totalRows`
* `Integer importedCount`
* `Integer updatedCount`
* `Integer skippedCount`
* `String status`
* `String errorMessage`
* `LocalDateTime createdAt`
* `LocalDateTime finishedAt`

## Migrations

Add Flyway migrations after existing latest migration.

Recommended:

* `V12__add_commerce_catalog_orders.sql`
* `V13__add_product_images.sql`

Create tables:

* `products`
* `product_images`
* `cart_items`
* `customer_orders`
* `order_items`
* `product_import_batches`

Add indexes:

* `products(shop_id)`
* `products(shop_id, visible, active)`
* `products(shop_id, brand)`
* `products(shop_id, barcode)`
* `product_images(shop_id, product_id)`
* `cart_items(shop_id, user_id)`
* `customer_orders(shop_id, created_at)`
* `customer_orders(shop_id, status)`

Use existing project migration style.

## Dependencies

Add Apache POI for XLSX parsing:

`org.apache.poi:poi-ooxml`

Do not add heavy dependencies unless needed.

## Backend repositories

Create:

* `ProductRepository`
* `ProductImageRepository`
* `CartItemRepository`
* `CustomerOrderRepository`
* `OrderItemRepository`
* `ProductImportBatchRepository`

Required repository operations:

`ProductRepository`:

* find by shopId;
* find visible/active products by shopId;
* search by query over name, brand, barcode, supplier article;
* find by shopId and id;
* find by shopId and supplierGuid;
* find by shopId and barcode;
* find by shopId, sourceSheet, supplierArticle;
* distinct brands by shopId.

`ProductImageRepository`:

* find by shopId and productId;
* find approved main image;
* find images needing review.

`CartItemRepository`:

* find cart by shopId and user;
* find cart item by shopId, user and product;
* delete cart by shopId and user.

`CustomerOrderRepository`:

* find orders by shopId;
* find orders by shopId and status;
* find user's orders by shopId.

## Product import service

Create `ProductImportService`.

Method:

`ImportResult importPriceList(String shopId, MultipartFile file, BigDecimal defaultMarkupPercent, boolean makeImportedVisible, boolean overwriteManualFields)`

Rules:

* Read only sheets:

  * `Косметика и уход`
  * `Парфюмерия`
* Header row is 6.
* Data starts after header.
* Parse row 4 for price date.
* Product row fields:

  * GUID: A
  * brand: D
  * supplierArticle: E
  * barcode: F
  * name: G
  * supplierPrice: H
* Category rows:

  * usually have only column D filled;
  * use as best-effort `categoryPath`;
  * do not import as products.
* Upsert key:

  1. `shopId + supplierGuid`, if GUID exists;
  2. `shopId + barcode`, if barcode exists;
  3. `shopId + sourceSheet + supplierArticle`.
* For existing products update:

  * supplier price;
  * brand;
  * article;
  * barcode;
  * name;
  * category path;
  * price list date;
  * last imported at;
  * active true.
* Do not overwrite manual fields unless `overwriteManualFields=true`:

  * salePrice;
  * oldPrice;
  * visible;
  * stockQuantity;
  * availabilityMode;
  * mainImageUrl;
  * imageStatus.
* For new products:

  * `salePrice = supplierPrice * (1 + defaultMarkupPercent / 100)`;
  * round to whole rubles with `HALF_UP`;
  * `visible = makeImportedVisible`, default false;
  * `availabilityMode = PREORDER`;
  * `stockQuantity = null`;
  * `active = true`;
  * `imageStatus = MISSING`.

## Image search and normalization

The price file has no photos.

Need pipeline:

1. Build search queries from product data.
2. Search image candidates using provider abstraction.
3. Score candidates.
4. Download selected candidate.
5. Normalize real image to white ecommerce packshot.
6. Admin reviews and approves.
7. Approved image becomes product main image.

Important:

* Do not generate branded product images from text.
* AI must not invent packaging.
* AI can only normalize existing real product image.
* If no image exists, product remains text-only or gets generic category placeholder later.
* Do not auto-approve in MVP.

### Desired final image

* Square.
* `1024x1024`.
* Pure white background `#FFFFFF`.
* Product centered.
* Product occupies around 75–85% of height.
* No people.
* No hands.
* No shelf.
* No props.
* No watermark.
* No marketplace badges.
* No price labels.
* No collage.
* Product packaging must remain unchanged.

### Search queries

Use:

1. Barcode:

   * `"{barcode}"`
2. Brand + article:

   * `"{brand}" "{supplierArticle}"`
3. Brand + product name:

   * `"{brand}" "{name}"`
4. Brand + article + product name:

   * `"{brand}" "{supplierArticle}" "{name}"`
5. For perfume:

   * `"{brand}" "{name}" perfume`
   * `"{brand}" "{name}" парфюм`
6. For cosmetics:

   * `"{brand}" "{name}" косметика`
   * `"{brand}" "{name}" купить`

### Search provider abstraction

Create interface:

`ProductImageSearchProvider`

Method:

* `List<ImageCandidate> search(Product product)`

Create default:

* `DisabledProductImageSearchProvider`

It returns empty list and is used when provider is not configured.

Create DTO `ImageCandidate`:

* `String title`
* `String imageUrl`
* `String pageUrl`
* `String sourceDomain`
* `ImageSourceType sourceType`
* `BigDecimal confidence`
* `String matchedBy`
* `Integer width`
* `Integer height`
* `Boolean hasWatermark`
* `Boolean looksLikePackshot`
* `Boolean needsReview`
* `LocalDateTime foundAt`

`matchedBy`:

* `BARCODE`
* `BRAND_ARTICLE`
* `BRAND_NAME`
* `NAME_ONLY`

### Candidate scoring

Positive:

* supplier source: +40
* page/title contains exact barcode: +35
* page/title contains exact supplier article: +25
* page/title contains exact brand: +15
* title contains important name tokens: +15
* image at least 600x600: +10
* looks like packshot: +10
* background is white/light: +5

Negative:

* no brand in title/page: -30
* image too small: -20
* collage: -20
* people/hands: -15
* watermark: -30
* marketplace badge or promo text: -15
* name-only match: -20
* category/listing page instead of product page: -15

Rules:

* confidence below 50: do not download automatically.
* confidence 50–79: can download but status must be `NEEDS_REVIEW`.
* confidence 80+: can download and normalize but still `NEEDS_REVIEW`.
* no auto-approval in MVP.

### Image normalization service

Create `ProductImageNormalizationService`.

Methods:

* `ProductImage normalizeImage(String shopId, Long imageId)`
* `byte[] normalizeToWhitePackshot(byte[] sourceImage, Product product)`
* `boolean validateNormalizedImage(byte[] normalizedImage, Product product)`

Create fallback implementation:

* read image;
* convert to RGB;
* resize preserving aspect ratio;
* place centered on `1024x1024` white canvas;
* save as jpg or webp.

If AI/image provider is configured, use it as optional provider.

Create interface:

`AiImageProvider`

Methods:

* `byte[] normalizeProductImage(byte[] original, Product product)`
* `byte[] generateCategoryPlaceholder(String categoryPath, String productName)`

Default:

* `NoopAiImageProvider`

AI normalization prompt:

```text
You are editing an ecommerce product photo.

Use the provided real product image as the source.
Keep the product packaging unchanged.
Do not change the brand logo, label text, colors, cap, box, bottle, tube, shape, size, or any printed details.
Remove the current background.
Place the exact same product centered on a pure white #FFFFFF background.
Create a clean square 1024x1024 ecommerce packshot.
Product should occupy about 75-85% of image height.
Use even margins.
Add only a very subtle natural shadow if needed.
No people, no hands, no props, no shelves, no badges, no watermark, no extra text, no collage.
Do not invent missing packaging details.
If the source image is too low quality or does not clearly show the product, return a failure instead of hallucinating.
```

## Image storage

Use simple local file storage for MVP.

Path:
`./data/product-images/{shopId}/{productId}/`

Files:

* `original-{imageId}.{ext}`
* `normalized-{imageId}.jpg`

Create `ImageStorageService`.

Methods:

* save original;
* save normalized;
* get public URL or served URL.

Add static/resource mapping if needed so Telegram can access approved images by public URL.

If public URL is not available in local dev, frontend preview can still use backend endpoint, but Telegram `sendPhoto` needs public URL or file upload. For MVP, implement `sendPhoto` by URL only if public URL exists; otherwise fallback to text.

## Cart service

Create `CartService`.

Functions:

* add product;
* decrease quantity;
* remove product;
* clear cart;
* get cart;
* calculate totals.

Rules:

* Cannot add inactive/hidden/out-of-stock products.
* For `IN_STOCK`, check stock.
* For `PREORDER`, allow order without stock.
* Use shopId everywhere.

## Order service

Create `OrderService`.

Functions:

* create order from cart;
* get admin orders;
* update status;
* cancel order;
* complete order and accrue bonus.

Rules:

* Create order item snapshots.
* For `IN_STOCK`, reduce stock on order creation.
* For `PREORDER`, do not reduce stock.
* If order cancelled, restore stock for `IN_STOCK`.
* Bonuses accrue only once when order becomes `COMPLETED`.
* Use existing `BonusService`.
* Update user purchase fields:

  * `totalSpend`
  * `purchasesCount`
  * `firstPurchaseAt`
  * `lastPurchaseAt`

## Admin API

Base path:

`/api/shops/{shopId}`

Access control:

* Reuse or extract existing access check from admin controllers.
* Every endpoint must check current admin has access to shopId.

Endpoints:

### Catalog import

`POST /api/shops/{shopId}/catalog/import`

Multipart:

* `file`
* `defaultMarkupPercent`, default 35
* `makeImportedVisible`, default false
* `overwriteManualFields`, default false

### Products

`GET /api/shops/{shopId}/products`

Query:

* page
* size
* query
* brand
* visible
* active
* missingImages

`GET /api/shops/{shopId}/products/{productId}`

`PUT /api/shops/{shopId}/products/{productId}`

Editable:

* salePrice
* oldPrice
* visible
* active
* stockQuantity
* availabilityMode
* description

`POST /api/shops/{shopId}/products/bulk`

Actions:

* set visible;
* set active;
* apply markup;
* set availability mode.

### Product images

`POST /api/shops/{shopId}/products/{productId}/images/search-candidates`

`POST /api/shops/{shopId}/products/{productId}/images/download-candidate`

`POST /api/shops/{shopId}/products/{productId}/images/{imageId}/normalize`

`POST /api/shops/{shopId}/products/{productId}/images/{imageId}/approve`

`POST /api/shops/{shopId}/products/{productId}/images/{imageId}/reject`

`POST /api/shops/{shopId}/products/{productId}/images/upload`

`POST /api/shops/{shopId}/products/{productId}/images/from-url`

`GET /api/shops/{shopId}/products/missing-images`

### Orders

`GET /api/shops/{shopId}/orders`

Query:

* status
* page
* size

`GET /api/shops/{shopId}/orders/{orderId}`

`PATCH /api/shops/{shopId}/orders/{orderId}/status`

Body:

```json
{
  "status": "COMPLETED"
}
```

## Telegram bot flow

Add user button:

`🛒 Каталог`

Add text commands:

* `/catalog`
* `/cart`
* `/orders`

Callbacks:

* `cat:p:{page}`
* `prd:{productId}`
* `cart:add:{productId}`
* `cart:dec:{productId}`
* `cart:rm:{productId}`
* `cart:view`
* `cart:clear`
* `checkout:start`
* `checkout:pickup`
* `checkout:delivery`
* `order:confirm`
* `order:cancel`

Use short callback prefixes.

Add user state:

* `AWAITING_ORDER_ADDRESS`

Flow:

1. User presses `🛒 Каталог`.
2. Bot shows paginated product list.
3. User opens product.
4. Bot shows product card.
5. User adds to cart.
6. User opens cart.
7. User starts checkout.
8. User chooses pickup or delivery.
9. For delivery, bot asks address.
10. Bot creates order.
11. User receives order number.
12. Admin sees order in admin panel.

Product card:

* If approved `mainImageUrl` exists, send photo with caption.
* Else send text.

Card text:

```text
🛍 BRAND
Product Name

Цена: 1 290 ₽
Старая цена: 1 690 ₽
Формат: под заказ

[➕ В корзину] [🛒 Корзина]
```

Cart text:

```text
🛒 Корзина

1. BRAND Product — 1 × 1 290 ₽
2. BRAND Product — 2 × 450 ₽

Итого: 2 190 ₽

[Оформить] [Очистить]
```

## TelegramApiClient

Add method if missing:

`sendPhoto(String botToken, Long chatId, String photoUrl, String caption, Object keyboard)`

If sending photo fails, fallback to `sendMessage`.

## Admin panel

Add routes:

* `/catalog`
* `/orders`

Add nav items:

* `Каталог`
* `Заказы`

### Catalog page

Create:
`admin-panel/src/features/catalog/CatalogPage.tsx`

Features:

* upload XLSX;
* import options:

  * markup percent default 35;
  * make imported visible default false;
  * overwrite manual fields default false;
* products table;
* filters:

  * query;
  * brand;
  * visible;
  * active;
  * missing image;
* columns:

  * image preview;
  * image status;
  * brand;
  * name;
  * article;
  * barcode;
  * supplier price;
  * sale price;
  * old price;
  * availability mode;
  * stock;
  * visible;
  * active;
* actions:

  * edit product;
  * publish/hide;
  * search image;
  * upload image;
  * approve/reject image.

Bulk actions:

* publish selected;
* hide selected;
* apply markup;
* search images for selected;
* show missing images.

### Product image modal

Features:

* current main image preview;
* button `Найти фото`;
* show candidates:

  * preview;
  * title;
  * source domain;
  * confidence;
  * matchedBy;
  * pageUrl;
* actions:

  * download;
  * normalize;
  * approve;
  * reject;
* manual upload;
* manual URL input.

### Orders page

Create:
`admin-panel/src/features/orders/OrdersPage.tsx`

Features:

* list orders;
* filter by status;
* open order details;
* see customer, phone, address, items;
* change status.

## Frontend API

Update:

* `admin-panel/src/api/types.ts`
* `admin-panel/src/api/client.ts`

Add types:

* Product
* ProductImage
* ImageCandidate
* CustomerOrder
* OrderItem
* ProductImportResponse
* ProductUpdateRequest
* ProductBulkRequest
* OrderStatus
* DeliveryType
* AvailabilityMode
* ImageStatus

Add API methods:

* importCatalog
* getProducts
* updateProduct
* bulkUpdateProducts
* searchImageCandidates
* downloadImageCandidate
* normalizeProductImage
* approveProductImage
* rejectProductImage
* uploadProductImage
* getOrders
* getOrder
* updateOrderStatus

## Acceptance criteria

Backend:

1. Project builds.
2. Migrations run.
3. XLSX import works.
4. Products are imported from both sheets.
5. Imported products are hidden by default.
6. Admin can publish product.
7. Telegram catalog shows only published active products.
8. Cart works.
9. Order creation works.
10. Admin can update order status.
11. COMPLETED order accrues bonuses once.
12. Product image candidate search architecture exists.
13. Disabled image provider does not break app.
14. Manual image upload works.
15. Image normalization fallback creates 1024x1024 white canvas image.
16. Approved image becomes product main image.
17. Telegram uses image when approved and falls back to text otherwise.

Frontend:

1. Admin build passes.
2. Catalog page exists.
3. XLSX upload exists.
4. Product table exists.
5. Product edit exists.
6. Image modal exists.
7. Orders page exists.
8. Status update works.

Manual smoke test:

1. Import `imports/price-25-06.xlsx`.
2. Publish one product.
3. Upload or attach image for it.
4. Approve image.
5. Open Telegram bot.
6. Click `🛒 Каталог`.
7. Add product to cart.
8. Create order.
9. Open admin orders.
10. Set order to `COMPLETED`.
11. Verify user bonus balance changed.

## Implementation order

Do not implement everything in one giant diff.

Step 1:

* Inspect project.
* Confirm current latest migration.
* Confirm existing API/auth patterns.
* Confirm Telegram patterns.
* Produce implementation plan.

Step 2:

* Backend catalog entities, migrations, repositories.
* XLSX import.
* Admin product endpoints.
* Backend build.

Step 3:

* Product image entities, storage, manual upload, normalization fallback.
* Image candidate provider abstraction.
* Image endpoints.
* Backend build.

Step 4:

* Cart and order backend.
* Bonus accrual on completion.
* Admin order endpoints.
* Backend build.

Step 5:

* Telegram catalog/cart/checkout flow.
* Add sendPhoto fallback.
* Backend build.

Step 6:

* Admin frontend catalog page.
* Image modal.
* Orders page.
* Frontend build.

Step 7:

* Full smoke fixes.
* Final report.
