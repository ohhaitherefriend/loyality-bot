package com.plstk.loyaltybot.entity.commerce;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_images", indexes = {
    @Index(name = "idx_product_images_shop_product", columnList = "shopId, product_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ImageType imageType = ImageType.CANDIDATE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ImageStatus status = ImageStatus.DOWNLOADED;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ImageSourceType sourceType;

    @Column(length = 2048)
    private String sourceUrl;

    @Column(length = 2048)
    private String sourcePageUrl;

    @Column(length = 255)
    private String sourceDomain;

    @Column(length = 2048)
    private String originalUrl;

    @Column(length = 2048)
    private String normalizedUrl;

    @Column(precision = 10, scale = 2)
    private BigDecimal confidence;

    @Column(length = 64)
    private String matchedBy;

    @Column(nullable = false)
    @Builder.Default
    private Boolean approvedByAdmin = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean aiNormalized = false;

    @Column(length = 512)
    private String rejectReason;

    private Integer visualQualityScore;

    @Column(length = 64)
    private String qualityDecision;

    @Column(columnDefinition = "TEXT")
    private String qualityWarnings;

    @Column(length = 64)
    private String normalizationProvider;

    @Column(nullable = false)
    @Builder.Default
    private Boolean backgroundRemoved = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean scaleNormalized = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean angleNormalized = false;

    @Column(length = 512)
    private String manualReviewReason;

    @Column(length = 512)
    private String rankerReason;

    @Column(columnDefinition = "TEXT")
    private String rankerWarnings;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
