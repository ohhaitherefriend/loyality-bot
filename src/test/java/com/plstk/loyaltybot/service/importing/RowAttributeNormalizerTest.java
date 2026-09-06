package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowAttributeNormalizerTest {

    private final RowAttributeNormalizer normalizer = new RowAttributeNormalizer();

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
        NormalizedRowData channel = normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 5 100 ml"));
        assertEquals("Channel", channel.brand(), "row's as-seen brand token must never be rewritten to 'Chanel'");

        NormalizedRowData shanel = normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Шанель",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Шанель №5 100 мл"));
        assertEquals("Шанель", shanel.brand());

        NormalizedRowData chanel = normalizer.normalize(Map.of(
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

    private void assertVolume(String name, String expectedValue, String expectedUnit) {
        NormalizedRowData row = normalize(name);
        assertEquals(new BigDecimal(expectedValue), row.volumeValue(), () -> "name=" + name);
        assertEquals(expectedUnit, row.volumeUnit(), () -> "name=" + name);
    }

    private NormalizedRowData normalize(String rawName) {
        return normalizer.normalize(Map.of(LayoutRuleDefinition.FIELD_RAW_NAME, rawName));
    }
}
