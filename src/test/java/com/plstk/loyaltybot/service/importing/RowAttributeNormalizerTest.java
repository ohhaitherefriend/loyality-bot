package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowAttributeNormalizerTest {

    private static final String SHOP_ID = "shop-1";

    private final BrandAliasRepository brandAliasRepository = mock(BrandAliasRepository.class);
    private final BrandAliasResolver brandAliasResolver =
            new BrandAliasResolver(brandAliasRepository, new BrandNormalizer());
    private final RowAttributeNormalizer normalizer = new RowAttributeNormalizer(brandAliasResolver);

    @Test
    void extractsVolumeAndUnit_acrossSpellingVariants() {
        assertVolume("Крем для лица 50 мл", "50", "ml");
        assertVolume("Крем для рук 100мл", "100", "ml");
        assertVolume("Spray 100 ML", "100", "ml");
        assertVolume("Духи 0.5 л", "500.0", "ml"); // converted to the base unit (l -> ml)
        assertVolume("Мыло 90 г", "90", "g");
        assertVolume("Дезодорант 150 g", "150", "g");
    }

    @Test
    void extractsTesterMarker() {
        NormalizedRowData row = normalize("Chanel No 5 Тестер 100 ml");
        assertTrue(row.tester());
        assertFalse(row.set());

        NormalizedRowData retailRow = normalize("Chanel No 5 100 ml");
        assertFalse(retailRow.tester());
    }

    @Test
    void extractsSetMarker() {
        NormalizedRowData row = normalize("Набор Chanel No 5 100 ml + Body Lotion");
        assertTrue(row.set());

        NormalizedRowData englishSet = normalize("Chanel Gift Set 100 ml");
        assertTrue(englishSet.set());
    }

    @Test
    void extractsConcentrationKeywords() {
        assertEquals("EDP", normalize("Chanel No 5 Eau de Parfum 100 ml").concentration());
        assertEquals("EDP", normalize("Chanel No 5 EDP 100 ml").concentration());
        assertEquals("EDT", normalize("Chanel No 5 Eau de Toilette 100 ml").concentration());
        assertEquals("EDT", normalize("Chanel No 5 EDT 100 ml").concentration());
        assertNull(normalize("Крем для лица 50 мл").concentration());
    }

    @Test
    void brandIsPreservedVerbatim_neverGloballyRewritten() {
        NormalizedRowData channel = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 5 100 ml"));
        assertEquals("Channel", channel.brand(), "row's as-seen brand token must never be rewritten to 'Chanel'");

        NormalizedRowData shanel = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Шанель",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Шанель №5 100 мл"));
        assertEquals("Шанель", shanel.brand());

        NormalizedRowData chanel = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml"));
        assertEquals("Chanel", chanel.brand());
    }

    @Test
    void sameStructuralAttributes_produceEqualFingerprint_regardlessOfSource() {
        NormalizedRowData row = normalize("Chanel No 5 100 ml");
        NormalizedRowData sameProduct = normalize("Chanel No 5 100 ml");
        assertEquals(row.fingerprint(), sameProduct.fingerprint());
    }

    @Test
    void differentVolume_producesDifferentFingerprint() {
        NormalizedRowData row50 = normalize("Aroma Cream 50 ml");
        NormalizedRowData row100 = normalize("Aroma Cream 100 ml");
        assertFalse(row50.fingerprint().equals(row100.fingerprint()));
        assertEquals(new BigDecimal("50"), row50.volumeValue());
        assertEquals(new BigDecimal("100"), row100.volumeValue());
    }

    @Test
    void fingerprintIsVersioned_asALiteralPrefix() {
        NormalizedRowData row = normalize("Chanel No 5 100 ml");
        assertTrue(row.fingerprint().startsWith("v" + RowAttributeNormalizer.NORMALIZATION_VERSION + ":"),
                "fingerprint must carry an explicit normalization-version marker: " + row.fingerprint());
        assertEquals(RowAttributeNormalizer.NORMALIZATION_VERSION, row.normalizationVersion());
    }

    /**
     * ADR-030: a shop with "Chanel"/"Channel"/"Шанель" configured as the same canonical brand must
     * produce the IDENTICAL fingerprint for "Channel No 5 100 ml" and "Chanel No 5 100 ml" - both
     * the brand component (canonical, not raw spelling) and the line component (brand phrase
     * stripped out first) must agree, even though the two rows' `brand()`/raw name text differ.
     */
    @Test
    void configuredBrandAlias_producesIdenticalFingerprint_acrossSpellings() {
        when(brandAliasRepository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of(
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Chanel").normalizedAlias("chanel").build(),
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Channel").normalizedAlias("channel").build(),
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Шанель").normalizedAlias("шанель").build()));

        NormalizedRowData chanelRow = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml"));
        NormalizedRowData channelRow = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 5 100 ml"));
        NormalizedRowData shanelRow = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Шанель",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Шанель No 5 100 ml"));

        assertEquals(chanelRow.fingerprint(), channelRow.fingerprint(),
                "configured alias 'Channel' must fingerprint identically to canonical 'Chanel'");
        assertEquals(chanelRow.fingerprint(), shanelRow.fingerprint(),
                "configured alias 'Шанель' must fingerprint identically to canonical 'Chanel'");
        // brand() itself must still be preserved verbatim (display/audit) - only the fingerprint is alias-aware.
        assertEquals("Channel", channelRow.brand());
        assertEquals("Шанель", shanelRow.brand());
    }

    /**
     * ADR-030 (Section 6, cross-shop isolation): a {@code BrandAlias} configured for one shop must
     * never affect another shop's fingerprint - shop-2 (no alias rows at all) must keep "Channel"
     * and "Chanel" as two DIFFERENT identities, exactly like the unconfigured/legacy behavior,
     * even though shop-1 (configured above) merges them. Aliases are strictly per-shop admin data
     * (D-002/multi-tenant), never a shared global table.
     */
    @Test
    void configuredBrandAlias_neverLeaksAcrossShops() {
        String otherShopId = "shop-2";
        when(brandAliasRepository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of(
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Chanel").normalizedAlias("chanel").build(),
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Channel").normalizedAlias("channel").build()));
        when(brandAliasRepository.findByShopIdOrderByCanonicalBrandAscAliasAsc(otherShopId)).thenReturn(List.of());

        NormalizedRowData chanelShop1 = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml"));
        NormalizedRowData channelShop1 = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 5 100 ml"));
        assertEquals(chanelShop1.fingerprint(), channelShop1.fingerprint(), "shop-1 has the alias configured");

        NormalizedRowData chanelShop2 = normalizer.normalize(otherShopId, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml"));
        NormalizedRowData channelShop2 = normalizer.normalize(otherShopId, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 5 100 ml"));
        assertFalse(chanelShop2.fingerprint().equals(channelShop2.fingerprint()),
                "shop-2 has no alias configured - 'Chanel' and 'Channel' must stay different identities there");
    }

    /**
     * ADR-030: stripping the brand phrase out of the name before computing `line` must be
     * word-boundary-safe and must never touch a DIFFERENT product's distinguishing text - "No 5"
     * and "No 19", "Coco Mademoiselle" and "No 5" must remain different fingerprints even when the
     * brand is configured with aliases.
     */
    @Test
    void brandStripping_neverMergesDifferentLines_evenWithConfiguredAliases() {
        when(brandAliasRepository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of(
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Chanel").normalizedAlias("chanel").build(),
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Channel").normalizedAlias("channel").build()));

        NormalizedRowData no5 = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml"));
        NormalizedRowData no19 = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 19 100 ml"));
        NormalizedRowData cocoMademoiselle = normalizer.normalize(SHOP_ID, Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel Coco Mademoiselle 100 ml"));

        assertFalse(no5.fingerprint().equals(no19.fingerprint()), "'No 5' and 'No 19' must stay different products");
        assertFalse(no5.fingerprint().equals(cocoMademoiselle.fingerprint()),
                "'No 5' and 'Coco Mademoiselle' must stay different products");
        assertEquals("No 5", no5.line());
        assertEquals("No 19", no19.line());
        assertEquals("Coco Mademoiselle", cocoMademoiselle.line());
    }

    /** Backward compatibility: a JSON blob persisted before {@code normalizationVersion} existed must still deserialize safely. */
    @Test
    void legacyJsonWithoutNormalizationVersion_deserializesWithNullVersion() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String legacyJson = "{\"brand\":\"Chanel\",\"line\":\"No 5\",\"variant\":null,\"volumeValue\":100,"
                + "\"volumeUnit\":\"ml\",\"concentration\":null,\"shade\":null,\"tester\":false,\"set\":false,"
                + "\"externalSku\":null,\"barcode\":null,\"supplierPrice\":1000,\"stock\":null,"
                + "\"searchName\":\"chanel no 5 100 ml\",\"fingerprint\":\"chanel|no 5|100|ml|||false|false\"}";
        NormalizedRowData legacy = objectMapper.readValue(legacyJson, NormalizedRowData.class);
        assertNull(legacy.normalizationVersion(), "missing field in old JSON must deserialize as null, not a guessed version");
        assertEquals("Chanel", legacy.brand());
        assertEquals("chanel|no 5|100|ml|||false|false", legacy.fingerprint());
    }

    private void assertVolume(String name, String expectedValue, String expectedUnit) {
        NormalizedRowData row = normalize(name);
        assertEquals(new BigDecimal(expectedValue), row.volumeValue(), () -> "name=" + name);
        assertEquals(expectedUnit, row.volumeUnit(), () -> "name=" + name);
    }

    private NormalizedRowData normalize(String rawName) {
        return normalizer.normalize(SHOP_ID, Map.of(LayoutRuleDefinition.FIELD_RAW_NAME, rawName));
    }
}
