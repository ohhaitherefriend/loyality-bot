package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects critical attribute conflicts between two {@link NormalizedRowData} (a supplier row and a
 * candidate catalog product, both normalized the same way). Per prompt 04 / docs/ARCHITECTURE.md
 * §9.4, a critical conflict on volume/unit, concentration, shade, set composition or tester/retail
 * always blocks auto-match for that candidate, regardless of how similar the names look (e.g. 50 ml
 * must never be auto-matched to 100 ml, a tester must never be auto-matched to retail packaging).
 *
 * <p>An attribute is only compared when BOTH sides have a defined (non-null) value for it - an
 * absent attribute is treated as "unknown", not as a conflict, since suppliers frequently omit
 * shade/concentration in free-text names.
 */
@Component
public class CriticalAttributeConflictChecker {

    public static final String VOLUME_UNIT = "VOLUME_UNIT";
    public static final String CONCENTRATION = "CONCENTRATION";
    public static final String SHADE = "SHADE";
    public static final String SET_COMPOSITION = "SET_COMPOSITION";
    public static final String TESTER_VS_RETAIL = "TESTER_VS_RETAIL";

    public List<String> findConflicts(NormalizedRowData a, NormalizedRowData b) {
        List<String> conflicts = new ArrayList<>();

        if (a.volumeValue() != null && b.volumeValue() != null) {
            boolean sameUnit = equalsIgnoreCase(a.volumeUnit(), b.volumeUnit());
            boolean sameValue = a.volumeValue().compareTo(b.volumeValue()) == 0;
            if (!sameUnit || !sameValue) {
                conflicts.add(VOLUME_UNIT);
            }
        }

        if (a.concentration() != null && b.concentration() != null
                && !equalsIgnoreCase(a.concentration(), b.concentration())) {
            conflicts.add(CONCENTRATION);
        }

        if (a.shade() != null && b.shade() != null && !equalsIgnoreCase(a.shade(), b.shade())) {
            conflicts.add(SHADE);
        }

        if (a.set() != b.set()) {
            conflicts.add(SET_COMPOSITION);
        }

        if (a.tester() != b.tester()) {
            conflicts.add(TESTER_VS_RETAIL);
        }

        return conflicts;
    }

    private boolean equalsIgnoreCase(String x, String y) {
        return Objects.equals(
                x == null ? null : x.trim().toLowerCase(),
                y == null ? null : y.trim().toLowerCase());
    }
}
