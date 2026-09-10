package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default, portable candidate fetcher: plain JPQL/derived queries, shop-scoped, bounded by
 * {@code supplier-import.matching.candidate-fetch-limit}. Works identically on H2 (tests/dev) and
 * PostgreSQL (prod) without requiring the {@code pg_trgm} extension. Wired in unless
 * {@code supplier-import.matching.pg-trgm-enabled=true} selects {@link TrigramProductCandidateFetcher}
 * instead (see {@code CandidateFetcherConfig}).
 *
 * <p>Stage 4 fix: the original implementation returned the first {@code limit} products ordered by
 * id, regardless of the row being matched - for any catalog bigger than that limit, a product whose
 * id happened to fall outside the window was permanently unreachable by fuzzy/AI matching, silently
 * causing missed matches and duplicate {@code NEW_PRODUCT}s. Candidates are now shortlisted BY
 * CONTENT: brand (exact + {@link BrandAliasResolver alias/transliteration}-expanded) first, then a
 * name-substring widening, and only backfilled with the old id-ordered pool if that still leaves
 * room under {@code limit} (e.g. a row with neither a usable brand nor name token).
 */
@Component
public class SimpleProductCandidateFetcher implements ProductCandidateFetcher {

    /**
     * Cache key includes the row's own brand/name signal (unlike the pre-Stage-4 version, which
     * cached one shop-wide list reused by every row): the shortlist is now content-dependent, but
     * many rows in one supplier file typically share a brand, so this still avoids re-querying per
     * row. Evicted per shop at the start of every batch (see {@link #invalidateForNewBatch}) and,
     * indirectly, whenever {@code BrandAliasResolver}'s own per-shop index is invalidated (alias data
     * changing mid-batch is not a supported scenario).
     */
    private final ConcurrentHashMap<CacheKey, List<Product>> cache = new ConcurrentHashMap<>();

    private final ProductRepository productRepository;
    private final BrandAliasResolver brandAliasResolver;
    private final BrandNormalizer brandNormalizer;

    public SimpleProductCandidateFetcher(
            ProductRepository productRepository, BrandAliasResolver brandAliasResolver, BrandNormalizer brandNormalizer) {
        this.productRepository = productRepository;
        this.brandAliasResolver = brandAliasResolver;
        this.brandNormalizer = brandNormalizer;
    }

    @Override
    public List<Product> fetchCandidates(String shopId, NormalizedRowData row, int limit) {
        Set<String> brandTokens = row.brand() != null ? brandAliasResolver.expand(shopId, row.brand()) : Set.of();
        String nameToken = longestToken(row.searchName());
        CacheKey key = new CacheKey(shopId, brandTokens, nameToken, limit);
        return cache.computeIfAbsent(key, k -> search(k.shopId(), k.brandTokens(), k.nameToken(), k.limit()));
    }

    private List<Product> search(String shopId, Set<String> brandTokens, String nameToken, int limit) {
        Map<Long, Product> byId = new LinkedHashMap<>();

        if (!brandTokens.isEmpty() && nameToken != null) {
            // Most targeted signal first, inserted before the wider brand-only query below: when a
            // single brand has more than `limit` products, this narrows within that brand by name
            // too, so the specific item this row's name matches is found (and survives the final
            // truncation) even if it would have fallen outside the brand-only query's page window.
            for (Product p : productRepository.findByShopIdAndBrandTokenInAndNameToken(
                    shopId, brandTokens, nameToken, PageRequest.of(0, limit))) {
                byId.putIfAbsent(p.getId(), p);
            }
        }
        if (!brandTokens.isEmpty()) {
            for (Product p : productRepository.findByShopIdAndBrandTokenIn(shopId, brandTokens, PageRequest.of(0, limit))) {
                byId.putIfAbsent(p.getId(), p);
            }
        }
        if (nameToken != null) {
            // Deliberately NOT gated on `byId.size() < limit` (Stage 4 gap, docs/DECISIONS.md
            // ADR-024): a brand with more products than `limit` fills the brand-only step above on
            // its own, which used to skip this name-based widening entirely and permanently hide any
            // candidate the brand-only query's page window happened to cut off.
            for (Product p : productRepository.findByShopIdAndNameContainingIgnoreCase(
                    shopId, nameToken, PageRequest.of(0, limit))) {
                byId.putIfAbsent(p.getId(), p);
            }
        }
        if (byId.size() < limit) {
            // Last-resort backfill: keeps behavior sane for rows with no usable brand/name signal at
            // all (e.g. a blank/garbage row) instead of returning zero candidates outright. This never
            // hides a content-matched candidate - it only ever adds MORE candidates on top.
            int remaining = limit - byId.size();
            for (Product p : productRepository.findByShopIdOrderByIdAsc(shopId, PageRequest.of(0, remaining + byId.size()))) {
                if (byId.size() >= limit) {
                    break;
                }
                byId.putIfAbsent(p.getId(), p);
            }
        }
        // Content-matched candidates (brand+name, brand-only, name-only) were inserted before the
        // id-ordered backfill, so truncating to `limit` here never drops a content match in favor
        // of an arbitrary id-ordered one, even when the combined pool exceeds `limit`.
        return new LinkedHashSet<>(byId.values()).stream().limit(limit).toList();
    }

    /** Longest word (&gt;=4 chars) in the row's cleaned search name - the best single discriminator for a LIKE shortlist. */
    private String longestToken(String searchName) {
        if (searchName == null || searchName.isBlank()) {
            return null;
        }
        String best = null;
        for (String token : searchName.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && (best == null || token.length() > best.length())) {
                best = token;
            }
        }
        return best;
    }

    @Override
    public void invalidateForNewBatch(String shopId) {
        cache.keySet().removeIf(key -> key.shopId().equals(shopId));
    }

    private record CacheKey(String shopId, Set<String> brandTokens, String nameToken, int limit) {
    }
}
