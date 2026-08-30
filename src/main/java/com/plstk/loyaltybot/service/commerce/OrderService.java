package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.entity.commerce.*;
import com.plstk.loyaltybot.repository.CustomerOrderRepository;
import com.plstk.loyaltybot.repository.OrderItemRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.UserRepository;
import com.plstk.loyaltybot.service.BonusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final CartService cartService;
    private final BonusService bonusService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<CustomerOrder> listOrders(String shopId, OrderStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (status != null) {
            return customerOrderRepository.findByShopIdAndStatus(shopId, status, pageable);
        }
        return customerOrderRepository.findByShopId(shopId, pageable);
    }

    @Transactional(readOnly = true)
    public OrderDetails getOrder(String shopId, Long orderId) {
        CustomerOrder order = requireOrder(shopId, orderId);
        List<OrderItem> items = orderItemRepository.findByShopIdAndOrderId(shopId, orderId);
        return new OrderDetails(order, items);
    }

    @Transactional
    public CustomerOrder createOrderFromCart(String shopId, User user, CreateOrderRequest request) {
        validateUserShop(shopId, user);

        List<CartItem> cartItems = cartService.getCartItems(shopId, user);
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        BigDecimal itemsTotal = BigDecimal.ZERO;
        List<PreparedLine> preparedLines = new ArrayList<>();

        for (CartItem cartItem : cartItems) {
            Product product = productRepository.findByShopIdAndIdForUpdate(shopId, cartItem.getProduct().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + cartItem.getProduct().getId()));

            validatePurchasable(product);
            validateStock(product, cartItem.getQuantity());

            BigDecimal unitPrice = product.getSalePrice() != null ? product.getSalePrice() : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(cartItem.getQuantity()));
            itemsTotal = itemsTotal.add(lineTotal);

            preparedLines.add(new PreparedLine(product, cartItem.getQuantity(), unitPrice, lineTotal));
        }

        BigDecimal bonusSpent = BigDecimal.ZERO;
        if (request.bonusSpent() != null && request.bonusSpent().compareTo(BigDecimal.ZERO) > 0) {
            bonusSpent = bonusService.spendBonus(user, itemsTotal, request.bonusSpent())
                    .setScale(0, RoundingMode.FLOOR);
        }

        BigDecimal totalToPay = itemsTotal.subtract(bonusSpent);
        if (totalToPay.compareTo(BigDecimal.ZERO) < 0) {
            totalToPay = BigDecimal.ZERO;
        }

        CustomerOrder order = CustomerOrder.builder()
                .shopId(shopId)
                .user(user)
                .status(OrderStatus.CREATED)
                .itemsTotal(itemsTotal)
                .bonusSpent(bonusSpent)
                .totalToPay(totalToPay)
                .bonusAccrued(BigDecimal.ZERO)
                .customerPhone(user.getPhoneNumber())
                .customerName(buildCustomerName(user))
                .deliveryType(request.deliveryType())
                .deliveryAddress(request.deliveryAddress())
                .customerComment(request.customerComment())
                .source(OrderSource.BOT)
                .build();
        order = customerOrderRepository.save(order);

        persistOrderLines(order, preparedLines);

        cartService.clearCart(shopId, user);
        log.info("Created order {} for shopId={}, user={}, total={}", order.getId(), shopId, user.getId(), totalToPay);
        return order;
    }

    @Transactional
    public CustomerOrder createOrderFromItems(
            String shopId,
            User user,
            List<OrderLineItem> items,
            CreateOrderRequest request,
            OrderSource source,
            String customerPhone,
            String customerName) {

        validateUserShop(shopId, user);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Order items cannot be empty");
        }

        BigDecimal itemsTotal = BigDecimal.ZERO;
        List<PreparedLine> preparedLines = new ArrayList<>();

        for (OrderLineItem lineItem : items) {
            if (lineItem.quantity() <= 0) {
                throw new IllegalArgumentException("Invalid quantity for product: " + lineItem.productId());
            }

            Product product = productRepository.findByShopIdAndIdForUpdate(shopId, lineItem.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + lineItem.productId()));

            validatePurchasable(product);
            validateStock(product, lineItem.quantity());

            BigDecimal unitPrice = product.getSalePrice() != null ? product.getSalePrice() : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(lineItem.quantity()));
            itemsTotal = itemsTotal.add(lineTotal);

            preparedLines.add(new PreparedLine(product, lineItem.quantity(), unitPrice, lineTotal));
        }

        BigDecimal bonusSpent = BigDecimal.ZERO;
        if (request.bonusSpent() != null && request.bonusSpent().compareTo(BigDecimal.ZERO) > 0) {
            bonusSpent = bonusService.spendBonus(user, itemsTotal, request.bonusSpent())
                    .setScale(0, RoundingMode.FLOOR);
        }

        BigDecimal totalToPay = itemsTotal.subtract(bonusSpent);
        if (totalToPay.compareTo(BigDecimal.ZERO) < 0) {
            totalToPay = BigDecimal.ZERO;
        }

        String resolvedPhone = StringUtils.hasText(customerPhone) ? customerPhone : user.getPhoneNumber();
        String resolvedName = StringUtils.hasText(customerName) ? customerName : buildCustomerName(user);

        CustomerOrder order = CustomerOrder.builder()
                .shopId(shopId)
                .user(user)
                .status(OrderStatus.CREATED)
                .itemsTotal(itemsTotal)
                .bonusSpent(bonusSpent)
                .totalToPay(totalToPay)
                .bonusAccrued(BigDecimal.ZERO)
                .customerPhone(resolvedPhone)
                .customerName(resolvedName)
                .deliveryType(request.deliveryType())
                .deliveryAddress(request.deliveryAddress())
                .customerComment(request.customerComment())
                .source(source != null ? source : OrderSource.BOT)
                .build();
        order = customerOrderRepository.save(order);

        persistOrderLines(order, preparedLines);

        log.info("Created order {} from items for shopId={}, user={}, source={}, total={}",
                order.getId(), shopId, user.getId(), order.getSource(), totalToPay);
        return order;
    }

    private void persistOrderLines(CustomerOrder order, List<PreparedLine> preparedLines) {
        for (PreparedLine line : preparedLines) {
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .product(line.product())
                    .skuSnapshot(line.product().getSupplierArticle())
                    .barcodeSnapshot(line.product().getBarcode())
                    .brandSnapshot(line.product().getBrand())
                    .nameSnapshot(line.product().getName())
                    .priceSnapshot(line.unitPrice())
                    .availabilityModeSnapshot(line.product().getAvailabilityMode())
                    .quantity(line.quantity())
                    .lineTotal(line.lineTotal())
                    .build();
            orderItemRepository.save(orderItem);

            if (AvailabilityMode.IN_STOCK.equals(line.product().getAvailabilityMode())) {
                Integer stock = line.product().getStockQuantity();
                line.product().setStockQuantity(stock - line.quantity());
                productRepository.save(line.product());
            }
        }
    }

    @Transactional
    public CustomerOrder updateStatus(String shopId, Long orderId, OrderStatus newStatus) {
        CustomerOrder order = requireOrder(shopId, orderId);
        OrderStatus oldStatus = order.getStatus();

        if (oldStatus == newStatus) {
            return order;
        }

        if (OrderStatus.COMPLETED.equals(oldStatus) || OrderStatus.CANCELLED.equals(oldStatus)) {
            throw new IllegalArgumentException("Order status cannot be changed from " + oldStatus);
        }

        if (OrderStatus.CANCELLED.equals(newStatus)) {
            restoreStock(shopId, orderId);
            order.setCancelledAt(LocalDateTime.now());
        }

        if (OrderStatus.COMPLETED.equals(newStatus)) {
            completeOrder(order);
            order.setCompletedAt(LocalDateTime.now());
        }

        order.setStatus(newStatus);
        return customerOrderRepository.save(order);
    }

    private void completeOrder(CustomerOrder order) {
        if (order.getBonusAccrued() != null && order.getBonusAccrued().compareTo(BigDecimal.ZERO) > 0) {
            log.info("Skipping bonus accrual for order {} - already accrued {}", order.getId(), order.getBonusAccrued());
            return;
        }

        User user = order.getUser();
        BigDecimal totalToPay = order.getTotalToPay() != null ? order.getTotalToPay() : BigDecimal.ZERO;
        BigDecimal accrued = bonusService.accrueBonus(user, totalToPay).setScale(0, RoundingMode.FLOOR);
        order.setBonusAccrued(accrued);

        user.recordPurchase(totalToPay, false);
        userRepository.save(user);

        log.info("Completed order {} with bonus accrued {}", order.getId(), order.getBonusAccrued());
    }

    private void restoreStock(String shopId, Long orderId) {
        List<OrderItem> items = orderItemRepository.findByShopIdAndOrderId(shopId, orderId);
        for (OrderItem item : items) {
            if (!AvailabilityMode.IN_STOCK.equals(item.getAvailabilityModeSnapshot())) {
                continue;
            }
            productRepository.findByShopIdAndIdForUpdate(shopId, item.getProduct().getId())
                    .ifPresent(product -> {
                        Integer stock = product.getStockQuantity();
                        int restored = stock != null ? stock : 0;
                        product.setStockQuantity(restored + item.getQuantity());
                        productRepository.save(product);
                    });
        }
    }

    private void validatePurchasable(Product product) {
        if (!Boolean.TRUE.equals(product.getActive())) {
            throw new IllegalArgumentException("Product is inactive: " + product.getId());
        }
        if (!Boolean.TRUE.equals(product.getVisible())) {
            throw new IllegalArgumentException("Product is not visible: " + product.getId());
        }
        if (AvailabilityMode.OUT_OF_STOCK.equals(product.getAvailabilityMode())) {
            throw new IllegalArgumentException("Product is out of stock: " + product.getId());
        }
    }

    private void validateStock(Product product, int quantity) {
        if (!AvailabilityMode.IN_STOCK.equals(product.getAvailabilityMode())) {
            return;
        }
        Integer stock = product.getStockQuantity();
        if (stock == null || stock < quantity) {
            throw new IllegalArgumentException("Insufficient stock for product: " + product.getId());
        }
    }

    private CustomerOrder requireOrder(String shopId, Long orderId) {
        return customerOrderRepository.findByShopIdAndId(shopId, orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    private void validateUserShop(String shopId, User user) {
        if (user == null || user.getShopId() == null || !shopId.equals(user.getShopId())) {
            throw new IllegalArgumentException("User does not belong to shop");
        }
    }

    private String buildCustomerName(User user) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? user.getLastName() : "";
        String name = (firstName + " " + lastName).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }

    private record PreparedLine(Product product, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    public record CreateOrderRequest(
            DeliveryType deliveryType,
            String deliveryAddress,
            String customerComment,
            BigDecimal bonusSpent
    ) {}

    public record OrderLineItem(Long productId, int quantity) {}

    public record OrderDetails(CustomerOrder order, List<OrderItem> items) {}
}
