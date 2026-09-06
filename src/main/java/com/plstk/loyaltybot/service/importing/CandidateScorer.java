package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes an explainable similarity score between a supplier row and a candidate catalog product,
 * both already normalized by {@link RowAttributeNormalizer}. Runs entirely in Java so it behaves
 * identically on H2 (tests) and PostgreSQL (prod) - see {@link ProductCandidateFetcher} for the part
 * of candidate search that IS database-specific (only the pre-filter, not the score itself).
 *
 * <p>{@code nameSimilarity} uses the same algorithm PostgreSQL's {@code pg_trgm} extension uses: a
 * Dice coefficient over character trigrams of the two (padded) strings.
 */
@Component
public class CandidateScorer {

    private static final BigDecimal BRAND_EXACT_BONUS = new BigDecimal("0.15");
    private static final BigDecimal BRAND_ALIAS_BONUS = new BigDecimal("0.08");
    private static final BigDecimal ATTRIBUTE_MATCH_BONUS = new BigDecimal("0.05");
    private static final BigDecimal CONFLICT_PENALTY = new BigDecimal("0.50");

    private final BrandAliasResolver brandAliasResolver;
    private final CriticalAttributeConflictChecker conflictChecker;

    public CandidateScorer(BrandAliasResolver brandAliasResolver, CriticalAttributeConflictChecker conflictChecker) {
        this.brandAliasResolver = brandAliasResolver;
        this.conflictChecker = conflictChecker;
    }

    public ScoredCandidate score(Long productId, String productName, NormalizedRowData row, NormalizedRowData candidate) {
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        List<String> matchedAttributes = new ArrayList<>();

        BigDecimal nameSimilarity = trigramSimilarity(row.searchName(), candidate.searchName());
        components.put("nameSimilarity", nameSimilarity);

        BigDecimal brandBonus = BigDecimal.ZERO;
        if (equalsIgnoreCase(row.brand(), candidate.brand())) {
            brandBonus = BRAND_EXACT_BONUS;
            matchedAttributes.add("brand");
        } else if (brandAliasResolver.areAliases(row.brand(), candidate.brand())) {
            brandBonus = BRAND_ALIAS_BONUS;
            matchedAttributes.add("brandAlias");
        }
        components.put("brandBonus", brandBonus);

        BigDecimal attributeBonus = BigDecimal.ZERO;
        if (row.volumeValue() != null && candidate.volumeValue() != null
                && row.volumeValue().compareTo(candidate.volumeValue()) == 0
                && equalsIgnoreCase(row.volumeUnit(), candidate.volumeUnit())) {
            attributeBonus = attributeBonus.add(ATTRIBUTE_MATCH_BONUS);
            matchedAttributes.add("volume");
        }
        if (row.concentration() != null && row.concentration().equals(candidate.concentration())) {
            attributeBonus = attributeBonus.add(ATTRIBUTE_MATCH_BONUS);
            matchedAttributes.add("concentration");
        }
        if (row.shade() != null && equalsIgnoreCase(row.shade(), candidate.shade())) {
            attributeBonus = attributeBonus.add(ATTRIBUTE_MATCH_BONUS);
            matchedAttributes.add("shade");
        }
        components.put("attributeBonus", attributeBonus);

        List<String> conflicts = conflictChecker.findConflicts(row, candidate);
        BigDecimal conflictPenalty = CONFLICT_PENALTY.multiply(BigDecimal.valueOf(conflicts.size()));
        components.put("conflictPenalty", conflictPenalty.negate());

        BigDecimal total = nameSimilarity.add(brandBonus).add(attributeBonus).subtract(conflictPenalty);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            total = BigDecimal.ZERO;
        }
        if (total.compareTo(BigDecimal.ONE) > 0) {
            total = BigDecimal.ONE;
        }
        total = total.setScale(4, RoundingMode.HALF_UP);

        return new ScoredCandidate(productId, productName, total, components, matchedAttributes, conflicts, candidate);
    }

    /** Dice coefficient over 3-grams of space-padded strings - the same formula pg_trgm's similarity() uses. */
    public BigDecimal trigramSimilarity(String a, String b) {
        if (a == null || b == null) {
            return BigDecimal.ZERO;
        }
        Set<String> trigramsA = trigrams(a);
        Set<String> trigramsB = trigrams(b);
        if (trigramsA.isEmpty() || trigramsB.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> intersection = new HashSet<>(trigramsA);
        intersection.retainAll(trigramsB);
        double dice = (2.0 * intersection.size()) / (trigramsA.size() + trigramsB.size());
        return BigDecimal.valueOf(dice).setScale(4, RoundingMode.HALF_UP);
    }

    private Set<String> trigrams(String value) {
        String padded = "  " + value.trim().toLowerCase() + "  ";
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= padded.length() - 3; i++) {
            result.add(padded.substring(i, i + 3));
        }
        return result;
    }

    private boolean equalsIgnoreCase(String x, String y) {
        if (x == null || y == null) {
            return false;
        }
        return x.trim().equalsIgnoreCase(y.trim());
    }
}
