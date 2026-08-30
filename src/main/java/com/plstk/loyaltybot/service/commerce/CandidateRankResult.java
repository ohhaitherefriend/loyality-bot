package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.Product;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record CandidateRankResult(
        boolean matched,
        ImageCandidate bestCandidate,
        int bestCandidateIndex,
        BigDecimal confidence,
        String matchedBy,
        String reason,
        List<String> warnings
) {
    public static CandidateRankResult noMatch(String reason) {
        return CandidateRankResult.builder()
                .matched(false)
                .confidence(BigDecimal.ZERO)
                .matchedBy("NO_MATCH")
                .reason(reason)
                .warnings(List.of())
                .build();
    }
}
