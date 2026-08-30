package com.plstk.loyaltybot.service.commerce;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QualityAssessment {
    private boolean singleProduct;
    private boolean packshotLike;
    private boolean frontOrSlightAngle;
    private boolean hasHandsOrPeople;
    private boolean hasWatermark;
    private boolean hasPromoBadges;
    private boolean isCollage;
    private boolean isBundleOrSet;
    private boolean productClearlyVisible;
    private boolean packagingLikelyReadable;
    private boolean angleTooExtreme;
    private boolean cropTooTight;
    private int visualQualityScore;
    private String decision;
    private List<String> warnings;
}
