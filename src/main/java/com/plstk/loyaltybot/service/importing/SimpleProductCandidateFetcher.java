package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *
 * <p>ADR-029 (six-bug hardening pass, follow-up): a single "longest token" was not enough to
 * disambiguate a specific product within a brand that itself has more members than {@code limit} -
 * e.g. among 305 "Chanel" products, the one row-distinctive detail (a volume like "100") never
 * appeared in the single longest token picked ("chanel" itself, shared by every product in that
 * brand), so the brand-only pool alone filled {@code limit} before the target's id was ever reached.
 * Every SQL query below is now run per significant token (not just the longest one) and every
 * fetched candidate accumulates a relevance score (more matched tokens = more relevant); the merged
 * pool is RANKED by that score - highest first - before being truncated to {@code limit}, so a
 * highly relevant candidate can never be silently dropped in favor of an arbitrary same-brand one
 * just because insertion order happened to fill the cap first.
 */
@Component
public class SimpleProductCandidateFetcher implements ProductCandidateFetcher {

    /** Bounds the number of extra per-token DB round-trips for one row; tokens are pre-ranked by specificity. */
    private static final int MAX_SIGNIFICANT_TOKENS = 5;

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
        List<String> significantTokens = significantTokens(row.searchName(), brandTokens);
        CacheKey key = new CacheKey(shopId, brandTokens, significantTokens, limit);
        return cache.computeIfAbsent(key, k -> search(k.shopId(), k.brandTokens(), k.significantTokens(), k.limit()));
    }

    private List<Product> search(String shopId, Set<String> brandTokens, List<String> significantTokens, int limit) {
        Map<Long, Product> byId = new LinkedHashMap<>();
        Map<Long, Integer> relevance = new HashMap<>();

        if (!brandTokens.isEmpty()) {
            // Most targeted signal: brand narrowed further by each individual significant token
            // (not just the single longest one, ADR-029) - when a single brand has more products
            // than `limit`, this finds the row's specific item within that brand even though the
            // brand-only query below alone could fill the whole page window before reaching it.
            for (String token : significantTokens) {
                for (Product p : productRepository.findByShopIdAndBrandTokenInAndNameToken(
                        shopId, brandTokens, token, PageRequest.of(0, limit))) {
                    byId.putIfAbsent(p.getId(), p);
                    relevance.merge(p.getId(), 3, Integer::sum);
                }
            }
            for (Product p : productRepository.findByShopIdAndBrandTokenIn(shopId, brandTokens, PageRequest.of(0, limit))) {
                byId.putIfAbsent(p.getId(), p);
                relevance.merge(p.getId(), 1, Integer::sum);
            }
        }
        // Deliberately NOT gated on `byId.size() < limit` (Stage 4 gap, docs/DECISIONS.md ADR-024):
        // a brand with more products than `limit` fills the brand-only step above on its own, which
        // used to skip this name-based widening entirely and permanently hide any candidate the
        // brand-only query's page window happened to cut off.
        for (String token : significantTokens) {
            for (Product p : productRepository.findByShopIdAndNameContainingIgnoreCase(
                    shopId, token, PageRequest.of(0, limit))) {
                byId.putIfAbsent(p.getId(), p);
                relevance.merge(p.getId(), 2, Integer::sum);
            }
        }
        if (byId.size() < limit) {
            // Last-resort backfill: keeps behavior sane for rows with no usable brand/name signal at
            // all (e.g. a blank/garbage row) instead of returning zero candidates outright. Scored 0
            // (lowest priority) - it never outranks a genuinely content-matched candidate.
            int remaining = limit - byId.size();
            for (Product p : productRepository.findByShopIdOrderByIdAsc(shopId, PageRequest.of(0, remaining + byId.size()))) {
                if (byId.size() >= limit) {
                    break;
                }
                byId.putIfAbsent(p.getId(), p);
            }
        }
        // ADR-029: rank by accumulated relevance (most matched tokens first) before truncating to
        // `limit` - a naive insertion-order truncation let a large pool of same-brand-only matches
        // (each with a low, single-point score) fill the whole cap before a specifically
        // token-matched candidate (inserted later, but far more relevant) ever got a chance to
        // survive. Ties break by id for determinism.
        return byId.values().stream()
                .sorted(Comparator
                        .comparingInt((Product p) -> relevance.getOrDefault(p.getId(), 0))
                        .reversed()
                        .thenComparing(Product::getId))
                .limit(limit)
                .toList();
    }

    /**
     * Every distinctive token in the row's cleaned search name - not just the single longest one
     * (ADR-029 fix for the case where the longest token is the brand name itself, shared by every
     * product in that brand, e.g. "chanel" among 305 Chanel products - a distinguishing detail like
     * a volume number would never be picked at all under the old single-token rule). A token
     * already covered by {@code brandTokens} is skipped (redundant with the brand-only query).
     * Purely-numeric tokens (e.g. "100" from a volume) are kept at any length, since digits are
     * highly specific even when short; alphabetic tokens need &gt;=4 chars to filter out common short
     * words. Ranked longest/most-specific first and capped at {@link #MAX_SIGNIFICANT_TOKENS} to
     * bound the number of extra per-token queries for one row.
     */
    private List<String> significantTokens(String searchName, Set<String> brandTokens) {
        if (searchName == null || searchName.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : searchName.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty() || brandTokens.contains(token)) {
                continue;
            }
            boolean numeric = token.chars().allMatch(Character::isDigit);
            if (numeric || token.length() >= 4) {
                tokens.add(token);
            }
        }
        return tokens.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_SIGNIFICANT_TOKENS)
                .toList();
    }

    @Override
    public void invalidateForNewBatch(String shopId) {
        cache.keySet().removeIf(key -> key.shopId().equals(shopId));
    }

    private record CacheKey(String shopId, Set<String> brandTokens, List<String> significantTokens, int limit) {
    }
}
