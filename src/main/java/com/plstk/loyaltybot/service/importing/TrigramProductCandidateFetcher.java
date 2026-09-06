package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Performant candidate pre-filter for large catalogs on real PostgreSQL, using the {@code pg_trgm}
 * extension's {@code similarity()} function (see {@code V19} migration) to shortlist by name
 * similarity at the database level instead of loading the whole shop catalog into memory. The final
 * explainable score is still always computed afterward by {@link CandidateScorer} in Java - this
 * class only changes performance/recall on very large catalogs, never the scoring contract.
 *
 * <p>Only wired in when {@code supplier-import.matching.pg-trgm-enabled=true} (see
 * {@code CandidateFetcherConfig}); never selected by default, since Flyway is disabled and a
 * production database cannot be assumed to already have the {@code pg_trgm} extension/index applied.
 */
@Component
public class TrigramProductCandidateFetcher implements ProductCandidateFetcher {

    private final ProductRepository productRepository;

    public TrigramProductCandidateFetcher(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public List<Product> fetchCandidates(String shopId, NormalizedRowData row, int limit) {
        String searchName = row.searchName() != null ? row.searchName() : "";
        return productRepository.findTopByShopIdOrderBySimilarity(shopId, searchName, limit);
    }
}
