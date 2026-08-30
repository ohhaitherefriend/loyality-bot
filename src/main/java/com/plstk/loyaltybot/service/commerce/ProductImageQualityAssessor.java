package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ProductImageQualityAssessor {

    public QualityAssessment assess(Product product, ImageCandidate candidate, BufferedImage image, int textConfidence) {
        List<String> warnings = new ArrayList<>();
        String combined = combinedText(candidate);

        boolean hasHandsOrPeople = containsAny(combined, "hand", "рука", "модель", "woman", "man", "девуш");
        boolean hasWatermark = containsAny(combined, "watermark", "©", "getty", "shutterstock");
        boolean hasPromoBadges = containsAny(combined, "sale", "скид", "акция", "-%", "promo", "badge");
        boolean isCollage = containsAny(combined, "collage", "коллаж", "comparison", "vs ");
        boolean isBundleOrSet = containsAny(combined, "набор", "set", "bundle", "комплект", "x2", "2 шт");
        boolean placeholder = containsAny(combined, "no-image", "placeholder", "default");
        boolean badReplica = containsAny(combined, "аналог", "копия", "реплика", "fake");

        if (hasHandsOrPeople) warnings.add("На фото могут быть руки или человек");
        if (hasWatermark) warnings.add("Возможный watermark");
        if (hasPromoBadges) warnings.add("Промо-бейджи или ценник");
        if (isCollage) warnings.add("Коллаж");
        if (isBundleOrSet) warnings.add("Набор или комплект");
        if (placeholder) warnings.add("Placeholder изображение");
        if (badReplica) warnings.add("Возможный аналог/реплика");

        int width = image != null ? image.getWidth() : safeInt(candidate.width());
        int height = image != null ? image.getHeight() : safeInt(candidate.height());
        boolean tooSmall = width < 400 || height < 400;
        if (tooSmall) warnings.add("Слишком маленькое изображение");

        double aspect = width > 0 && height > 0 ? (double) width / height : 1.0;
        boolean angleTooExtreme = aspect > 2.8 || aspect < 0.25;
        if (angleTooExtreme) warnings.add("Экстремальный ракурс или crop");

        int score = textConfidence;
        if (hasHandsOrPeople) score -= 25;
        if (hasWatermark) score -= 20;
        if (hasPromoBadges) score -= 15;
        if (isCollage) score -= 30;
        if (isBundleOrSet) score -= 25;
        if (placeholder || badReplica) score -= 50;
        if (tooSmall) score -= 20;
        if (angleTooExtreme) score -= 20;
        score = Math.max(0, Math.min(100, score));

        String decision;
        if (placeholder || badReplica || score < 35) {
            decision = "REJECT_AND_TRY_NEXT";
        } else if (hasHandsOrPeople || isCollage || isBundleOrSet || angleTooExtreme || score < 55) {
            decision = "MANUAL_REVIEW_REQUIRED";
        } else if (score < 70 || !warnings.isEmpty()) {
            decision = "NORMALIZE_BUT_REVIEW";
        } else {
            decision = "ACCEPT_FOR_NORMALIZATION";
        }

        return QualityAssessment.builder()
                .singleProduct(!isBundleOrSet && !isCollage)
                .packshotLike(!hasHandsOrPeople && !isCollage)
                .frontOrSlightAngle(!angleTooExtreme)
                .hasHandsOrPeople(hasHandsOrPeople)
                .hasWatermark(hasWatermark)
                .hasPromoBadges(hasPromoBadges)
                .isCollage(isCollage)
                .isBundleOrSet(isBundleOrSet)
                .productClearlyVisible(!placeholder && !tooSmall)
                .packagingLikelyReadable(score >= 50)
                .angleTooExtreme(angleTooExtreme)
                .cropTooTight(tooSmall)
                .visualQualityScore(score)
                .decision(decision)
                .warnings(warnings)
                .build();
    }

    private String combinedText(ImageCandidate candidate) {
        return ((candidate.title() != null ? candidate.title() : "") + " "
                + (candidate.description() != null ? candidate.description() : "") + " "
                + (candidate.pageUrl() != null ? candidate.pageUrl() : "") + " "
                + (candidate.imageUrl() != null ? candidate.imageUrl() : "")).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
