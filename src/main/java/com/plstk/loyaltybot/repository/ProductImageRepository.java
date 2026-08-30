package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.ImageType;
import com.plstk.loyaltybot.entity.commerce.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByShopIdAndProductIdOrderByCreatedAtDesc(String shopId, Long productId);

    Optional<ProductImage> findByShopIdAndProductIdAndId(String shopId, Long productId, Long imageId);

    Optional<ProductImage> findByShopIdAndProductIdAndImageTypeAndStatus(
            String shopId, Long productId, ImageType imageType, ImageStatus status);

    List<ProductImage> findByShopIdAndProductIdAndStatus(String shopId, Long productId, ImageStatus status);

    List<ProductImage> findByShopIdAndProduct_IdInAndNormalizedUrlIsNotNullOrderByCreatedAtDesc(
            String shopId, Collection<Long> productIds);
}
