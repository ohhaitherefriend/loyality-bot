package com.plstk.loyaltybot.entity.commerce;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record ImageCandidate(
        String title,
        String imageUrl,
        String thumbnailUrl,
        String pageUrl,
        String sourceDomain,
        String description,
        ImageSourceType sourceType,
        BigDecimal confidence,
        String matchedBy,
        Integer width,
        Integer height,
        Boolean hasWatermark,
        Boolean looksLikePackshot,
        Boolean needsReview,
        LocalDateTime foundAt
) {}
