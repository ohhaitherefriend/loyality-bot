package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.BrandAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandAliasRepository extends JpaRepository<BrandAlias, Long> {

    List<BrandAlias> findByShopIdOrderByCanonicalBrandAscAliasAsc(String shopId);

    Optional<BrandAlias> findByShopIdAndId(String shopId, Long id);

    Optional<BrandAlias> findByShopIdAndNormalizedAlias(String shopId, String normalizedAlias);

    long countByShopId(String shopId);
}
