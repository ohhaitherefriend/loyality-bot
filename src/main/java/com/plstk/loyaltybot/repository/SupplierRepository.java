package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByShopId(String shopId);

    Optional<Supplier> findByShopIdAndId(String shopId, Long id);

    Optional<Supplier> findByShopIdAndName(String shopId, String name);
}
