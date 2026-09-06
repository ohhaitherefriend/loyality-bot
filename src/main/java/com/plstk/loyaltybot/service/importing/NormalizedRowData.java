package com.plstk.loyaltybot.service.importing;

import java.math.BigDecimal;

/**
 * Structured attributes extracted from one supplier row's raw name/brand (or, for candidate
 * comparison, from a catalog {@code Product}'s name/brand) by {@link RowAttributeNormalizer}.
 *
 * <p>{@code brand} is always the as-seen token - it is NEVER globally rewritten to a canonical
 * spelling (e.g. a row's "Channel" stays "Channel"); brand aliasing is a scoped candidate-search
 * signal only, applied later by {@link BrandAliasResolver} / {@link CandidateScorer}, never here.
 *
 * <p>{@code fingerprint} is a deterministic, lowercase, structural key built only from
 * brand+line+volume+unit+concentration+shade+tester+set - used both to look up a previously
 * confirmed {@code SupplierProductLink} and to compare against on-the-fly normalized catalog
 * candidates for the "safe fingerprint" deterministic match stage.
 */
public record NormalizedRowData(
        String brand,
        String line,
        String variant,
        BigDecimal volumeValue,
        String volumeUnit,
        String concentration,
        String shade,
        boolean tester,
        boolean set,
        String externalSku,
        String barcode,
        BigDecimal supplierPrice,
        Integer stock,
        String searchName,
        String fingerprint) {
}
