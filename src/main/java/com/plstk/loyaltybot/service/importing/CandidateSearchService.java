package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Ties {@link ProductCandidateFetcher} (DB-specific pre-filter), {@link RowAttributeNormalizer}
 * (normalizes each fetched {@code Product} the same way a row is normalized) and
 * {@link CandidateScorer} (explainable Java-side scoring) together into one ordered candidate list
 * per shop-scoped row. Used both for the "safe fingerprint" deterministic stage and the fuzzy
 * top-N stage by {@link DeterministicMatchResolver} - one fetch+normalize+score pass serves both.
 */
@Service
public class CandidateSearchService {

    private final ProductCandidateFetcher candidateFetcher;
    private final RowAttributeNormalizer normalizer;
    private final CandidateScorer scorer;
    private final SupplierImportProperties properties;
    private final BrandAliasResolver brandAliasResolver;

    public CandidateSearchService(
            ProductCandidateFetcher candidateFetcher,
            RowAttributeNormalizer normalizer,
            CandidateScorer scorer,
            SupplierImportProperties properties,
            BrandAliasResolver brandAliasResolver) {
        this.candidateFetcher = candidateFetcher;
        this.normalizer = normalizer;
        this.scorer = scorer;
        this.properties = properties;
        this.brandAliasResolver = brandAliasResolver;
    }

    /**
     * Must be called once per shop before that shop's batch row loop begins - see
     * {@link ProductCandidateFetcher#invalidateForNewBatch}.
     */
    public void startNewBatch(String shopId) {
        candidateFetcher.invalidateForNewBatch(shopId);
        brandAliasResolver.invalidateForNewBatch(shopId);
    }

    /** @return every fetched candidate, scored and sorted by {@code totalScore} descending (unbounded). */
    public List<ScoredCandidate> search(String shopId, NormalizedRowData row) {
        SupplierImportProperties.Matching cfg = properties.getMatching();
        List<Product> fetched = candidateFetcher.fetchCandidates(shopId, row, cfg.getCandidateFetchLimit());

        return fetched.stream()
                .map(product -> scorer.score(shopId, product.getId(), product.getName(), row, normalizer.normalizeProduct(shopId, product)))
                .sorted(Comparator.comparing(ScoredCandidate::totalScore).reversed())
                .toList();
    }

    /** @return the top {@code maxCandidates} (config), filtering out near-zero noise below the threshold. */
    public List<ScoredCandidate> topCandidates(List<ScoredCandidate> scored) {
        SupplierImportProperties.Matching cfg = properties.getMatching();
        return scored.stream()
                .filter(c -> c.totalScore().doubleValue() >= cfg.getMinSimilarityThreshold() || c.hasConflicts())
                .limit(cfg.getMaxCandidates())
                .toList();
    }
}
