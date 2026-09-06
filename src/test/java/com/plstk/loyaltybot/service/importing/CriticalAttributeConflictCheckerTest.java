package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CriticalAttributeConflictCheckerTest {

    private final CriticalAttributeConflictChecker checker = new CriticalAttributeConflictChecker();

    @Test
    void volumeMismatch_isFlagged() {
        NormalizedRowData a = row(new BigDecimal("50"), "ml", null, null, false, false);
        NormalizedRowData b = row(new BigDecimal("100"), "ml", null, null, false, false);
        assertTrue(checker.findConflicts(a, b).contains(CriticalAttributeConflictChecker.VOLUME_UNIT));
    }

    @Test
    void unitMismatch_sameValue_isFlagged() {
        NormalizedRowData a = row(new BigDecimal("100"), "ml", null, null, false, false);
        NormalizedRowData b = row(new BigDecimal("100"), "g", null, null, false, false);
        assertTrue(checker.findConflicts(a, b).contains(CriticalAttributeConflictChecker.VOLUME_UNIT));
    }

    @Test
    void concentrationMismatch_isFlagged() {
        NormalizedRowData a = row(null, null, "EDP", null, false, false);
        NormalizedRowData b = row(null, null, "EDT", null, false, false);
        assertTrue(checker.findConflicts(a, b).contains(CriticalAttributeConflictChecker.CONCENTRATION));
    }

    @Test
    void shadeMismatch_isFlagged() {
        NormalizedRowData a = row(null, null, null, "12", false, false);
        NormalizedRowData b = row(null, null, null, "15", false, false);
        assertTrue(checker.findConflicts(a, b).contains(CriticalAttributeConflictChecker.SHADE));
    }

    @Test
    void testerVsRetail_isFlagged() {
        NormalizedRowData tester = row(null, null, null, null, true, false);
        NormalizedRowData retail = row(null, null, null, null, false, false);
        assertTrue(checker.findConflicts(tester, retail).contains(CriticalAttributeConflictChecker.TESTER_VS_RETAIL));
    }

    @Test
    void setVsSingle_isFlagged() {
        NormalizedRowData set = row(null, null, null, null, false, true);
        NormalizedRowData single = row(null, null, null, null, false, false);
        assertTrue(checker.findConflicts(set, single).contains(CriticalAttributeConflictChecker.SET_COMPOSITION));
    }

    @Test
    void matchingAttributes_produceNoConflicts() {
        NormalizedRowData a = row(new BigDecimal("100"), "ml", "EDP", "12", false, false);
        NormalizedRowData b = row(new BigDecimal("100"), "ml", "EDP", "12", false, false);
        assertEquals(List.of(), checker.findConflicts(a, b));
    }

    @Test
    void absentAttribute_isNeverAConflict() {
        NormalizedRowData a = row(null, null, null, null, false, false);
        NormalizedRowData b = row(new BigDecimal("100"), "ml", "EDP", "12", false, false);
        assertEquals(List.of(), checker.findConflicts(a, b),
                "an attribute unknown on one side must never be treated as a conflict");
    }

    private NormalizedRowData row(
            BigDecimal volumeValue, String volumeUnit, String concentration, String shade, boolean tester, boolean set) {
        return new NormalizedRowData(
                "Brand", "Line", null, volumeValue, volumeUnit, concentration, shade, tester, set,
                null, null, null, null, "brand line", "fp");
    }
}
