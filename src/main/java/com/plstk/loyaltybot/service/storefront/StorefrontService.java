package com.plstk.loyaltybot.service.storefront;

import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.entity.commerce.CustomerOrder;
import com.plstk.loyaltybot.entity.commerce.DeliveryType;
import com.plstk.loyaltybot.entity.commerce.OrderSource;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.BotInstanceRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.BotInstanceService;
import com.plstk.loyaltybot.service.ShopSettingsService;
import com.plstk.loyaltybot.service.SubscriptionService;
import com.plstk.loyaltybot.service.commerce.OrderService;
import com.plstk.loyaltybot.service.storefront.StorefrontProductMapper.StorefrontProductDto;
import com.plstk.loyaltybot.service.telegram.TelegramInitDataValidator;
import com.plstk.loyaltybot.service.telegram.TelegramInitDataValidator.ValidatedTelegramUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StorefrontService {

    private final ShopRepository shopRepository;
    private final ShopSettingsService shopSettingsService;
    private final ProductRepository productRepository;
    private final StorefrontProductMapper productMapper;
    private final OrderService orderService;
    private final StorefrontUserService storefrontUserService;
    private final BotInstanceRepository botInstanceRepository;
    private final BotInstanceService botInstanceService;
    private final TelegramInitDataValidator initDataValidator;
    private final SubscriptionService subscriptionService;

    public void ensureShopAccessible(String shopId) {
        if (!shopRepository.findByShopId(shopId).isPresent()) {
            throw new StorefrontNotFoundException("Shop not found");
        }
        if (!subscriptionService.isAccessGranted(shopId)) {
            throw new StorefrontUnavailableException("Shop is temporarily unavailable");
        }
    }

    @Transactional(readOnly = true)
    public StorefrontSettingsDto getSettings(String shopId) {
        ensureShopAccessible(shopId);
        Shop shop = shopRepository.findByShopId(shopId)
                .orElseThrow(() -> new StorefrontNotFoundException("Shop not found"));
        ShopSettings settings = shopSettingsService.getSettings(shopId);

        return new StorefrontSettingsDto(
                shopId,
                shop.getName(),
                Boolean.TRUE.equals(settings.getBonusPointsEnabled()),
                settings.getBonusCashbackPercent(),
                "Самовывоз доступен. Адрес уточняется при подтверждении заказа."
        );
    }

    @Transactional(readOnly = true)
    public StorefrontProductPageDto listProducts(
            String shopId, int page, int size, String query, String brand, String category) {

        ensureShopAccessible(shopId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "brand", "name"));
        Page<Product> products = productRepository.searchStorefrontProducts(
                shopId, blankToNull(query), blankToNull(brand), blankToNull(category), pageable);

        List<StorefrontProductDto> content = products.getContent().stream()
                .map(productMapper::toDto)
                .toList();

        return new StorefrontProductPageDto(
                content,
                products.getNumber(),
                products.getSize(),
                products.getTotalElements(),
                products.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public StorefrontProductDto getProduct(String shopId, Long productId) {
        ensureShopAccessible(shopId);
        Product product = productRepository.findByShopIdAndId(shopId, productId)
                .filter(p -> Boolean.TRUE.equals(p.getVisible()) && Boolean.TRUE.equals(p.getActive()))
                .orElseThrow(() -> new StorefrontNotFoundException("Product not found"));
        return productMapper.toDto(product);
    }

    @Transactional(readOnly = true)
    public List<String> listBrands(String shopId) {
        ensureShopAccessible(shopId);
        return productRepository.findDistinctBrandsByShopIdVisibleActive(shopId);
    }

    @Transactional
    public CustomerOrder createOrder(String shopId, String initData, StorefrontOrderRequest request) {
        ensureShopAccessible(shopId);

        if (!StringUtils.hasText(initData)) {
            throw new StorefrontAuthException("Telegram authentication required");
        }

        var botInstance = botInstanceRepository.findByShopId(shopId)
                .filter(b -> b.getIsActive() && b.getStatus() == com.plstk.loyaltybot.entity.BotInstance.BotStatus.ACTIVE)
                .orElseThrow(() -> new StorefrontUnavailableException("Shop bot is not configured"));

        String botToken = botInstanceService.getDecryptedToken(botInstance);
        ValidatedTelegramUser telegramUser = initDataValidator.validate(initData, botToken);

        validateOrderRequest(request);

        User user = storefrontUserService.findOrCreate(
                shopId, telegramUser, request.customerPhone(), request.customerName());

        List<OrderService.OrderLineItem> lineItems = request.items().stream()
                .map(item -> new OrderService.OrderLineItem(item.productId(), item.quantity()))
                .toList();

        return orderService.createOrderFromItems(
                shopId,
                user,
                lineItems,
                new OrderService.CreateOrderRequest(
                        request.deliveryType(),
                        request.deliveryAddress(),
                        request.comment(),
                        null),
                OrderSource.MINI_APP,
                request.customerPhone(),
                request.customerName());
    }

    private void validateOrderRequest(StorefrontOrderRequest request) {
        if (!StringUtils.hasText(request.customerName())) {
            throw new IllegalArgumentException("Customer name is required");
        }
        if (!StringUtils.hasText(request.customerPhone())) {
            throw new IllegalArgumentException("Customer phone is required");
        }
        if (request.deliveryType() == null) {
            throw new IllegalArgumentException("Delivery type is required");
        }
        if (DeliveryType.COURIER.equals(request.deliveryType()) && !StringUtils.hasText(request.deliveryAddress())) {
            throw new IllegalArgumentException("Delivery address is required for courier delivery");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Order items cannot be empty");
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record StorefrontSettingsDto(
            String shopId,
            String shopName,
            boolean bonusesEnabled,
            Integer bonusCashbackPercent,
            String pickupHint
    ) {}

    public record StorefrontProductPageDto(
            List<StorefrontProductDto> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    public record StorefrontOrderItemRequest(Long productId, int quantity) {}

    public record StorefrontOrderRequest(
            String customerName,
            String customerPhone,
            DeliveryType deliveryType,
            String deliveryAddress,
            String comment,
            List<StorefrontOrderItemRequest> items
    ) {}

    public record StorefrontOrderResponse(
            Long orderId,
            String status,
            java.math.BigDecimal itemsTotal,
            java.math.BigDecimal totalToPay
    ) {
        public static StorefrontOrderResponse from(CustomerOrder order) {
            return new StorefrontOrderResponse(
                    order.getId(),
                    order.getStatus().name(),
                    order.getItemsTotal(),
                    order.getTotalToPay());
        }
    }

    public static class StorefrontNotFoundException extends RuntimeException {
        public StorefrontNotFoundException(String message) {
            super(message);
        }
    }

    public static class StorefrontUnavailableException extends RuntimeException {
        public StorefrontUnavailableException(String message) {
            super(message);
        }
    }

    public static class StorefrontAuthException extends RuntimeException {
        public StorefrontAuthException(String message) {
            super(message);
        }
    }
}
