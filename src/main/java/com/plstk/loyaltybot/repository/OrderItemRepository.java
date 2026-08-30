package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.commerce.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT oi FROM OrderItem oi JOIN FETCH oi.product "
            + "WHERE oi.order.id = :orderId AND oi.order.shopId = :shopId")
    List<OrderItem> findByShopIdAndOrderId(@Param("shopId") String shopId, @Param("orderId") Long orderId);
}
