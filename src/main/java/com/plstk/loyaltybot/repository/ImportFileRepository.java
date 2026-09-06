package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.ImportFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {

    Optional<ImportFile> findByShopIdAndSupplierSourceIdAndSha256(
            String shopId, Long supplierSourceId, String sha256);

    // ========== Prompt 07 operations UI dashboard ==========

    /** One {@code ImportFile} per ingested email attachment today - doubles as "emails processed" count. */
    long countByShopId(String shopId);

    long countByShopIdAndReceivedAtAfter(String shopId, LocalDateTime since);
}
