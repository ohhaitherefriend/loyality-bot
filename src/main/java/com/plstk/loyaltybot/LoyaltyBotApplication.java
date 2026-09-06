package com.plstk.loyaltybot;

import com.plstk.loyaltybot.config.CommerceProperties;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({CommerceProperties.class, SupplierImportProperties.class})
public class LoyaltyBotApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(LoyaltyBotApplication.class, args);
    }
}