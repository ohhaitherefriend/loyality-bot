package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@link NormalizedRowData} (brand/line/variant/volume+unit/concentration/shade/
 * tester/set/SKU/barcode/price/stock) from either a supplier row's raw values or a catalog
 * {@code Product}'s brand+name, using the SAME extraction rules for both - this is what makes the
 * "safe fingerprint" deterministic match stage (comparing a row's fingerprint against a live
 * catalog product's on-the-fly computed fingerprint) meaningful.
 *
 * <p>Deliberately conservative: unrecognized shade/concentration/variant text is left {@code null}
 * rather than guessed, since an absent attribute is treated as "unknown" (never compared) by
 * {@link CriticalAttributeConflictChecker}, while a wrong guess could silently create a false
 * conflict or a false match. {@code brand} is always preserved exactly as seen - this class never
 * rewrites "Channel" to "Chanel" or vice versa; that is a scoped search signal handled separately by
 * {@link BrandAliasResolver}.
 */
@Component
public class RowAttributeNormalizer {

    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(мл|ml|л|l|кг|kg|мг|mg|гр|g|г|oz)(?![a-zA-Zа-яёА-ЯЁ])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern TESTER_PATTERN = Pattern.compile(
            "(?<![a-zA-Zа-яёА-ЯЁ])(тестер|tester)(?![a-zA-Zа-яёА-ЯЁ])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SET_PATTERN = Pattern.compile(
            "(?<![a-zA-Zа-яёА-ЯЁ])(набор|set|kit)(?![a-zA-Zа-яёА-ЯЁ])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Checked longest-phrase-first so "eau de parfum" is not misread as bare "parfum". */
    private static final Pattern[] CONCENTRATION_PATTERNS = {
            Pattern.compile("eau\\s*de\\s*parfum|парфюмерная\\s*вода|\\bedp\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("eau\\s*de\\s*toilette|туалетная\\s*вода|\\bedt\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("eau\\s*de\\s*cologne|одеколон|\\bedc\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("parfum|духи", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
    };
    private static final String[] CONCENTRATION_CODES = {"EDP", "EDT", "EDC", "PARFUM"};

    private static final Pattern[] SHADE_PATTERNS = {
            Pattern.compile("№\\s*([a-zA-Zа-яёА-ЯЁ0-9\\-]+)", Pattern.UNICODE_CASE),
            Pattern.compile("#\\s*([a-zA-Zа-яёА-ЯЁ0-9\\-]+)"),
            Pattern.compile("(?:оттенок|тон|shade|цвет|color)\\s+([a-zA-Zа-яёА-ЯЁ0-9\\-]+)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
    };

    public NormalizedRowData normalize(Map<String, String> rawValues) {
        String brand = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_BRAND));
        String name = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_RAW_NAME));
        String externalSku = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_EXTERNAL_SKU));
        String barcode = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_BARCODE));
        BigDecimal supplierPrice = parseDecimal(rawValues.get(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE));
        Integer stock = parseInt(rawValues.get(LayoutRuleDefinition.FIELD_STOCK));

        return extract(brand, name, externalSku, barcode, supplierPrice, stock);
    }

    /** Normalizes a catalog {@code Product} the same way, for deterministic fingerprint comparison. */
    public NormalizedRowData normalizeProduct(Product product) {
        return extract(
                trimToNull(product.getBrand()),
                trimToNull(product.getName()),
                trimToNull(product.getSupplierArticle()),
                trimToNull(product.getBarcode()),
                product.getSupplierPrice(),
                product.getStockQuantity());
    }

    private NormalizedRowData extract(
            String brand, String name, String externalSku, String barcode,
            BigDecimal supplierPrice, Integer stock) {

        String cleaned = cleanText(name);

        BigDecimal volumeValue = null;
        String volumeUnit = null;
        String remainder = cleaned;
        if (cleaned != null) {
            Matcher volumeMatcher = VOLUME_PATTERN.matcher(cleaned);
            if (volumeMatcher.find()) {
                BigDecimal rawValue = parseDecimal(volumeMatcher.group(1));
                String rawUnit = normalizeUnit(volumeMatcher.group(2));
                VolumeAmount base = toBaseUnit(rawValue, rawUnit);
                volumeValue = base.value();
                volumeUnit = base.unit();
                remainder = removeMatch(remainder, volumeMatcher);
            }
        }

        String concentration = null;
        if (cleaned != null) {
            for (int i = 0; i < CONCENTRATION_PATTERNS.length; i++) {
                Matcher m = CONCENTRATION_PATTERNS[i].matcher(cleaned);
                if (m.find()) {
                    concentration = CONCENTRATION_CODES[i];
                    remainder = removeMatch(remainder, CONCENTRATION_PATTERNS[i].matcher(remainder));
                    break;
                }
            }
        }

        String shade = null;
        if (cleaned != null) {
            for (Pattern p : SHADE_PATTERNS) {
                Matcher m = p.matcher(cleaned);
                if (m.find()) {
                    shade = m.group(1);
                    remainder = removeMatch(remainder, p.matcher(remainder));
                    break;
                }
            }
        }

        boolean tester = cleaned != null && TESTER_PATTERN.matcher(cleaned).find();
        if (tester) {
            remainder = removeMatch(remainder, TESTER_PATTERN.matcher(remainder));
        }
        boolean set = cleaned != null && SET_PATTERN.matcher(cleaned).find();
        if (set) {
            remainder = removeMatch(remainder, SET_PATTERN.matcher(remainder));
        }

        String line = collapseWhitespace(remainder);
        String searchName = cleaned == null ? null : foldYo(cleaned.toLowerCase(java.util.Locale.ROOT));

        String fingerprint = buildFingerprint(brand, line, volumeValue, volumeUnit, concentration, shade, tester, set);

        return new NormalizedRowData(
                brand, line, null, volumeValue, volumeUnit, concentration, shade, tester, set,
                externalSku, barcode, supplierPrice, stock, searchName, fingerprint);
    }

    private String buildFingerprint(
            String brand, String line, BigDecimal volumeValue, String volumeUnit,
            String concentration, String shade, boolean tester, boolean set) {
        String brandKey = brand == null ? "" : foldYo(brand.trim().toLowerCase(java.util.Locale.ROOT));
        String lineKey = line == null ? "" : foldYo(line.trim().toLowerCase(java.util.Locale.ROOT));
        String volumeKey = volumeValue == null ? "" : volumeValue.stripTrailingZeros().toPlainString();
        String unitKey = volumeUnit == null ? "" : volumeUnit.toLowerCase(java.util.Locale.ROOT);
        String concentrationKey = concentration == null ? "" : concentration.toLowerCase(java.util.Locale.ROOT);
        String shadeKey = shade == null ? "" : shade.toLowerCase(java.util.Locale.ROOT);
        return String.join("|", brandKey, lineKey, volumeKey, unitKey, concentrationKey, shadeKey,
                String.valueOf(tester), String.valueOf(set));
    }

    private String normalizeUnit(String unit) {
        String u = unit.toLowerCase(java.util.Locale.ROOT);
        return switch (u) {
            case "мл", "ml" -> "ml";
            case "л", "l" -> "l";
            case "кг", "kg" -> "kg";
            case "мг", "mg" -> "mg";
            case "г", "гр", "g" -> "g";
            case "oz" -> "oz";
            default -> u;
        };
    }

    private static final BigDecimal ML_PER_L = BigDecimal.valueOf(1000);
    private static final BigDecimal G_PER_KG = BigDecimal.valueOf(1000);
    private static final BigDecimal G_PER_MG = new BigDecimal("0.001");

    /**
     * Converts to a canonical base unit (ml for liquid volume, g for weight) before the value is
     * ever stored/compared, so a row written as "0.5 л" and a catalog product written as "500 мл"
     * normalize to the identical (500, ml) pair instead of tripping {@code
     * CriticalAttributeConflictChecker}'s {@code VOLUME_UNIT} conflict on a spurious unit mismatch.
     * {@code oz} is left unconverted (ambiguous fluid-oz vs weight-oz without more context) - it
     * only ever compares equal against another {@code oz} value, same as before this fix.
     */
    private VolumeAmount toBaseUnit(BigDecimal value, String unit) {
        if (value == null || unit == null) {
            return new VolumeAmount(value, unit);
        }
        return switch (unit) {
            case "l" -> new VolumeAmount(value.multiply(ML_PER_L), "ml");
            case "kg" -> new VolumeAmount(value.multiply(G_PER_KG), "g");
            case "mg" -> new VolumeAmount(value.multiply(G_PER_MG), "g");
            default -> new VolumeAmount(value, unit);
        };
    }

    private record VolumeAmount(BigDecimal value, String unit) {
    }

    /** Unicode NFKC, quote/dash normalization and whitespace collapse; the visible text is preserved. */
    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = normalized
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-');
        return collapseWhitespace(normalized);
    }

    private String collapseWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String removeMatch(String text, Matcher matcher) {
        if (text == null) {
            return null;
        }
        return matcher.find() ? matcher.replaceFirst(" ") : text;
    }

    /** ё/е folding applied only to the derived search key, never to the stored original text. */
    private String foldYo(String value) {
        return value.replace('ё', 'е');
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
