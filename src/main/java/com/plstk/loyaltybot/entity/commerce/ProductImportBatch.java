package com.plstk.loyaltybot.entity.commerce;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_import_batches", indexes = {
    @Index(name = "idx_product_import_batches_shop_id", columnList = "shopId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @Column(length = 512)
    private String filename;

    private LocalDate priceListDate;

    private Integer totalRows;

    private Integer importedCount;

    private Integer updatedCount;

    private Integer skippedCount;

    @Column(length = 32)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
