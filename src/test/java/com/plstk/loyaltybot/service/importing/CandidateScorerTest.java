package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateScorerTest {

    private final CandidateScorer scorer = new CandidateScorer(new BrandAliasResolver(), new CriticalAttributeConflictChecker());

    @Test
    void brandAlias_givesSmallerBonusThanExactBrand_andIsTagged() {
        NormalizedRowData row = row("Channel", "chanel no 5 100 ml", new BigDecimal("100"), "ml", null, null, false, false);
        NormalizedRowData aliasCandidate = row("Chanel", "chanel no 5 100 ml", new BigDecimal("100"), "ml", null, null, false, false);
        NormalizedRowData exactCandidate = row("Channel", "chanel no 5 100 ml", new BigDecimal("100"), "ml", null, null, false, false);

        ScoredCandidate aliasScore = scorer.score(1L, "Chanel No 5", row, aliasCandidate);
        ScoredCandidate exactScore = scorer.score(2L, "Channel No 5", row, exactCandidate);

        assertTrue(aliasScore.matchedAttributes().contains("brandAlias"));
        assertTrue(exactScore.matchedAttributes().contains("brand"));
        assertTrue(aliasScore.componentScores().get("brandBonus").compareTo(exactScore.componentScores().get("brandBonus")) < 0,
                "an aliased brand must score lower than an exact brand match");
    }

    @Test
    void conflictingCandidate_isPenalizedBelowCleanCandidate() {
        NormalizedRowData row = row("Brand", "aroma cream 50 ml", new BigDecimal("50"), "ml", null, null, false, false);
        NormalizedRowData cleanCandidate = row("Brand", "aroma cream 50 ml", new BigDecimal("50"), "ml", null, null, false, false);
        NormalizedRowData conflictingCandidate = row("Brand", "aroma cream 100 ml", new BigDecimal("100"), "ml", null, null, false, false);

        ScoredCandidate clean = scorer.score(1L, "Aroma Cream 50ml", row, cleanCandidate);
        ScoredCandidate conflicting = scorer.score(2L, "Aroma Cream 100ml", row, conflictingCandidate);

        assertTrue(conflicting.hasConflicts());
        assertTrue(clean.totalScore().compareTo(conflicting.totalScore()) > 0,
                "a volume conflict must push a candidate's score below a clean one");
    }

    @Test
    void identicalStrings_haveMaximumTrigramSimilarity() {
        assertEquals(BigDecimal.ONE.setScale(4), scorer.trigramSimilarity("chanel no 5", "chanel no 5"));
    }

    @Test
    void completelyDifferentStrings_haveLowSimilarity() {
        BigDecimal similarity = scorer.trigramSimilarity("chanel no 5", "xyz totally unrelated");
        assertTrue(similarity.compareTo(new BigDecimal("0.2")) < 0);
    }

    private NormalizedRowData row(
            String brand, String searchName, BigDecimal volumeValue, String volumeUnit,
            String concentration, String shade, boolean tester, boolean set) {
        return new NormalizedRowData(
                brand, "line", null, volumeValue, volumeUnit, concentration, shade, tester, set,
                null, null, null, null, searchName, "fp");
    }
}
