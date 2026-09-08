package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shop-scoped, admin-managed brand-alias signal (Stage 4 of the production-hardening pass) used to
 * widen fuzzy candidate search and to tag {@code matchedAttributes} with {@code "brandAlias"}
 * (docs/PROJECT_CONTEXT.md "Пример matching"). Deliberately NOT a global string replacement:
 * {@link RowAttributeNormalizer} never calls this class, so a row's stored
 * {@code normalizedData.brand} always keeps the as-seen token (e.g. a misspelled "Channel" is never
 * rewritten to "Chanel"). This class only answers "could this row's brand also plausibly refer to
 * that other brand?" for candidate widening/scoring.
 *
 * <p>Alias data is entirely admin-managed per shop via {@link BrandAlias} (CRUD:
 * {@code BrandAliasAdminController}) - there is no hardcoded single alias group baked into this
 * class. As a generic safety net for brands nobody has configured yet, this resolver also treats two
 * brands as aliases when their {@link BrandNormalizer#transliterate transliterated} forms are
 * identical (e.g. "Dior" / "Диор"), which catches phonetically-regular Cyrillic/Latin spelling pairs
 * without needing an explicit row for every possible brand; irregular pairs (e.g. "Chanel" /
 * "Шанель", which are not a letter-for-letter transliteration of one another) still need an explicit
 * {@link BrandAlias} row.
 */
@Component
public class BrandAliasResolver {

    private final BrandAliasRepository repository;
    private final BrandNormalizer brandNormalizer;

    /** normalizedAlias -&gt; normalized canonical group key, rebuilt per shop on first use after invalidation. */
    private final ConcurrentHashMap<String, Map<String, String>> indexByShop = new ConcurrentHashMap<>();

    public BrandAliasResolver(BrandAliasRepository repository, BrandNormalizer brandNormalizer) {
        this.repository = repository;
        this.brandNormalizer = brandNormalizer;
    }

    /** Must be called whenever a shop's {@link BrandAlias} rows may have changed (new batch, CRUD write). */
    public void invalidateForNewBatch(String shopId) {
        indexByShop.remove(shopId);
    }

    /**
     * @return true if {@code brandA} and {@code brandB} are different (case/normalization-insensitive)
     *     but known - or transliteration-equivalent - aliases of one another. Identical normalized
     *     tokens are not "aliases" of themselves for this purpose - callers should check exact
     *     equality separately.
     */
    public boolean areAliases(String shopId, String brandA, String brandB) {
        if (shopId == null || brandA == null || brandB == null) {
            return false;
        }
        String a = brandNormalizer.normalize(brandA);
        String b = brandNormalizer.normalize(brandB);
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        Map<String, String> index = indexFor(shopId);
        String canonicalA = index.get(a);
        String canonicalB = index.get(b);
        if (canonicalA != null && canonicalA.equals(canonicalB)) {
            return true;
        }
        return brandNormalizer.transliterate(a).equals(brandNormalizer.transliterate(b));
    }

    /**
     * @return every known alias/canonical spelling (normalized) that {@code brand} belongs to,
     *     including its own normalized and transliterated forms - used by {@code
     *     SimpleProductCandidateFetcher} to widen a SQL-level brand shortlist beyond a plain equality
     *     check, without ever relying on catalog row order.
     */
    public Set<String> expand(String shopId, String brand) {
        Set<String> result = new LinkedHashSet<>();
        if (shopId == null || brand == null) {
            return result;
        }
        String normalized = brandNormalizer.normalize(brand);
        if (normalized == null || normalized.isEmpty()) {
            return result;
        }
        result.add(normalized);
        result.add(brandNormalizer.transliterate(normalized));

        Map<String, String> index = indexFor(shopId);
        String canonical = index.get(normalized);
        if (canonical != null) {
            for (Map.Entry<String, String> entry : index.entrySet()) {
                if (canonical.equals(entry.getValue())) {
                    result.add(entry.getKey());
                }
            }
        }
        return result;
    }

    private Map<String, String> indexFor(String shopId) {
        return indexByShop.computeIfAbsent(shopId, this::buildIndex);
    }

    private Map<String, String> buildIndex(String shopId) {
        List<BrandAlias> rows = repository.findByShopIdOrderByCanonicalBrandAscAliasAsc(shopId);
        Map<String, String> index = new java.util.HashMap<>();
        for (BrandAlias row : rows) {
            String canonicalKey = brandNormalizer.normalize(row.getCanonicalBrand());
            index.put(row.getNormalizedAlias(), canonicalKey);
            // The canonical spelling is implicitly a member of its own group even if no row aliases
            // it to itself, so a row's brand exactly matching the canonical spelling still groups
            // with every other alias of that canonical brand.
            index.putIfAbsent(canonicalKey, canonicalKey);
        }
        return index;
    }
}
