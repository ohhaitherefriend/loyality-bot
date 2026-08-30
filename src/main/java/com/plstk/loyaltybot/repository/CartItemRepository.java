package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.commerce.CartItem;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByShopIdAndUser(String shopId, User user);

    Optional<CartItem> findByShopIdAndUserAndProduct(String shopId, User user, Product product);

    void deleteByShopIdAndUser(String shopId, User user);
}
