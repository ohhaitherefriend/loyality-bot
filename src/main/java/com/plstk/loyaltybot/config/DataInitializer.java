package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Инициализирует начальные данные при старте приложения.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {
    
    private final SubscriptionService subscriptionService;
    
    @Override
    public void run(String... args) {
        log.info("Initializing default data...");
        
        // Инициализируем стандартные планы подписки
        subscriptionService.initDefaultPlans();
        
        log.info("Default data initialization complete");
    }
}
