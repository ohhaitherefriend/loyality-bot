package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.entity.commerce.AvailabilityMode;
import com.plstk.loyaltybot.entity.commerce.CartItem;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.CartItemRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public CartView getCart(String shopId, User user) {
        validateUserShop(shopId, user);
        List<CartItem> items = cartItemRepository.findByShopIdAndUser(shopId, user);
        return toCartView(items);
    }

    @Transactional
    public CartView addProduct(String shopId, User user, Long productId, int quantity) {
        validateUserShop(shopId, user);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        Product product = requirePurchasableProduct(shopId, productId);
        validateStock(product, quantity, 0);

        CartItem item = cartItemRepository.findByShopIdAndUserAndProduct(shopId, user, product)
                .orElse(null);
        if (item == null) {
            item = CartItem.builder()
                    .shopId(shopId)
                    .user(user)
                    .product(product)
                    .quantity(quantity)
                    .build();
        } else {
            validateStock(product, quantity, item.getQuantity());
            item.setQuantity(item.getQuantity() + quantity);
        }

        cartItemRepository.save(item);
        return getCart(shopId, user);
    }

    @Transactional
    public CartView decreaseQuantity(String shopId, User user, Long productId, int amount) {
        validateUserShop(shopId, user);
        if (amount <= 0) {
            amount = 1;
        }

        Product product = requireProduct(shopId, productId);
        CartItem item = cartItemRepository.findByShopIdAndUserAndProduct(shopId, user, product)
                .orElseThrow(() -> new IllegalArgumentException("Product is not in cart"));

        int newQuantity = item.getQuantity() - amount;
        if (newQuantity <= 0) {
            cartItemRepository.delete(item);
        } else {
            item.setQuantity(newQuantity);
            cartItemRepository.save(item);
        }
        return getCart(shopId, user);
    }

    @Transactional
    public CartView removeProduct(String shopId, User user, Long productId) {
        validateUserShop(shopId, user);
        Product product = requireProduct(shopId, productId);
        cartItemRepository.findByShopIdAndUserAndProduct(shopId, user, product)
                .ifPresent(cartItemRepository::delete);
        return getCart(shopId, user);
    }

    @Transactional
    public void clearCart(String shopId, User user) {
        validateUserShop(shopId, user);
        cartItemRepository.deleteByShopIdAndUser(shopId, user);
    }

    @Transactional(readOnly = true)
    public List<CartItem> getCartItems(String shopId, User user) {
        validateUserShop(shopId, user);
        return cartItemRepository.findByShopIdAndUser(shopId, user);
    }

    private CartView toCartView(List<CartItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        List<CartLineView> lines = items.stream().map(item -> {
            BigDecimal unitPrice = item.getProduct().getSalePrice() != null
                    ? item.getProduct().getSalePrice()
                    : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            return new CartLineView(
                    item.getProduct().getId(),
                    item.getProduct().getBrand(),
                    item.getProduct().getName(),
                    unitPrice,
                    item.getQuantity(),
                    lineTotal);
        }).toList();

        for (CartLineView line : lines) {
            total = total.add(line.lineTotal());
        }
        return new CartView(lines, total);
    }

    private Product requireProduct(String shopId, Long productId) {
        return productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    private Product requirePurchasableProduct(String shopId, Long productId) {
        Product product = requireProduct(shopId, productId);
        validatePurchasable(product);
        return product;
    }

    private void validatePurchasable(Product product) {
        if (!Boolean.TRUE.equals(product.getActive())) {
            throw new IllegalArgumentException("Product is inactive");
        }
        if (!Boolean.TRUE.equals(product.getVisible())) {
            throw new IllegalArgumentException("Product is not available");
        }
        if (AvailabilityMode.OUT_OF_STOCK.equals(product.getAvailabilityMode())) {
            throw new IllegalArgumentException("Product is out of stock");
        }
    }

    private void validateStock(Product product, int addQuantity, int existingQuantity) {
        if (!AvailabilityMode.IN_STOCK.equals(product.getAvailabilityMode())) {
            return;
        }
        Integer stock = product.getStockQuantity();
        if (stock == null || stock < existingQuantity + addQuantity) {
            throw new IllegalArgumentException("Insufficient stock");
        }
    }

    private void validateUserShop(String shopId, User user) {
        if (user == null || user.getShopId() == null || !shopId.equals(user.getShopId())) {
            throw new IllegalArgumentException("User does not belong to shop");
        }
    }

    public record CartLineView(
            Long productId,
            String brand,
            String name,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal
    ) {}

    public record CartView(List<CartLineView> items, BigDecimal total) {}
}
