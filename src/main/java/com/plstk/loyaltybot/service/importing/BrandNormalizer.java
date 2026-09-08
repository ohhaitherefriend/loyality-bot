package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 4 normalization used for brand-alias lookup keys and candidate-search widening: lower-case,
 * Unicode NFKC, {@code ё->е} folding, quote/hyphen/whitespace collapse, and a best-effort
 * Cyrillic<->Latin transliteration so a Cyrillic brand token can still find Latin-spelled catalog
 * candidates (and vice versa) even before any operator adds an explicit {@link
 * com.plstk.loyaltybot.entity.importing.BrandAlias} row for that specific pair.
 *
 * <p>Transliteration here is a SEARCH-WIDENING signal only (used to build a broader SQL candidate
 * shortlist / to compare against stored aliases) - it never rewrites a row's stored {@code brand} and
 * is never, by itself, treated as proof of a match. A transliterated collision still has to clear
 * {@link CandidateScorer}'s trigram/critical-attribute-conflict scoring like any other candidate.
 */
@Component
public class BrandNormalizer {

    /** GOST-ish transliteration table, longest keys checked first for multi-letter Cyrillic graphemes. */
    private static final Map<String, String> CYRILLIC_TO_LATIN = buildCyrillicToLatin();

    private static Map<String, String> buildCyrillicToLatin() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("щ", "shch");
        m.put("ю", "yu");
        m.put("я", "ya");
        m.put("ё", "e");
        m.put("ж", "zh");
        m.put("ц", "ts");
        m.put("ч", "ch");
        m.put("ш", "sh");
        m.put("ъ", "");
        m.put("ы", "y");
        m.put("ь", "");
        m.put("э", "e");
        m.put("а", "a");
        m.put("б", "b");
        m.put("в", "v");
        m.put("г", "g");
        m.put("д", "d");
        m.put("е", "e");
        m.put("з", "z");
        m.put("и", "i");
        m.put("й", "y");
        m.put("к", "k");
        m.put("л", "l");
        m.put("м", "m");
        m.put("н", "n");
        m.put("о", "o");
        m.put("п", "p");
        m.put("р", "r");
        m.put("с", "s");
        m.put("т", "t");
        m.put("у", "u");
        m.put("ф", "f");
        m.put("х", "h");
        return m;
    }

    /**
     * Canonical lookup key: NFKC, lower-case, {@code ё->е}, quotes/dashes/whitespace collapsed. Used
     * as {@code BrandAlias.normalizedAlias} and to compare a row's brand against stored aliases.
     */
    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = normalized.toLowerCase(java.util.Locale.ROOT);
        normalized = normalized.replace('ё', 'е');
        normalized = normalized
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-');
        normalized = normalized.replaceAll("[\"'.,]", "");
        normalized = normalized.replaceAll("[\\s-]+", " ").trim();
        return normalized;
    }

    /**
     * Best-effort Cyrillic-to-Latin transliteration of an already-{@link #normalize}d value, for
     * widening SQL candidate search only (see class javadoc). Latin/mixed input is returned as-is
     * (no reverse Latin->Cyrillic table is attempted - too ambiguous to be a safe search signal).
     */
    public String transliterate(String normalized) {
        if (normalized == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            String ch = String.valueOf(normalized.charAt(i));
            out.append(CYRILLIC_TO_LATIN.getOrDefault(ch, ch));
        }
        return out.toString();
    }

    /** @return true if the value contains at least one Cyrillic letter. */
    public boolean hasCyrillic(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) {
                return true;
            }
        }
        return false;
    }
}
