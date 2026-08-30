package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.commerce.ProductImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductImportBatchRepository extends JpaRepository<ProductImportBatch, Long> {

    List<ProductImportBatch> findByShopIdOrderByCreatedAtDesc(String shopId);
}
