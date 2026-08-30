package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.Product;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryScaleRules {
    private double maxWidthRatio;
    private double maxHeightRatio;
    private double minMarginRatio;

    public static CategoryScaleRules forProduct(Product product) {
        String category = product.getCategoryPath() != null ? product.getCategoryPath().toLowerCase() : "";
        String name = product.getName() != null ? product.getName().toLowerCase() : "";

        if (category.contains("парфюм") || name.contains("parfum") || name.contains("парфюм")) {
            return CategoryScaleRules.builder().maxWidthRatio(0.65).maxHeightRatio(0.86).minMarginRatio(0.08).build();
        }
        if (name.contains("крем") || name.contains("jar") || name.contains("банка")) {
            return CategoryScaleRules.builder().maxWidthRatio(0.75).maxHeightRatio(0.70).minMarginRatio(0.08).build();
        }
        if (name.contains("tube") || name.contains("тушь") || name.contains("гель") || name.contains("сыворот")) {
            return CategoryScaleRules.builder().maxWidthRatio(0.72).maxHeightRatio(0.84).minMarginRatio(0.08).build();
        }
        if (name.contains("box") || name.contains("набор") || category.contains("космет")) {
            return CategoryScaleRules.builder().maxWidthRatio(0.84).maxHeightRatio(0.75).minMarginRatio(0.08).build();
        }
        return CategoryScaleRules.builder().maxWidthRatio(0.82).maxHeightRatio(0.82).minMarginRatio(0.08).build();
    }
}
