package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ProductImageQueryBuilder {

    public List<String> buildQueries(Product product) {
        Set<String> queries = new LinkedHashSet<>();
        String brand = trim(product.getBrand());
        String article = trim(product.getSupplierArticle());
        String barcode = trim(product.getBarcode());
        String name = trim(product.getName());
        String category = trim(product.getCategoryPath());

        if (StringUtils.hasText(barcode)) {
            queries.add("\"" + barcode + "\"");
        }
        if (StringUtils.hasText(brand) && StringUtils.hasText(article)) {
            queries.add("\"" + brand + "\" \"" + article + "\"");
        }
        if (StringUtils.hasText(brand) && StringUtils.hasText(name)) {
            queries.add("\"" + brand + "\" \"" + name + "\"");
        }
        if (StringUtils.hasText(brand) && StringUtils.hasText(article) && StringUtils.hasText(name)) {
            queries.add("\"" + brand + "\" \"" + article + "\" \"" + name + "\"");
        }

        boolean perfume = isPerfume(category, name);
        if (perfume && StringUtils.hasText(brand) && StringUtils.hasText(name)) {
            queries.add("\"" + brand + "\" \"" + name + "\" парфюм");
            queries.add("\"" + brand + "\" \"" + name + "\" perfume");
        } else if (StringUtils.hasText(brand) && StringUtils.hasText(name)) {
            queries.add("\"" + brand + "\" \"" + name + "\" косметика");
            queries.add("\"" + brand + "\" \"" + name + "\" купить");
        }

        return new ArrayList<>(queries);
    }

    private boolean isPerfume(String category, String name) {
        String combined = ((category != null ? category : "") + " " + (name != null ? name : "")).toLowerCase();
        return combined.contains("парфюм")
                || combined.contains("parfum")
                || combined.contains("perfume")
                || combined.contains("eau de")
                || combined.contains("духи")
                || combined.contains("extrait")
                || combined.contains(" edp")
                || combined.contains(" edt");
    }

    private String trim(String value) {
        return value != null ? value.trim() : null;
    }
}
