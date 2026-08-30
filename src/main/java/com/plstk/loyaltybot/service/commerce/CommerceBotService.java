package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.entity.commerce.*;
import com.plstk.loyaltybot.repository.CustomerOrderRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.service.UserService;
import com.plstk.loyaltybot.telegram.TelegramApiClient;
import com.plstk.loyaltybot.telegram.TelegramContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommerceBotService {

    private static final int CATALOG_PAGE_SIZE = 8;
    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final String CB_CAT_PAGE = "cat:p:";
    private static final String CB_PRODUCT = "prd:";
    private static final String CB_CART_ADD = "cart:add:";
    private static final String CB_CART_DEC = "cart:dec:";
    private static final String CB_CART_RM = "cart:rm:";
    private static final String CB_CART_VIEW = "cart:view";
    private static final String CB_CART_CLEAR = "cart:clear";
    private static final String CB_CHECKOUT_START = "checkout:start";
    private static final String CB_CHECKOUT_PICKUP = "checkout:pickup";
    private static final String CB_CHECKOUT_DELIVERY = "checkout:delivery";
    private static final String CB_ORDER_CONFIRM = "order:confirm";
    private static final String CB_ORDER_CANCEL = "order:cancel";

    private final TelegramApiClient telegramApiClient;
    private final ProductRepository productRepository;
    private final CartService cartService;
    private final OrderService orderService;
    private final CustomerOrderRepository customerOrderRepository;
    private final UserService userService;
    private final ImageStorageService imageStorageService;

    private final Map<String, PendingCheckout> pendingCheckouts = new ConcurrentHashMap<>();

    public boolean isCommerceCallback(String data) {
        if (data == null) {
            return false;
        }
        return data.startsWith(CB_CAT_PAGE)
                || data.startsWith(CB_PRODUCT)
                || data.startsWith(CB_CART_ADD)
                || data.startsWith(CB_CART_DEC)
                || data.startsWith(CB_CART_RM)
                || data.equals(CB_CART_VIEW)
                || data.equals(CB_CART_CLEAR)
                || data.equals(CB_CHECKOUT_START)
                || data.equals(CB_CHECKOUT_PICKUP)
                || data.equals(CB_CHECKOUT_DELIVERY)
                || data.equals(CB_ORDER_CONFIRM)
                || data.equals(CB_ORDER_CANCEL);
    }

    public boolean isCommerceCommand(String text) {
        if (text == null) {
            return false;
        }
        return text.equals("/catalog") || text.equals("/cart") || text.equals("/orders");
    }

    public static final String CATALOG_MENU_BUTTON = "🛒 Каталог в боте";

    public static boolean isCatalogMenuButton(String text) {
        return CATALOG_MENU_BUTTON.equals(text);
    }

    public void handleCallback(TelegramContext ctx, CallbackQuery callbackQuery, User user) {
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        String shopId = ctx.getShopId();

        requireRegisteredCustomer(user);

        if (data.startsWith(CB_CAT_PAGE)) {
            int page = parsePage(data.substring(CB_CAT_PAGE.length()));
            showCatalog(ctx, chatId, shopId, page);
        } else if (data.startsWith(CB_PRODUCT)) {
            long productId = Long.parseLong(data.substring(CB_PRODUCT.length()));
            showProductCard(ctx, chatId, shopId, productId);
        } else if (data.startsWith(CB_CART_ADD)) {
            long productId = Long.parseLong(data.substring(CB_CART_ADD.length()));
            addToCart(ctx, chatId, shopId, user, productId);
        } else if (data.startsWith(CB_CART_DEC)) {
            long productId = Long.parseLong(data.substring(CB_CART_DEC.length()));
            cartService.decreaseQuantity(shopId, user, productId, 1);
            sendText(ctx, chatId, "Количество уменьшено.", cartActionsKeyboard(productId));
        } else if (data.startsWith(CB_CART_RM)) {
            long productId = Long.parseLong(data.substring(CB_CART_RM.length()));
            cartService.removeProduct(shopId, user, productId);
            sendText(ctx, chatId, "Товар удалён из корзины.", inlineButton("🛒 Корзина", CB_CART_VIEW));
        } else if (data.equals(CB_CART_VIEW)) {
            showCart(ctx, chatId, shopId, user);
        } else if (data.equals(CB_CART_CLEAR)) {
            cartService.clearCart(shopId, user);
            pendingCheckouts.remove(checkoutKey(shopId, chatId));
            sendText(ctx, chatId, "Корзина очищена.", catalogEntryKeyboard());
        } else if (data.equals(CB_CHECKOUT_START)) {
            startCheckout(ctx, chatId, shopId, user);
        } else if (data.equals(CB_CHECKOUT_PICKUP)) {
            pendingCheckouts.put(checkoutKey(shopId, chatId),
                    new PendingCheckout(DeliveryType.PICKUP, null));
            sendText(ctx, chatId, "Самовызов выбран.\n\nПодтвердите заказ:", confirmOrderKeyboard());
        } else if (data.equals(CB_CHECKOUT_DELIVERY)) {
            pendingCheckouts.put(checkoutKey(shopId, chatId),
                    new PendingCheckout(DeliveryType.COURIER, null));
            userService.updateUserState(user, User.UserState.AWAITING_ORDER_ADDRESS);
            sendText(ctx, chatId, "Введите адрес доставки одним сообщением:", null);
        } else if (data.equals(CB_ORDER_CONFIRM)) {
            confirmOrder(ctx, chatId, shopId, user);
        } else if (data.equals(CB_ORDER_CANCEL)) {
            cancelCheckout(ctx, chatId, shopId, user);
        }
    }

    public boolean handleMessage(TelegramContext ctx, User user, String messageText) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();

        if (user.getState() == User.UserState.AWAITING_ORDER_ADDRESS) {
            if (isLoyaltyMenuButton(messageText) || isCommerceCommand(messageText) || isCatalogMenuButton(messageText)) {
                return false;
            }
            handleDeliveryAddress(ctx, chatId, shopId, user, messageText);
            return true;
        }

        if (messageText == null) {
            return false;
        }

        if (isCommerceCommand(messageText) || isCatalogMenuButton(messageText)) {
            requireRegisteredCustomer(user);
            switch (messageText) {
                case "/catalog", CATALOG_MENU_BUTTON -> showCatalog(ctx, chatId, shopId, 0);
                case "/cart" -> showCart(ctx, chatId, shopId, user);
                case "/orders" -> showOrders(ctx, chatId, shopId, user);
            }
            return true;
        }

        return false;
    }

    private void handleDeliveryAddress(TelegramContext ctx, Long chatId, String shopId, User user, String address) {
        if (!StringUtils.hasText(address)) {
            sendText(ctx, chatId, "Пожалуйста, отправьте адрес текстом.", null);
            return;
        }

        String key = checkoutKey(shopId, chatId);
        PendingCheckout pending = pendingCheckouts.get(key);
        if (pending == null || pending.deliveryType() != DeliveryType.COURIER) {
            userService.updateUserState(user, User.UserState.REGISTERED);
            sendText(ctx, chatId, "Оформление заказа не начато. Откройте корзину.", catalogEntryKeyboard());
            return;
        }

        pendingCheckouts.put(key, new PendingCheckout(DeliveryType.COURIER, address.trim()));
        userService.updateUserState(user, User.UserState.REGISTERED);
        sendText(ctx, chatId, "Адрес сохранён.\n\nПодтвердите заказ:", confirmOrderKeyboard());
    }

    private void showCatalog(TelegramContext ctx, Long chatId, String shopId, int page) {
        Pageable pageable = PageRequest.of(page, CATALOG_PAGE_SIZE);
        Page<Product> products = productRepository.findByShopIdAndVisibleTrueAndActiveTrue(shopId, pageable);

        if (products.isEmpty()) {
            sendText(ctx, chatId, "Каталог пока пуст.", catalogEntryKeyboard());
            return;
        }

        StringBuilder text = new StringBuilder("🛒 *Каталог*\n\n");
        List<List<Map<String, String>>> rows = new ArrayList<>();

        for (Product product : products.getContent()) {
            text.append("• ")
                    .append(escapeMarkdown(product.getBrand()))
                    .append(" — ")
                    .append(formatPrice(product.getSalePrice()))
                    .append(" ₽\n");

            String label = truncate(product.getBrand() + " " + product.getName(), 40);
            rows.add(List.of(button(label, CB_PRODUCT + product.getId())));
        }

        List<Map<String, String>> navRow = new ArrayList<>();
        if (page > 0) {
            navRow.add(button("⬅️ Назад", CB_CAT_PAGE + (page - 1)));
        }
        if (products.hasNext()) {
            navRow.add(button("➡️ Далее", CB_CAT_PAGE + (page + 1)));
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        rows.add(List.of(button("🛒 Корзина", CB_CART_VIEW)));

        sendText(ctx, chatId, text.toString(), Map.of("inline_keyboard", rows));
    }

    private void showProductCard(TelegramContext ctx, Long chatId, String shopId, long productId) {
        Product product = productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        if (!Boolean.TRUE.equals(product.getVisible()) || !Boolean.TRUE.equals(product.getActive())) {
            sendText(ctx, chatId, "Товар недоступен.", catalogEntryKeyboard());
            return;
        }

        String caption = buildProductCaption(product);
        Map<String, Object> keyboard = productCardKeyboard(productId);

        if (ImageStatus.APPROVED.equals(product.getImageStatus()) && StringUtils.hasText(product.getMainImageUrl())) {
            TelegramApiClient.MessageResult photoResult = sendProductPhoto(
                    ctx, chatId, product.getMainImageUrl(), caption, keyboard);
            if (!photoResult.ok()) {
                log.warn("sendPhoto failed for product {}, fallback to text: {}", productId, photoResult.error());
                sendText(ctx, chatId, caption, keyboard);
            }
        } else {
            sendText(ctx, chatId, caption, keyboard);
        }
    }

    private void addToCart(TelegramContext ctx, Long chatId, String shopId, User user, long productId) {
        try {
            cartService.addProduct(shopId, user, productId, 1);
            sendText(ctx, chatId, "✅ Товар добавлен в корзину.", cartActionsKeyboard(productId));
        } catch (IllegalArgumentException e) {
            sendText(ctx, chatId, "❌ " + e.getMessage(), productCardKeyboard(productId));
        }
    }

    private void showCart(TelegramContext ctx, Long chatId, String shopId, User user) {
        CartService.CartView cart = cartService.getCart(shopId, user);
        if (cart.items().isEmpty()) {
            sendText(ctx, chatId, "🛒 Корзина пуста.", catalogEntryKeyboard());
            return;
        }

        StringBuilder text = new StringBuilder("🛒 *Корзина*\n\n");
        int index = 1;
        for (CartService.CartLineView line : cart.items()) {
            text.append(index++).append(". ")
                    .append(escapeMarkdown(line.brand())).append(" ")
                    .append(escapeMarkdown(line.name())).append(" — ")
                    .append(line.quantity()).append(" × ")
                    .append(formatPrice(line.unitPrice())).append(" ₽\n");
        }
        text.append("\n*Итого:* ").append(formatPrice(cart.total())).append(" ₽");

        List<List<Map<String, String>>> rows = new ArrayList<>();
        rows.add(List.of(
                button("Оформить", CB_CHECKOUT_START),
                button("Очистить", CB_CART_CLEAR)
        ));
        rows.add(List.of(button("🛒 Каталог", CB_CAT_PAGE + "0")));

        sendText(ctx, chatId, text.toString(), Map.of("inline_keyboard", rows));
    }

    private void startCheckout(TelegramContext ctx, Long chatId, String shopId, User user) {
        CartService.CartView cart = cartService.getCart(shopId, user);
        if (cart.items().isEmpty()) {
            sendText(ctx, chatId, "Корзина пуста.", catalogEntryKeyboard());
            return;
        }

        sendText(ctx, chatId, "Выберите способ получения:", Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                button("🏪 Самовывоз", CB_CHECKOUT_PICKUP),
                                button("🚚 Доставка", CB_CHECKOUT_DELIVERY)
                        ),
                        List.of(button("❌ Отмена", CB_ORDER_CANCEL))
                )
        ));
    }

    private void confirmOrder(TelegramContext ctx, Long chatId, String shopId, User user) {
        String key = checkoutKey(shopId, chatId);
        PendingCheckout pending = pendingCheckouts.get(key);
        if (pending == null) {
            sendText(ctx, chatId, "Сначала выберите способ получения.", inlineButton("🛒 Корзина", CB_CART_VIEW));
            return;
        }

        if (pending.deliveryType() == DeliveryType.COURIER && !StringUtils.hasText(pending.deliveryAddress())) {
            userService.updateUserState(user, User.UserState.AWAITING_ORDER_ADDRESS);
            sendText(ctx, chatId, "Введите адрес доставки одним сообщением:", null);
            return;
        }

        try {
            CustomerOrder order = orderService.createOrderFromCart(
                    shopId,
                    user,
                    new OrderService.CreateOrderRequest(
                            pending.deliveryType(),
                            pending.deliveryAddress(),
                            null,
                            null));

            pendingCheckouts.remove(key);
            userService.updateUserState(user, User.UserState.REGISTERED);

            sendText(ctx, chatId,
                    "✅ *Заказ оформлен!*\n\n" +
                            "Номер заказа: *#" + order.getId() + "*\n" +
                            "Сумма: *" + formatPrice(order.getTotalToPay()) + "* ₽\n\n" +
                            "Мы свяжемся с вами для подтверждения.",
                    catalogEntryKeyboard());
        } catch (IllegalArgumentException e) {
            sendText(ctx, chatId, "❌ Не удалось создать заказ: " + e.getMessage(), inlineButton("🛒 Корзина", CB_CART_VIEW));
        }
    }

    private void cancelCheckout(TelegramContext ctx, Long chatId, String shopId, User user) {
        resetCheckoutState(shopId, chatId, user);
        sendText(ctx, chatId, "Оформление заказа отменено.", catalogEntryKeyboard());
    }

    public void resetCheckoutState(String shopId, Long chatId, User user) {
        pendingCheckouts.remove(checkoutKey(shopId, chatId));
        if (user.getState() == User.UserState.AWAITING_ORDER_ADDRESS) {
            userService.updateUserState(user, User.UserState.REGISTERED);
        }
    }

    private boolean isLoyaltyMenuButton(String text) {
        if (text == null) {
            return false;
        }
        return text.equals("🛍 Я совершаю покупку")
                || text.equals("📊 Мой статус")
                || text.equals("📜 История покупок")
                || text.equals("🎁 Мои скидки")
                || text.equals("☕ Мои штампы")
                || text.equals("🏆 Достижения")
                || text.equals("🔑 Ввести код покупки")
                || text.equals("🎁 Код награды")
                || text.equals("📢 Отправить скидку")
                || text.equals("🎁 Активные акции")
                || text.equals("📊 Статистика");
    }

    private void showOrders(TelegramContext ctx, Long chatId, String shopId, User user) {
        List<CustomerOrder> orders = customerOrderRepository.findByShopIdAndUserOrderByCreatedAtDesc(shopId, user);
        if (orders.isEmpty()) {
            sendText(ctx, chatId, "У вас пока нет заказов.", catalogEntryKeyboard());
            return;
        }

        StringBuilder text = new StringBuilder("📦 *Ваши заказы*\n\n");
        for (CustomerOrder order : orders.stream().limit(10).toList()) {
            text.append("*#").append(order.getId()).append("* — ")
                    .append(orderStatusLabel(order.getStatus())).append("\n")
                    .append(formatPrice(order.getTotalToPay())).append(" ₽ · ")
                    .append(order.getCreatedAt().format(ORDER_DATE_FORMAT)).append("\n\n");
        }

        sendText(ctx, chatId, text.toString(), catalogEntryKeyboard());
    }

    private String buildProductCaption(Product product) {
        StringBuilder caption = new StringBuilder();
        caption.append("🛍 *").append(escapeMarkdown(product.getBrand())).append("*\n");
        caption.append(escapeMarkdown(product.getName())).append("\n\n");
        caption.append("Цена: *").append(formatPrice(product.getSalePrice())).append(" ₽*");

        if (product.getOldPrice() != null && product.getOldPrice().compareTo(BigDecimal.ZERO) > 0) {
            caption.append("\nСтарая цена: ").append(formatPrice(product.getOldPrice())).append(" ₽");
        }

        caption.append("\nФормат: *").append(availabilityLabel(product.getAvailabilityMode())).append("*");
        return caption.toString();
    }

    private Map<String, Object> productCardKeyboard(long productId) {
        return Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                button("➕ В корзину", CB_CART_ADD + productId),
                                button("🛒 Корзина", CB_CART_VIEW)
                        ),
                        List.of(button("⬅️ Каталог", CB_CAT_PAGE + "0"))
                )
        );
    }

    private Map<String, Object> cartActionsKeyboard(long productId) {
        return Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                button("🛒 Корзина", CB_CART_VIEW),
                                button("➕ Ещё", CB_CART_ADD + productId)
                        ),
                        List.of(button("⬅️ К товару", CB_PRODUCT + productId))
                )
        );
    }

    private Map<String, Object> confirmOrderKeyboard() {
        return Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                button("✅ Подтвердить", CB_ORDER_CONFIRM),
                                button("❌ Отмена", CB_ORDER_CANCEL)
                        )
                )
        );
    }

    private Map<String, Object> catalogEntryKeyboard() {
        return Map.of(
                "inline_keyboard", List.of(
                        List.of(button("🛒 Каталог", CB_CAT_PAGE + "0"))
                )
        );
    }

    private Map<String, Object> inlineButton(String text, String callbackData) {
        return Map.of("inline_keyboard", List.of(List.of(button(text, callbackData))));
    }

    private Map<String, String> button(String text, String callbackData) {
        return Map.of("text", text, "callback_data", callbackData);
    }

    private void sendText(TelegramContext ctx, Long chatId, String text, Object keyboard) {
        telegramApiClient.sendMessage(ctx.getBotToken(), chatId, text, keyboard, "Markdown");
    }

    private TelegramApiClient.MessageResult sendProductPhoto(
            TelegramContext ctx,
            Long chatId,
            String mainImageUrl,
            String caption,
            Map<String, Object> keyboard) {
        var localBytes = imageStorageService.readBytesFromPublicUrl(mainImageUrl);
        if (localBytes.isPresent()) {
            String filename = mainImageUrl.substring(mainImageUrl.lastIndexOf('/') + 1);
            return telegramApiClient.sendPhotoBytes(
                    ctx.getBotToken(), chatId, localBytes.get(), filename, caption, keyboard, "Markdown");
        }

        return telegramApiClient.sendPhoto(
                ctx.getBotToken(), chatId, resolvePhotoUrl(mainImageUrl), caption, keyboard, "Markdown");
    }

    private String resolvePhotoUrl(String mainImageUrl) {
        if (mainImageUrl.startsWith("http://") || mainImageUrl.startsWith("https://")) {
            return mainImageUrl;
        }
        return imageStorageService.toAbsoluteUrl(mainImageUrl);
    }

    private void requireRegisteredCustomer(User user) {
        if (user.getRole() == User.UserRole.ADMIN) {
            throw new IllegalArgumentException("Каталог доступен клиентам");
        }
        if (user.getState() != User.UserState.REGISTERED
                && user.getState() != User.UserState.AWAITING_ORDER_ADDRESS) {
            throw new IllegalArgumentException("Сначала завершите регистрацию");
        }
    }

    private int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return Math.max(page, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String checkoutKey(String shopId, Long chatId) {
        return shopId + ":" + chatId;
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "0";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        DecimalFormat format = new DecimalFormat("#,##0", symbols);
        return format.format(price);
    }

    private String availabilityLabel(AvailabilityMode mode) {
        if (mode == null) {
            return "под заказ";
        }
        return switch (mode) {
            case IN_STOCK -> "в наличии";
            case OUT_OF_STOCK -> "нет в наличии";
            case PREORDER -> "под заказ";
        };
    }

    private String orderStatusLabel(OrderStatus status) {
        if (status == null) {
            return "—";
        }
        return switch (status) {
            case CREATED -> "Создан";
            case CONFIRMED -> "Подтверждён";
            case PACKING -> "Сборка";
            case READY_FOR_PICKUP -> "Готов к выдаче";
            case SHIPPED -> "Отправлен";
            case COMPLETED -> "Завершён";
            case CANCELLED -> "Отменён";
            case DRAFT -> "Черновик";
        };
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("[", "\\[");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 1) + "…";
    }

    private record PendingCheckout(DeliveryType deliveryType, String deliveryAddress) {}
}
