package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Scoped cosmetics/perfumery brand-alias signal used only to widen fuzzy candidate search and to
 * tag {@code matchedAttributes} with {@code "brandAlias"} (docs/PROJECT_CONTEXT.md "Пример
 * matching"). Deliberately NOT a global string replacement: {@link RowAttributeNormalizer} never
 * calls this class, so a row's stored {@code normalizedData.brand} always keeps the as-seen token
 * (e.g. a misspelled "Channel" is never rewritten to "Chanel"). This class only answers "could this
 * row's brand also plausibly refer to that other brand?" for candidate widening/scoring.
 */
@Component
public class BrandAliasResolver {

    /**
     * Seed alias groups: every key in a group maps to every other key in the same group (including
     * itself). Extend this map as new supplier misspellings/transliterations are observed - it is
     * intentionally small and explicit rather than a generic transliteration engine, so it can never
     * silently invent an unrelated brand match.
     */
    private static final Set<Set<String>> ALIAS_GROUPS = Set.of(
            Set.of("chanel", "шанель", "channel")
    );

    private final Map<String, Set<String>> aliasesByToken = buildIndex();

    private static Map<String, Set<String>> buildIndex() {
        Map<String, Set<String>> index = new java.util.HashMap<>();
        for (Set<String> group : ALIAS_GROUPS) {
            for (String token : group) {
                index.put(token, group);
            }
        }
        return index;
    }

    /**
     * @return true if {@code brandA} and {@code brandB} are different (case-insensitive) but known
     *     aliases of one another. Identical tokens are not "aliases" of themselves for this purpose -
     *     callers should check exact equality separately.
     */
    public boolean areAliases(String brandA, String brandB) {
        if (brandA == null || brandB == null) {
            return false;
        }
        String a = brandA.trim().toLowerCase();
        String b = brandB.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        Set<String> group = aliasesByToken.get(a);
        return group != null && group.contains(b);
    }
}
