package com.plstk.loyaltybot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LoyaltyBotApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(LoyaltyBotApplication.class, args);
    }
}