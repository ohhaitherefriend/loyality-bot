package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;

import java.util.List;

/**
 * Narrows the shop's catalog down to a bounded candidate pool BEFORE Java-side scoring
 * ({@link CandidateScorer}). This is the only database-specific part of candidate search - the
 * final explainable score is always computed the same way in Java, in every environment, so the two
 * implementations only differ in performance/recall on large catalogs, never in scoring behavior.
 */
public interface ProductCandidateFetcher {

    /**
     * @return up to {@code limit} products for this shop only (tenant isolation is mandatory - no
     *     implementation may return another shop's products), in no particular guaranteed order;
     *     final ranking is done by {@link CandidateScorer} afterward.
     */
    List<Product> fetchCandidates(String shopId, NormalizedRowData row, int limit);

    /**
     * Called once by {@link ImportBatchNormalizingService#normalizeBatch} for {@code shopId} before
     * that batch's row loop begins. Implementations that cache a row-independent result across calls
     * (e.g. {@link SimpleProductCandidateFetcher}) must drop any such cached entry for this shop here,
     * so every batch is guaranteed to see a fresh catalog snapshot. Implementations whose result
     * genuinely depends on {@code row} (e.g. {@link TrigramProductCandidateFetcher}) never cache
     * across rows in the first place, so the default no-op is correct for them.
     */
    default void invalidateForNewBatch(String shopId) {
    }
}
