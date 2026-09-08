package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Shop-scoped, admin-managed brand alias (Stage 4 of the production-hardening pass):
 * {@code canonicalBrand} <-> {@code alias} pairs used to widen candidate search and brand-match
 * scoring across misspellings/transliterations (e.g. {@code Chanel}/{@code Шанель}/{@code Channel}).
 * Deliberately NOT a hardcoded, single-group constant in Java: an operator can add/remove groups per
 * shop via the admin API/UI as new supplier misspellings are observed, and no single seed group is
 * "the only one that will ever exist" (see {@code BrandAliasResolver}).
 *
 * <p>{@code normalizedAlias} is the lookup key ({@link com.plstk.loyaltybot.service.importing.BrandNormalizer}:
 * lower-case, {@code ё->е} folded, Unicode NFKC, whitespace/hyphen/quote collapsed) - stored
 * denormalized so the resolver's per-shop index can be built with a single indexed query instead of
 * normalizing every row on every read.
 */
@Entity
@Table(name = "brand_aliases", indexes = {
    @Index(name = "idx_brand_aliases_shop_id", columnList = "shopId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_brand_aliases_shop_normalized_alias", columnNames = {"shopId", "normalizedAlias"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    /** Preferred/display spelling for this alias group (e.g. {@code "Chanel"}). */
    @Column(nullable = false, length = 255)
    private String canonicalBrand;

    /** As-entered alias spelling (e.g. {@code "Шанель"}). May equal {@code canonicalBrand} itself. */
    @Column(nullable = false, length = 255)
    private String alias;

    /** Normalized lookup key for {@code alias} - see class javadoc. */
    @Column(nullable = false, length = 255)
    private String normalizedAlias;

    /** Operator email/id who created this row; {@code "SYSTEM"} for migration-seeded defaults. */
    @Column(length = 255)
    private String createdBy;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
