package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default, portable candidate fetcher: plain JPQL, shop-scoped, bounded by
 * {@code supplier-import.matching.candidate-fetch-limit}. Works identically on H2 (tests/dev) and
 * PostgreSQL (prod) without requiring the {@code pg_trgm} extension, so the whole normalization +
 * candidate search pipeline is fully testable without Docker/Testcontainers. Wired in unless
 * {@code supplier-import.matching.pg-trgm-enabled=true} selects {@link TrigramProductCandidateFetcher}
 * instead (see {@code CandidateFetcherConfig}).
 */
@Component
public class SimpleProductCandidateFetcher implements ProductCandidateFetcher {

    /**
     * The query below depends only on {@code (shopId, limit)}, never on {@code row} - every row in
     * a batch would otherwise trigger an identical "SELECT ... FROM products WHERE shop_id = ?
     * ORDER BY id LIMIT ?" query (a several-thousand-row batch issuing several thousand identical
     * queries). Cached per shop for the duration of one batch's row loop only - {@link
     * ImportBatchNormalizingService#normalizeBatch} evicts this shop's entry via {@link
     * #invalidateForNewBatch} before the loop starts, so every batch always sees a fresh catalog
     * snapshot (e.g. a NEW_PRODUCT created by an earlier batch for the same shop) and no test using a
     * fixed shopId can leak a stale result into a later, unrelated test/batch.
     */
    private final ConcurrentHashMap<CacheKey, List<Product>> cache = new ConcurrentHashMap<>();

    private final ProductRepository productRepository;

    public SimpleProductCandidateFetcher(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public List<Product> fetchCandidates(String shopId, NormalizedRowData row, int limit) {
        CacheKey key = new CacheKey(shopId, limit);
        return cache.computeIfAbsent(key,
                k -> productRepository.findByShopIdOrderByIdAsc(k.shopId(), PageRequest.of(0, k.limit())));
    }

    @Override
    public void invalidateForNewBatch(String shopId) {
        cache.keySet().removeIf(key -> key.shopId().equals(shopId));
    }

    private record CacheKey(String shopId, int limit) {
    }
}
