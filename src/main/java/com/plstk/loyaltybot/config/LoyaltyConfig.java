package com.plstk.loyaltybot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "loyalty")
@Data
public class LoyaltyConfig {
    
    private PurchaseCode purchaseCode = new PurchaseCode();
    private DiscountCode discountCode = new DiscountCode();
    private Points points = new Points();
    
    @Data
    public static class PurchaseCode {
        private int expirationMinutes = 10;
        private int length = 6;
    }
    
    @Data
    public static class DiscountCode {
        private int length = 8;
    }
    
    @Data
    public static class Points {
        private int bronzeThreshold = 0;
        private int silverThreshold = 500;
        private int goldThreshold = 1000;
    }
}