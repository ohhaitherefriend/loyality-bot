package com.plstk.loyaltybot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Конфигурация асинхронной обработки и планировщика задач.
 * Отдельные пулы потоков для Telegram и Max Updates.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
    
    @Bean(name = "telegramUpdateExecutor")
    public Executor telegramUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("tg-update-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }
    
    @Bean(name = "maxUpdateExecutor")
    public Executor maxUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("max-update-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }
}

