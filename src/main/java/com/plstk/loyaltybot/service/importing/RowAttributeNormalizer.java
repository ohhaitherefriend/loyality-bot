package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
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
 * conflict or a false match. {@code brand} (the returned {@link NormalizedRowData#brand()}) is
 * always preserved exactly as seen - this class never rewrites "Channel" to "Chanel" or vice versa
 * for DISPLAY/audit purposes.
 *
 * <p><b>ADR-030 (identity across configured brand aliases):</b> the {@code fingerprint} itself -
 * the one thing actually compared for deterministic identity - is different: it is shop-scoped and
 * alias-AWARE. A shop with "Chanel"/"Channel"/"Шанель" configured as the same canonical brand
 * (via {@link BrandAliasResolver}) must treat a "Channel No 5 100 ml" row and a "Chanel No 5 100 ml"
 * catalog product as the SAME identity, not two different fingerprints just because of spelling.
 * Two independent, alias-aware steps make this safe without ever mutating the row/product's own
 * stored/displayed text:
 * <ol>
 *   <li>the fingerprint's brand component uses {@link BrandAliasResolver#canonicalKey} (the
 *       configured alias group's canonical spelling for this shop) instead of the raw brand text;</li>
 *   <li>every known alias spelling of that brand (via {@link BrandAliasResolver#expand}) is
 *       stripped as a whole-word/whole-phrase match from the free-text name BEFORE the remaining
 *       text becomes {@code line} - so "Channel No 5" and "Chanel No 5" both reduce to the line
 *       "No 5", not two different line strings. This is a targeted, word-boundary-safe removal of
 *       the recognized brand phrase specifically (never a blind global substring replace that could
 *       mangle unrelated text) - "No 5" and "No 19", "Coco Mademoiselle" and "No 5" remain
 *       completely different {@code line} values, exactly as before.</li>
 * </ol>
 * Both steps require {@code shopId} (aliases are shop-scoped, D-002/multi-tenant) - see
 * {@link #normalize(String, Map)}/{@link #normalizeProduct(String, Product)}.
 *
 * <p><b>Normalization versioning:</b> changing the fingerprint algorithm changes what string a
 * given brand/name produces. {@link #NORMALIZATION_VERSION} is embedded as the fingerprint's own
 * first segment (e.g. {@code "v2:chanel|no 5|..."}) so a fingerprint computed by an older version of
 * this class can never coincidentally collide with (or be silently compared equal/unequal against)
 * one computed by a newer version - see {@code SupplierProductLinkFingerprintMigrationService} for
 * how already-persisted {@code SupplierProductLink.fingerprint} rows are backfilled to the current
 * version instead of just silently going stale.
 */
@Component
public class RowAttributeNormalizer {

    /**
     * Bumped whenever the fingerprint algorithm changes in a way that changes its output for the
     * same input (ADR-030 bumped 1 -&gt; 2: alias-canonical brand + brand-stripped line; ADR-031
     * bumped 2 -&gt; 3: "No"/"No." product-number spelling equivalence, see {@link
     * #PRODUCT_NUMBER_ABBREVIATION_PATTERN}). See class javadoc "Normalization versioning".
     */
    public static final int NORMALIZATION_VERSION = 3;

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

    /**
     * ADR-031 (Section 2, scenario A root cause): a "No"/"No." product-number token (e.g. the
     * perfume line "Chanel No 5" vs a supplier's own "Chanel No. 5" spelling) must compare equal
     * regardless of whether the abbreviation-period is present - the period after "No" is a purely
     * orthographic variant, never a decimal point (a decimal point is only ever preceded by a
     * DIGIT, e.g. "1.5", which this pattern - anchored on the LETTERS "No" - can never match, so
     * "1.5" is never touched/corrupted by this substitution). Deliberately narrow and targeted
     * (never a blind global punctuation strip): only the literal period immediately after the
     * word "No" and immediately before a digit is normalized away, to a single canonical space -
     * "No 5"/"No. 5"/"No.5" all become the identical "No 5" text before fingerprinting, while
     * "No 19" remains completely distinct from "No 5" (the digits themselves are untouched).
     */
    private static final Pattern PRODUCT_NUMBER_ABBREVIATION_PATTERN =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}])No\\.\\s*(?=\\d)");

    private final BrandAliasResolver brandAliasResolver;

    public RowAttributeNormalizer(BrandAliasResolver brandAliasResolver) {
        this.brandAliasResolver = brandAliasResolver;
    }

    public NormalizedRowData normalize(String shopId, Map<String, String> rawValues) {
        String brand = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_BRAND));
        String name = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_RAW_NAME));
        String externalSku = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_EXTERNAL_SKU));
        String barcode = trimToNull(rawValues.get(LayoutRuleDefinition.FIELD_BARCODE));
        BigDecimal supplierPrice = parseDecimal(rawValues.get(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE));
        Integer stock = parseInt(rawValues.get(LayoutRuleDefinition.FIELD_STOCK));

        return extract(shopId, brand, name, externalSku, barcode, supplierPrice, stock);
    }

    /** Normalizes a catalog {@code Product} the same way, for deterministic fingerprint comparison. */
    public NormalizedRowData normalizeProduct(String shopId, Product product) {
        return extract(
                shopId,
                trimToNull(product.getBrand()),
                trimToNull(product.getName()),
                trimToNull(product.getSupplierArticle()),
                trimToNull(product.getBarcode()),
                product.getSupplierPrice(),
                product.getStockQuantity());
    }

    private NormalizedRowData extract(
            String shopId, String brand, String name, String externalSku, String barcode,
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
                // ADR-030 bugfix: `volumeMatcher` was already advanced by the `find()` check above,
                // so reusing it here made `removeMatch`'s own `find()` search FORWARD from the end
                // of that match instead of locating it - the volume text was therefore never
                // actually removed from `remainder` (it stayed a false "no second occurrence"
                // no-op). A fresh matcher against the current `remainder` fixes it, matching every
                // other stripping step below (concentration/shade/tester/set), which already used a
                // freshly-constructed matcher and never had this bug.
                remainder = removeMatch(remainder, VOLUME_PATTERN.matcher(remainder));
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

        // ADR-030: strip the brand as its OWN recognized component from the free-text name before
        // what's left becomes `line` - a targeted, word-boundary-safe removal of every known alias
        // spelling of THIS brand for this shop (never a blind global substring replace), so
        // "Channel No 5" and "Chanel No 5" both reduce to line "No 5" instead of two different line
        // strings just because of spelling. "No 5"/"No 19", "Coco Mademoiselle"/"No 5" are untouched
        // by this - only the recognized brand phrase itself is removed, nothing else.
        if (brand != null && shopId != null) {
            remainder = stripBrandPhrase(remainder, brandAliasResolver.expand(shopId, brand));
        }

        String line = collapseWhitespace(remainder);
        String searchName = cleaned == null ? null : foldYo(cleaned.toLowerCase(java.util.Locale.ROOT));

        String brandIdentityKey = brandIdentityKey(shopId, brand);
        String fingerprint = buildFingerprint(brandIdentityKey, line, volumeValue, volumeUnit, concentration, shade, tester, set);

        return new NormalizedRowData(
                brand, line, null, volumeValue, volumeUnit, concentration, shade, tester, set,
                externalSku, barcode, supplierPrice, stock, searchName, fingerprint, NORMALIZATION_VERSION);
    }

    /**
     * The alias-canonical identity key used ONLY inside the fingerprint (ADR-030) - never returned
     * as {@link NormalizedRowData#brand()} itself, which always stays the as-seen text. Falls back
     * to the brand's own normalized form when {@code shopId} is absent or no alias is configured,
     * so behavior for an unconfigured/unaliased brand is unchanged from before this ADR.
     */
    private String brandIdentityKey(String shopId, String brand) {
        if (brand == null) {
            return null;
        }
        if (shopId != null) {
            String canonical = brandAliasResolver.canonicalKey(shopId, brand);
            if (canonical != null && !canonical.isEmpty()) {
                return canonical;
            }
        }
        return foldYo(brand.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Removes the FIRST whole-word/whole-phrase occurrence of any known alias spelling of this
     * brand from {@code text} - e.g. for brand "Channel" with aliases {"chanel","channel","шанель"}
     * configured, "Channel No 5 100 ml" (after volume already stripped) has "Channel" removed,
     * leaving "No 5". Word-boundary-anchored so it never touches a substring inside an unrelated
     * word (never a blind {@code String.replace}).
     */
    private String stripBrandPhrase(String text, Set<String> brandAliasKeys) {
        if (text == null || brandAliasKeys == null || brandAliasKeys.isEmpty()) {
            return text;
        }
        for (String alias : brandAliasKeys) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            Matcher m = brandPhrasePattern(alias).matcher(text);
            if (m.find()) {
                return m.replaceFirst(" ");
            }
        }
        return text;
    }

    private Pattern brandPhrasePattern(String normalizedAlias) {
        String[] words = normalizedAlias.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) {
                sb.append("\\s+");
            }
            sb.append(Pattern.quote(w));
        }
        String pattern = "(?<![\\p{L}\\p{N}])" + sb + "(?![\\p{L}\\p{N}])";
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private String buildFingerprint(
            String brandKey, String line, BigDecimal volumeValue, String volumeUnit,
            String concentration, String shade, boolean tester, boolean set) {
        String brandPart = brandKey == null ? "" : foldYo(brandKey.trim().toLowerCase(java.util.Locale.ROOT));
        String lineKey = line == null ? "" : foldYo(line.trim().toLowerCase(java.util.Locale.ROOT));
        String volumeKey = volumeValue == null ? "" : volumeValue.stripTrailingZeros().toPlainString();
        String unitKey = volumeUnit == null ? "" : volumeUnit.toLowerCase(java.util.Locale.ROOT);
        String concentrationKey = concentration == null ? "" : concentration.toLowerCase(java.util.Locale.ROOT);
        String shadeKey = shade == null ? "" : shade.toLowerCase(java.util.Locale.ROOT);
        String body = String.join("|", brandPart, lineKey, volumeKey, unitKey, concentrationKey, shadeKey,
                String.valueOf(tester), String.valueOf(set));
        return "v" + NORMALIZATION_VERSION + ":" + body;
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

    /**
     * Unicode NFKC, quote/dash normalization, the "No."/"No" product-number equivalence (see
     * {@link #PRODUCT_NUMBER_ABBREVIATION_PATTERN}) and whitespace collapse; the visible text is
     * otherwise preserved.
     */
    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = normalized
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-');
        normalized = PRODUCT_NUMBER_ABBREVIATION_PATTERN.matcher(normalized).replaceAll("No ");
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
