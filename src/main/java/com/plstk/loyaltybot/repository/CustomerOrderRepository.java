package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.commerce.CustomerOrder;
import com.plstk.loyaltybot.entity.commerce.OrderStatus;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    @EntityGraph(attributePaths = "user")
    Page<CustomerOrder> findByShopId(String shopId, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<CustomerOrder> findByShopIdAndStatus(String shopId, OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Optional<CustomerOrder> findByShopIdAndId(String shopId, Long id);

    List<CustomerOrder> findByShopIdAndUserOrderByCreatedAtDesc(String shopId, User user);
}
