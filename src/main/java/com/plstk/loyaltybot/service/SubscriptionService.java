package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.Plan;
import com.plstk.loyaltybot.entity.Subscription;
import com.plstk.loyaltybot.repository.PlanRepository;
import com.plstk.loyaltybot.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Сервис для управления подписками.
 * Billing enforcement отключен (warn only mode).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {
    
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    
    @Value("${billing.trial-days:7}")
    private int trialDays;
    
    @Value("${billing.enforcement-mode:OFF}")
    private String enforcementMode;
    
    /**
     * Создаёт trial подписку для нового магазина
     */
    @Transactional
    public Subscription createTrialSubscription(String shopId) {
        log.info("Creating trial subscription for shopId={}", shopId);
        
        // Проверяем, нет ли уже подписки
        Optional<Subscription> existing = subscriptionRepository.findByShopId(shopId);
        if (existing.isPresent()) {
            log.warn("Subscription already exists for shopId={}", shopId);
            return existing.get();
        }
        
        Subscription subscription = Subscription.createTrial(shopId, trialDays);
        subscription = subscriptionRepository.save(subscription);
        
        log.info("Trial subscription created: id={}, shopId={}, trialEndAt={}", 
            subscription.getId(), shopId, subscription.getTrialEndAt());
        
        return subscription;
    }
    
    /**
     * Получает подписку магазина
     */
    public Optional<Subscription> getSubscription(String shopId) {
        return subscriptionRepository.findByShopId(shopId);
    }
    
    /**
     * Активирует подписку (заглушка — без реальной оплаты)
     */
    @Transactional
    public Subscription activateStub(String shopId, String planCode) {
        log.info("Activating stub subscription for shopId={}, planCode={}", shopId, planCode);
        
        Subscription subscription = subscriptionRepository.findByShopId(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found for shopId=" + shopId));
        
        Plan plan = planRepository.findByCode(planCode).orElse(null);
        int periodDays = plan != null ? plan.getPeriodDays() : 30;
        
        LocalDateTime now = LocalDateTime.now();
        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setPlanCode(planCode);
        subscription.setProvider(Subscription.PaymentProvider.STUB);
        subscription.setCurrentPeriodStartAt(now);
        subscription.setCurrentPeriodEndAt(now.plusDays(periodDays));
        subscription.setExpirationNotified(false);
        
        subscription = subscriptionRepository.save(subscription);
        
        log.info("Stub subscription activated: id={}, shopId={}, periodEndAt={}", 
            subscription.getId(), shopId, subscription.getCurrentPeriodEndAt());
        
        return subscription;
    }
    
    /**
     * Продлевает trial период (для саппорта)
     */
    @Transactional
    public Subscription extendTrial(String shopId, int days) {
        log.info("Extending trial for shopId={} by {} days", shopId, days);
        
        Subscription subscription = subscriptionRepository.findByShopId(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found for shopId=" + shopId));
        
        if (subscription.getTrialEndAt() != null) {
            subscription.setTrialEndAt(subscription.getTrialEndAt().plusDays(days));
        } else {
            subscription.setTrialEndAt(LocalDateTime.now().plusDays(days));
        }
        
        // Если был EXPIRED, возвращаем в TRIALING
        if (subscription.getStatus() == Subscription.SubscriptionStatus.EXPIRED) {
            subscription.setStatus(Subscription.SubscriptionStatus.TRIALING);
        }
        
        subscription.setExpirationNotified(false);
        subscription = subscriptionRepository.save(subscription);
        
        log.info("Trial extended: id={}, shopId={}, newTrialEndAt={}", 
            subscription.getId(), shopId, subscription.getTrialEndAt());
        
        return subscription;
    }
    
    /**
     * Возвращает информацию о подписке с вычисленными полями
     */
    public SubscriptionInfo getSubscriptionInfo(String shopId) {
        Optional<Subscription> subOpt = subscriptionRepository.findByShopId(shopId);
        
        if (subOpt.isEmpty()) {
            return new SubscriptionInfo(
                null,
                "NO_SUBSCRIPTION",
                0,
                null,
                enforcementMode
            );
        }
        
        Subscription sub = subOpt.get();
        
        return new SubscriptionInfo(
            sub,
            sub.getStatus().name(),
            sub.getDaysLeft(),
            sub.getEffectiveEndDate(),
            enforcementMode
        );
    }
    
    /**
     * Информация о подписке для API
     */
    public record SubscriptionInfo(
        Subscription subscription,
        String status,
        long daysLeft,
        LocalDateTime endsAt,
        String billingEnforcementMode
    ) {}
    
    /**
     * Cron job: проверяет и обновляет истёкшие подписки.
     * Запускается каждый день в 3:00.
     * НЕ блокирует функционал, только обновляет статус.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void checkExpiredSubscriptions() {
        log.info("Running expired subscriptions check...");
        
        LocalDateTime now = LocalDateTime.now();
        
        // Проверяем истёкшие trial
        List<Subscription> expiredTrials = subscriptionRepository.findExpiredTrials(now);
        for (Subscription sub : expiredTrials) {
            sub.setStatus(Subscription.SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            log.info("Trial expired for shopId={} (enforcement={})", sub.getShopId(), enforcementMode);
        }
        
        // Проверяем истёкшие активные подписки
        List<Subscription> expiredActive = subscriptionRepository.findExpiredActive(now);
        for (Subscription sub : expiredActive) {
            sub.setStatus(Subscription.SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            log.info("Subscription expired for shopId={} (enforcement={})", sub.getShopId(), enforcementMode);
        }
        
        log.info("Expired check complete: {} trials, {} active marked as expired", 
            expiredTrials.size(), expiredActive.size());
    }
    
    /**
     * Инициализирует стандартные планы при старте
     */
    @Transactional
    public void initDefaultPlans() {
        if (!planRepository.existsByCode(Plan.FREE_TRIAL)) {
            planRepository.save(Plan.builder()
                .code(Plan.FREE_TRIAL)
                .name("Пробный период")
                .description("7 дней бесплатного использования")
                .priceAmount(0)
                .periodDays(7)
                .isStub(true)
                .build());
        }
        
        if (!planRepository.existsByCode(Plan.BASIC_MONTHLY)) {
            planRepository.save(Plan.builder()
                .code(Plan.BASIC_MONTHLY)
                .name("Базовый (месяц)")
                .description("Базовый тариф на месяц")
                .priceAmount(99000) // 990 руб в копейках
                .currency("RUB")
                .periodDays(30)
                .isStub(true)
                .build());
        }
        
        if (!planRepository.existsByCode(Plan.BASIC_YEARLY)) {
            planRepository.save(Plan.builder()
                .code(Plan.BASIC_YEARLY)
                .name("Базовый (год)")
                .description("Базовый тариф на год")
                .priceAmount(990000) // 9900 руб в копейках
                .currency("RUB")
                .periodDays(365)
                .isStub(true)
                .build());
        }
        
        log.info("Default plans initialized");
    }
}
