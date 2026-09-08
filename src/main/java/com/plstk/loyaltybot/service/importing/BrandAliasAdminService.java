package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Shop-scoped CRUD for {@link BrandAlias} (Stage 4 of the production-hardening pass). Deliberately
 * thin - the only real business rule is "one alias spelling per shop" (case/normalization-insensitive,
 * enforced both here for a clean 400 and by the DB unique constraint as the final guard) - so an
 * operator adding "Шанель" twice gets a clear error instead of a confusing duplicate group.
 */
@Service
@RequiredArgsConstructor
public class BrandAliasAdminService {

    private final BrandAliasRepository repository;
    private final BrandNormalizer brandNormalizer;
    private final BrandAliasResolver brandAliasResolver;

    public List<BrandAlias> list(String shopId) {
        return repository.findByShopIdOrderByCanonicalBrandAscAliasAsc(shopId);
    }

    public BrandAlias create(String shopId, String canonicalBrand, String alias, String createdBy) {
        if (canonicalBrand == null || canonicalBrand.isBlank()) {
            throw new BrandAliasValidationException(
                    "Validation failed", java.util.Map.of("canonicalBrand", "must not be blank"));
        }
        if (alias == null || alias.isBlank()) {
            throw new BrandAliasValidationException(
                    "Validation failed", java.util.Map.of("alias", "must not be blank"));
        }
        String normalizedAlias = brandNormalizer.normalize(alias);
        if (repository.findByShopIdAndNormalizedAlias(shopId, normalizedAlias).isPresent()) {
            throw new BrandAliasValidationException(
                    "Validation failed", java.util.Map.of("alias", "already exists for this shop"));
        }
        BrandAlias saved = repository.save(BrandAlias.builder()
                .shopId(shopId)
                .canonicalBrand(canonicalBrand.trim())
                .alias(alias.trim())
                .normalizedAlias(normalizedAlias)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.now())
                .build());
        brandAliasResolver.invalidateForNewBatch(shopId);
        return saved;
    }

    public void delete(String shopId, Long id) {
        BrandAlias existing = repository.findByShopIdAndId(shopId, id)
                .orElseThrow(() -> new IllegalArgumentException("Brand alias " + id + " not found for shop " + shopId));
        repository.delete(existing);
        brandAliasResolver.invalidateForNewBatch(shopId);
    }
}
