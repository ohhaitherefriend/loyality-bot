package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class RuleBasedProductImageCandidateRanker implements ProductImageCandidateRanker {

    @Override
    public CandidateRankResult rank(Product product, List<ImageCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return CandidateRankResult.noMatch("Кандидаты не найдены");
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new ScoredCandidate(i, candidates.get(i), score(product, candidates.get(i))));
        }
        scored.sort(Comparator.comparing(ScoredCandidate::score).reversed());

        ScoredCandidate best = scored.get(0);
        if (best.score() < 50) {
            return CandidateRankResult.noMatch("Недостаточная уверенность rule-based ranker");
        }

        ImageCandidate candidate = best.candidate();
        return CandidateRankResult.builder()
                .matched(true)
                .bestCandidate(candidate)
                .bestCandidateIndex(best.index())
                .confidence(BigDecimal.valueOf(best.score()))
                .matchedBy(candidate.matchedBy() != null ? candidate.matchedBy() : "WEAK_TEXT_MATCH")
                .reason("Rule-based match score " + best.score())
                .warnings(List.of())
                .build();
    }

    private int score(Product product, ImageCandidate candidate) {
        int score = 50;
        String title = lower(candidate.title());
        String description = lower(candidate.description());
        String pageUrl = lower(candidate.pageUrl());
        String imageUrl = lower(candidate.imageUrl());
        String combined = title + " " + description + " " + pageUrl + " " + imageUrl;

        String brand = lower(product.getBrand());
        String article = lower(product.getSupplierArticle());
        String barcode = lower(product.getBarcode());
        String name = lower(product.getName());

        if (StringUtils.hasText(barcode) && combined.contains(barcode)) {
            score += 35;
        }
        if (StringUtils.hasText(article) && combined.contains(article)) {
            score += 25;
        }
        if (StringUtils.hasText(brand) && combined.contains(brand)) {
            score += 15;
        } else if (StringUtils.hasText(brand)) {
            score -= 30;
        }
        if (containsImportantTokens(name, combined)) {
            score += 15;
        } else if (StringUtils.hasText(name) && !StringUtils.hasText(brand)) {
            score -= 20;
        }

        Integer width = candidate.width();
        Integer height = candidate.height();
        if (width != null && height != null && width >= 600 && height >= 600) {
            score += 10;
        } else if ((width != null && width < 300) || (height != null && height < 300)) {
            score -= 20;
        }

        if (looksLikeProductSource(candidate.sourceDomain())) {
            score += 5;
        } else {
            score -= 15;
        }

        if (containsAny(combined, "no-image", "placeholder", "default")) {
            score -= 50;
        }
        if (containsAny(combined, "аналог", "копия", "реплика", "fake")) {
            score -= 50;
        }
        if (containsAny(combined, "набор", "set", "bundle", "комплект", "x2", "2 шт")) {
            score -= 20;
        }
        if (candidate.confidence() != null) {
            score += candidate.confidence().intValue() / 10;
        }

        return clamp(score);
    }

    private boolean containsImportantTokens(String name, String combined) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String[] tokens = name.split("\\s+");
        int hits = 0;
        for (String token : tokens) {
            if (token.length() >= 3 && combined.contains(token)) {
                hits++;
            }
        }
        return hits >= Math.min(2, tokens.length);
    }

    private boolean looksLikeProductSource(String domain) {
        if (!StringUtils.hasText(domain)) {
            return false;
        }
        String d = domain.toLowerCase(Locale.ROOT);
        return d.contains("shop") || d.contains("store") || d.contains("brand")
                || d.contains("cosmetic") || d.contains("parfum") || d.contains("goldapple")
                || d.contains("letu") || d.contains("rivegauche") || d.contains("ozon")
                || d.contains("yandex") || d.contains("market") || d.contains("wildberries")
                || d.contains("fragrantica") || d.contains("xerjoff");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    private record ScoredCandidate(int index, ImageCandidate candidate, int score) {}
}
