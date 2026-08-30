Implement Telegram Mini App storefront for the Zabotik commerce MVP.

Current problem:

* The existing Telegram bot catalog is text-message based and looks bad for ecommerce.
* We need a Botmag-like storefront experience: product grid, images, search, cart, checkout.
* Do not remove the existing bot catalog; keep it as fallback.
* Main UX should be Mini App.

## Goal

Add a mobile-first Telegram Mini App storefront:

```text
Bot main menu
→ 🛍 Открыть магазин
→ Telegram Mini App
→ product grid
→ product details
→ cart
→ checkout
→ CustomerOrder in backend
→ admin sees order in Orders page
```

## Important Telegram rules

* Telegram Mini App uses `window.Telegram.WebApp`.
* Frontend can read `window.Telegram.WebApp.initData`.
* Backend must validate raw `initData` before trusting user identity.
* Do not trust `initDataUnsafe` directly on backend.
* Do not expose bot token or secrets to frontend.
* In dev, allow guest/session mode when Telegram initData is unavailable.
* In prod, Telegram-linked orders should validate initData on backend.

## Architecture

Use existing React/Vite frontend if possible.

Add storefront route:

```text
/shop/:shopId
```

or:

```text
/store/:shopId
```

Prefer:

```text
/store/:shopId
```

because admin routes are separate.

Backend public storefront API:

```text
/api/storefront/{shopId}/...
```

Do not reuse admin APIs for storefront.

## Bot integration

Update Telegram bot menu.

Main user keyboard should include:

* 🛍 Открыть магазин
* 🛒 Каталог в боте
* 🎁 Мои бонусы
* 👤 Профиль

When user presses `🛍 Открыть магазин`, bot sends a button that opens Telegram WebApp.

Use configured public URL:

```yaml
commerce:
  mini-app:
    enabled: ${COMMERCE_MINI_APP_ENABLED:true}
    public-url: ${COMMERCE_MINI_APP_PUBLIC_URL:http://localhost:5173}
```

WebApp URL:

```text
{COMMERCE_MINI_APP_PUBLIC_URL}/store/{shopId}
```

Add `TelegramApiClient` support for WebApp button if missing.

If WebApp button is not supported in current keyboard helper, implement inline keyboard with `web_app` button.

Do not break existing bot catalog. Rename current text catalog to:

```text
🛒 Каталог в боте
```

## Frontend Mini App

Create storefront module:

```text
admin-panel/src/features/storefront/
  StorefrontApp.tsx
  StorefrontCatalogPage.tsx
  StorefrontProductPage.tsx
  StorefrontCartPage.tsx
  storefrontApi.ts
  storefrontTypes.ts
  storefrontStore.ts
  telegramWebApp.ts
  Storefront.css or module styles
```

If the project structure suggests a better location, use it, but keep admin and storefront separated.

## Storefront UI

Mobile-first, Telegram-friendly.

Style:

* clean white/light background;
* rounded product cards;
* 2-column grid on mobile;
* sticky search/filter header;
* sticky bottom cart bar;
* large product images;
* clear prices;
* minimal visual noise;
* responsive width max 480–560px for mobile view;
* must look good inside Telegram WebView.

Do not copy Botmag assets, logos or proprietary CSS. Use our own clean layout.

## Telegram WebApp integration

Create helper:

```ts
telegramWebApp.ts
```

Functions:

* `getTelegramWebApp()`
* `getInitData(): string`
* `getTelegramUser()`
* `ready()`
* `expand()`
* `close()`
* `showBackButton(handler)`
* `hideBackButton()`
* `triggerHapticFeedback(type)`

On Storefront mount:

* call `window.Telegram?.WebApp?.ready()`
* call `window.Telegram?.WebApp?.expand()`
* use Telegram theme params if available.

All storefront API requests should send raw init data header if available:

```http
X-Telegram-Init-Data: window.Telegram.WebApp.initData
```

Also send a guest session id:

```http
X-Storefront-Session-Id: uuid from localStorage
```

This allows dev/browser testing without Telegram.

## Storefront screens

### 1. Catalog screen

Route:

```text
/store/:shopId
```

Features:

* header with shop name;
* search input;
* brand chips or dropdown;
* optional category chips;
* product grid:

  * image;
  * brand;
  * short name;
  * price;
  * old price if exists;
  * availability;
  * add to cart button;
* pagination or infinite scroll;
* sticky cart bottom bar when cart has items.

Only show products:

* `visible=true`
* `active=true`

Images:

* use `mainImageUrl` only if imageStatus is APPROVED;
* if no image, show clean placeholder:

  * white/gray block;
  * text `Фото скоро`;
* do not show unapproved images.

### 2. Product details screen

Route:

```text
/store/:shopId/products/:productId
```

Show:

* large image;
* brand;
* full name;
* price;
* old price;
* availability;
* description;
* quantity control;
* button `Добавить в корзину`;
* button `Назад`.

### 3. Cart screen

Route:

```text
/store/:shopId/cart
```

Show:

* cart items;
* quantity controls;
* remove button;
* total;
* delivery type:

  * `PICKUP`
  * `COURIER`
* address input if courier;
* customer name input;
* customer phone input;
* comment input;
* button `Оформить заказ`.

If Telegram user already has phone/name in backend, prefill if possible. Otherwise require phone before order.

### 4. Order success screen

Route or state after checkout:

Show:

* order number;
* total;
* status;
* text:
  `Заказ создан. Мы свяжемся с вами для подтверждения.`
* button:
  `Вернуться в каталог`.

## Frontend cart state

Use Zustand or existing store style.

Cart can be frontend-local for MVP:

* store productId, quantity, product snapshot for UI;
* on checkout, send items to backend;
* backend validates current product price/visibility/stock and creates official order.

Do not trust frontend prices for final order total.

## Backend storefront API

Add controller:

```java
StorefrontController
```

Base path:

```text
/api/storefront/{shopId}
```

No admin auth required.

### GET storefront settings

```http
GET /api/storefront/{shopId}/settings
```

Returns:

* shopId;
* shopName;
* bonuses enabled if useful;
* pickup text/address if exists;
* delivery options.

### GET products

```http
GET /api/storefront/{shopId}/products
```

Query:

* page
* size
* query
* brand
* category

Returns paginated products:

* id;
* brand;
* name;
* shortName;
* salePrice;
* oldPrice;
* availabilityMode;
* stockQuantity if IN_STOCK;
* mainImageUrl only if approved;
* imageStatus;
* categoryPath.

Rules:

* only visible=true;
* only active=true;
* do not return internal supplier price;
* do not return hidden/unapproved image candidates.

### GET product details

```http
GET /api/storefront/{shopId}/products/{productId}
```

Returns same + description.

### GET brands

```http
GET /api/storefront/{shopId}/brands
```

Returns distinct brands for visible active products.

### POST order

```http
POST /api/storefront/{shopId}/orders
```

Headers:

* `X-Telegram-Init-Data`, optional in dev but required for Telegram user linking;
* `X-Storefront-Session-Id`, optional guest fallback.

Body:

```json
{
  "customerName": "Иван",
  "customerPhone": "+79990000000",
  "deliveryType": "PICKUP",
  "deliveryAddress": "",
  "comment": "",
  "items": [
    {"productId": 1, "quantity": 2}
  ]
}
```

Backend rules:

* validate all productIds belong to shopId;
* validate visible=true and active=true;
* validate availability:

  * PREORDER allowed;
  * IN_STOCK requires enough stock;
  * OUT_OF_STOCK rejected;
* do not trust frontend price;
* create CustomerOrder using existing OrderService;
* snapshot product names/prices in OrderItem;
* link to User if Telegram initData validated and user exists/created;
* if no Telegram initData in dev, create guest order or use customer info only;
* return order id and totals.

## Telegram initData validation

Add service:

```java
TelegramInitDataValidator
```

Method:

```java
ValidatedTelegramUser validate(String initData, String botToken)
```

Rules:

* use raw initData from `X-Telegram-Init-Data`;
* validate hash using Telegram algorithm and bot token;
* validate auth_date freshness, e.g. max 24 hours;
* parse user id, first_name, username if valid;
* never trust initDataUnsafe from frontend directly;
* if invalid in prod mode, reject Telegram-linked identity;
* in dev mode allow guest order without Telegram user.

Config:

```yaml
commerce:
  mini-app:
    require-telegram-auth: ${COMMERCE_MINI_APP_REQUIRE_TELEGRAM_AUTH:false}
    init-data-max-age-seconds: ${COMMERCE_MINI_APP_INIT_DATA_MAX_AGE_SECONDS:86400}
```

If `require-telegram-auth=true`, order creation requires valid initData.
If false, guest checkout allowed.

## Telegram user linking

When valid initData is present:

* find or create User by:

  * shopId
  * telegramId/chatId from initData user id
* update username/firstName if present.
* Link CustomerOrder.user to this User.
* Bonus accrual later works on COMPLETED.

If guest checkout:

* CustomerOrder can have nullable user only if entity supports it.
* If current schema requires user not null, create a synthetic/guest User or require initData. Prefer minimal safe implementation:

  * if CustomerOrder.user is non-nullable, require valid Telegram initData for Mini App checkout.
  * But catalog browsing can work without auth.

## Security

* Storefront read endpoints are public by shopId but return only public data.
* Storefront order endpoint must validate product/shop/visibility/stock.
* Do not expose supplierPrice.
* Do not expose hidden products.
* Do not expose unapproved images.
* Do not expose admin data.
* Do not expose bot token.
* Do not expose Brave/DeepSeek/rembg configs.

## Admin orders

Orders created by Mini App must appear in existing Admin Orders page.

Add order source if useful:

```java
OrderSource:
- BOT
- MINI_APP
- ADMIN
```

If adding enum is too much, add nullable `source` string to CustomerOrder:

* `MINI_APP`
* `BOT`

Admin order details should show source if easy.

## Bot fallback catalog

Keep existing bot catalog.

But update menu text:

* `🛍 Открыть магазин`
* `🛒 Каталог в боте`

Do not remove existing text catalog.

## Build / deploy notes

Telegram Mini App needs a public HTTPS URL for real mobile testing.

Local options:

* ngrok;
* Cloudflare Tunnel;
* deployed frontend.

Add docs:

```text
COMMERCE_MINI_APP_PUBLIC_URL=https://your-public-domain
```

Bot sends WebApp URL:

```text
https://your-public-domain/store/{shopId}
```

For local browser testing:

* open `http://localhost:5173/store/{shopId}`;
* guest/dev mode can browse;
* checkout may require Telegram initData depending on config.

## Acceptance criteria

1. Bot shows `🛍 Открыть магазин`.
2. Clicking it opens Telegram Mini App storefront.
3. Mini App calls `ready()` and `expand()`.
4. Catalog grid shows visible active products.
5. Approved product images are displayed.
6. Products without approved images show clean placeholder.
7. Search works.
8. Brand filter works if brands exist.
9. Product details screen works.
10. Cart add/remove/quantity works.
11. Checkout creates CustomerOrder.
12. Admin Orders page shows Mini App orders.
13. Existing bot catalog still works as fallback.
14. Storefront APIs do not expose hidden products, supplierPrice or unapproved image candidates.
15. Telegram initData validation exists before trusting Telegram user id.
16. Backend build/tests pass.
17. Frontend build passes.

## Implementation order

Step A — Backend public storefront API:

* settings;
* products;
* product details;
* brands;
* order creation;
* initData validator.

Step B — Frontend Mini App:

* Storefront route;
* Telegram helper;
* catalog grid;
* product details;
* cart;
* checkout;
* success state.

Step C — Bot integration:

* WebApp button;
* menu updates;
* fallback catalog rename.

Step D — Smoke test:

* open browser route;
* open via Telegram WebApp URL;
* create order;
* confirm admin sees order.

## After implementation report

Run backend and frontend builds.

Report:

1. files changed;
2. new endpoints;
3. env vars needed;
4. how to configure COMMERCE_MINI_APP_PUBLIC_URL;
5. how to open Mini App from bot;
6. how to test in browser;
7. how to test in Telegram;
8. how order creation links to Telegram user;
9. remaining limitations.
