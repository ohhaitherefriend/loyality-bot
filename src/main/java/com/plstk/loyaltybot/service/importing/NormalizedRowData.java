package com.plstk.loyaltybot.service.importing;

import java.math.BigDecimal;

/**
 * Structured attributes extracted from one supplier row's raw name/brand (or, for candidate
 * comparison, from a catalog {@code Product}'s name/brand) by {@link RowAttributeNormalizer}.
 *
 * <p>{@code brand} is always the as-seen token for display/audit purposes - it is NEVER globally
 * rewritten to a canonical spelling (e.g. a row's "Channel" stays "Channel" here).
 *
 * <p>{@code fingerprint} is a deterministic, lowercase, structural key built from an
 * alias-canonical brand identity (ADR-030, {@link BrandAliasResolver#canonicalKey}) + line (with
 * the recognized brand phrase itself already stripped out, ADR-030) + volume/unit/concentration/
 * shade/tester/set - used both to look up a previously confirmed {@code SupplierProductLink} and
 * to compare against on-the-fly normalized catalog candidates for the "safe fingerprint"
 * deterministic match stage. Its first segment is a literal {@code "v" + normalizationVersion}
 * marker, so a fingerprint computed by an older algorithm version can never be silently compared
 * equal (or unequal in a way that hides a real match) against one computed by a newer version.
 *
 * @param normalizationVersion the {@link RowAttributeNormalizer#NORMALIZATION_VERSION} that
 *     produced this instance. {@code null} only for instances deserialized from JSON persisted
 *     before this field existed - callers must treat that as "unknown version", not as version 1,
 *     since the field's ABSENCE is itself the signal that this data predates any versioning at all.
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
        String fingerprint,
        Integer normalizationVersion) {
}
