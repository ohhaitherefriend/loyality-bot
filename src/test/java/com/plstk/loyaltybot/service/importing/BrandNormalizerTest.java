package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandNormalizerTest {

    private final BrandNormalizer normalizer = new BrandNormalizer();

    @Test
    void normalize_lowercasesAndFoldsYo() {
        assertEquals("елка", normalizer.normalize("Ёлка"), "ё must fold to е");
        assertEquals("шанель", normalizer.normalize("ШАНЕЛЬ"));
    }

    @Test
    void normalize_collapsesQuotesHyphensAndWhitespace() {
        assertEquals("loreal paris", normalizer.normalize("L\u2019Oreal   \u2013  Paris"));
    }

    @Test
    void transliterate_convertsRegularCyrillicToLatin() {
        assertEquals("dior", normalizer.transliterate(normalizer.normalize("Диор")));
        assertEquals("shchuka", normalizer.transliterate(normalizer.normalize("щука")));
    }

    @Test
    void transliterate_leavesLatinInputUnchanged() {
        assertEquals("dior", normalizer.transliterate(normalizer.normalize("Dior")));
    }

    @Test
    void hasCyrillic_detectsCyrillicLetters() {
        assertTrue(normalizer.hasCyrillic("Шанель"));
        assertTrue(!normalizer.hasCyrillic("Chanel"));
    }
}
