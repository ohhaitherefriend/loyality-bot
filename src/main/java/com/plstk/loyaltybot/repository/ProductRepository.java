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
     * Bounded shop-scoped candidate pool for {@code SimpleProductCandidateFetcher}: deliberately not
     * filtered by visible/active, since a previously-deactivated or hidden product must still be
     * matchable (it can be reactivated by a reappearing supplier offer per D-006).
     */
    List<Product> findByShopIdOrderByIdAsc(String shopId, Pageable pageable);

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
