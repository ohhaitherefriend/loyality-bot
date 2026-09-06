package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.SupplierSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierSourceRepository extends JpaRepository<SupplierSource, Long> {

    List<SupplierSource> findByShopId(String shopId);

    Optional<SupplierSource> findByShopIdAndId(String shopId, Long id);

    List<SupplierSource> findByMailboxConnectionIdAndEnabledTrue(Long mailboxConnectionId);
}
