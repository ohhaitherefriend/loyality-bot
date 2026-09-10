package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByShopId(String shopId, Pageable pageable);

    Optional<Product> findByShopIdAndId(String shopId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.shopId = :shopId AND p.id = :id")
    Optional<Product> findByShopIdAndIdForUpdate(@Param("shopId") String shopId, @Param("id") Long id);

    Optional<Product> findByShopIdAndSupplierGuid(String shopId, String supplierGuid);

    Optional<Product> findByShopIdAndBarcode(String shopId, String barcode);

    /**
     * Unlike {@link #findByShopIdAndBarcode}, returns every match instead of throwing on a
     * duplicate. A duplicate barcode within one shop is a data anomaly that must fall through to
     * fuzzy candidate search rather than being auto-matched to an arbitrary one of the two.
     */
    List<Product> findAllByShopIdAndBarcode(String shopId, String barcode);

    /**
     * Stage 4 deterministic matching step 3: exact supplier-article match against the catalog's own
     * {@code supplierArticle} column, for a supplier's very first batch before any {@code
     * SupplierProductLink} exists yet (e.g. the product was originally catalogued by a legacy manual
     * XLSX import - {@code ProductImportService} - that already recorded this same article). Like
     * {@link #findAllByShopIdAndBarcode}, returns every match instead of throwing: an ambiguous
     * (non-unique) article is a data anomaly that must fall through to fuzzy search, never be
     * auto-picked.
     */
    List<Product> findAllByShopIdAndSupplierArticle(String shopId, String supplierArticle);

    /**
     * Bounded shop-scoped candidate pool for {@code SimpleProductCandidateFetcher}: deliberately not
     * filtered by visible/active, since a previously-deactivated or hidden product must still be
     * matchable (it can be reactivated by a reappearing supplier offer per D-006). Stage 4: used only
     * as a last-resort backfill when the brand/name-token shortlist below returns fewer than the
     * configured limit - never the primary candidate source, since "first N by id" silently hides
     * every product beyond the Nth row of a large catalog.
     */
    List<Product> findByShopIdOrderByIdAsc(String shopId, Pageable pageable);

    /**
     * Stage 4 candidate shortlist, step 1: every product whose {@code brand} exactly matches one of
     * the caller's already-normalized/alias-expanded/transliterated brand tokens - found by content,
     * never by row id, so a matching brand at id 50000 in a 100000-row catalog is always reachable.
     */
    @Query("SELECT p FROM Product p WHERE p.shopId = :shopId AND LOWER(p.brand) IN :brandTokens ORDER BY p.id ASC")
    List<Product> findByShopIdAndBrandTokenIn(
            @Param("shopId") String shopId, @Param("brandTokens") java.util.Collection<String> brandTokens, Pageable pageable);

    /**
     * Stage 4 candidate shortlist, step 2: a name-substring shortlist (portable {@code LIKE}, no
     * {@code pg_trgm} dependency) used to widen the pool beyond an exact/aliased brand match - e.g. a
     * supplier's typo'd brand ("Diorr") still surfaces the real catalog product when the row and
     * product names otherwise overlap on a distinctive word.
     */
    List<Product> findByShopIdAndNameContainingIgnoreCase(String shopId, String nameToken, Pageable pageable);

    /**
     * Six-bug hardening pass: the most targeted shortlist, combining brand AND name-token in one
     * query. When a single brand already has more products than {@code limit}, the plain
     * brand-only query ({@link #findByShopIdAndBrandTokenIn}) can fill its own page window before
     * ever reaching the specific product a row's name would identify, and {@code
     * SimpleProductCandidateFetcher} used to skip its name-based step entirely once the brand step
     * alone reached {@code limit} - permanently hiding any candidate past that page for a large
     * brand (docs/DECISIONS.md ADR-024, reproduced as catalog item #301 within a 300+-item brand
     * never appearing as a candidate). Running this narrower brand+name query FIRST, and always
     * running the name-only query regardless of how many brand-only matches were already found,
     * closes that gap.
     */
    @Query("SELECT p FROM Product p WHERE p.shopId = :shopId AND LOWER(p.brand) IN :brandTokens "
            + "AND LOWER(p.name) LIKE LOWER(CONCAT('%', :nameToken, '%')) ORDER BY p.id ASC")
    List<Product> findByShopIdAndBrandTokenInAndNameToken(
            @Param("shopId") String shopId,
            @Param("brandTokens") java.util.Collection<String> brandTokens,
            @Param("nameToken") String nameToken,
            Pageable pageable);

    /**
     * PostgreSQL-only candidate pool for {@code TrigramProductCandidateFetcher}, requiring the
     * {@code pg_trgm} extension (see V19). Only ever invoked when
     * {@code supplier-import.matching.pg-trgm-enabled=true} explicitly selects that fetcher - never
     * exercised against H2, which has no {@code similarity()} function.
     */
    @Query(value = """
            SELECT * FROM products p
            WHERE p.shop_id = :shopId
            ORDER BY similarity(p.name, :searchName) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Product> findTopByShopIdOrderBySimilarity(
            @Param("shopId") String shopId, @Param("searchName") String searchName, @Param("limit") int limit);

    Optional<Product> findByShopIdAndSourceSheetAndSupplierArticle(
            String shopId, String sourceSheet, String supplierArticle);

    Page<Product> findByShopIdAndVisibleTrueAndActiveTrue(String shopId, Pageable pageable);

    @Query("""
            SELECT p FROM Product p
            WHERE p.shopId = :shopId
              AND (:visible IS NULL OR p.visible = :visible)
              AND (:active IS NULL OR p.active = :active)
              AND (:brand IS NULL OR :brand = '' OR LOWER(p.brand) = LOWER(:brand))
              AND (:missingImages IS NULL OR :missingImages = false OR p.imageStatus = :missingImageStatus)
              AND (
                  :query IS NULL OR :query = '' OR
                  LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR
                  LOWER(p.brand) LIKE LOWER(CONCAT('%', :query, '%')) OR
                  LOWER(p.barcode) LIKE LOWER(CONCAT('%', :query, '%')) OR
                  LOWER(p.supplierArticle) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            """)
    Page<Product> searchProducts(
            @Param("shopId") String shopId,
            @Param("query") String query,
            @Param("brand") String brand,
            @Param("visible") Boolean visible,
            @Param("active") Boolean active,
            @Param("missingImages") Boolean missingImages,
            @Param("missingImageStatus") ImageStatus missingImageStatus,
            Pageable pageable);

    @Query("SELECT DISTINCT p.brand FROM Product p WHERE p.shopId = :shopId AND p.brand IS NOT NULL AND p.brand <> '' ORDER BY p.brand")
    List<String> findDistinctBrandsByShopId(@Param("shopId") String shopId);

    List<Product> findByShopIdAndIdIn(String shopId, List<Long> ids);

    @Query("""
            SELECT p FROM Product p
            WHERE p.shopId = :shopId
              AND p.visible = true
              AND p.active = true
              AND (:brand IS NULL OR :brand = '' OR LOWER(p.brand) = LOWER(:brand))
              AND (:category IS NULL OR :category = '' OR LOWER(p.categoryPath) LIKE LOWER(CONCAT('%', :category, '%')))
              AND (
                  :query IS NULL OR :query = '' OR
                  LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR
                  LOWER(p.brand) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            """)
    Page<Product> searchStorefrontProducts(
            @Param("shopId") String shopId,
            @Param("query") String query,
            @Param("brand") String brand,
            @Param("category") String category,
            Pageable pageable);

    @Query("""
            SELECT DISTINCT p.brand FROM Product p
            WHERE p.shopId = :shopId
              AND p.visible = true
              AND p.active = true
              AND p.brand IS NOT NULL AND p.brand <> ''
            ORDER BY p.brand
            """)
    List<String> findDistinctBrandsByShopIdVisibleActive(@Param("shopId") String shopId);
}
