package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.CustomerBadge;
import com.plstk.loyaltybot.entity.ManualBadgeDefinition;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.CustomerBadgeRepository;
import com.plstk.loyaltybot.repository.ManualBadgeDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Сервис для управления ручными бейджами.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualBadgeService {
    
    private final ManualBadgeDefinitionRepository badgeDefinitionRepository;
    private final CustomerBadgeRepository customerBadgeRepository;
    private final ShopSettingsService shopSettingsService;
    
    // Максимум активных бейджей на клиента (глобальный лимит)
    private static final int MAX_ACTIVE_BADGES_PER_CUSTOMER = 2;
    
    // ========== Выдача бейджей ==========
    
    /**
     * Результат выдачи бейджа
     */
    public record AwardResult(
        boolean success,
        String error,
        CustomerBadge badge
    ) {}
    
    /**
     * Выдаёт бейдж клиенту
     */
    @Transactional
    public AwardResult awardBadge(User customer, ManualBadgeDefinition badge, User awardedBy, String reason) {
        // Проверяем лимит бейджей на клиента
        long activeBadgesCount = customerBadgeRepository.countActiveByUser(customer, LocalDateTime.now());
        if (activeBadgesCount >= MAX_ACTIVE_BADGES_PER_CUSTOMER) {
            return new AwardResult(false, 
                "У клиента уже максимум активных бейджей (" + MAX_ACTIVE_BADGES_PER_CUSTOMER + ")", 
                null);
        }
        
        // Проверяем лимит этого бейджа на клиента
        if (badge.getMaxPerCustomer() != null) {
            long badgeCount = customerBadgeRepository.countActiveByUserAndBadge(customer, badge, LocalDateTime.now());
            if (badgeCount >= badge.getMaxPerCustomer()) {
                return new AwardResult(false, 
                    "У клиента уже есть этот бейдж", 
                    null);
            }
        }
        
        // Проверяем месячный лимит выдач
        if (badge.getMaxAwardsPerMonth() != null) {
            LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            long monthlyAwards = customerBadgeRepository.countAwardsSince(badge, startOfMonth);
            if (monthlyAwards >= badge.getMaxAwardsPerMonth()) {
                return new AwardResult(false, 
                    "Превышен месячный лимит выдачи этого бейджа", 
                    null);
            }
        }
        
        // Создаём бейдж
        LocalDateTime expiresAt = null;
        if (badge.getValidDays() != null) {
            expiresAt = LocalDateTime.now().plusDays(badge.getValidDays());
        }
        
        CustomerBadge customerBadge = CustomerBadge.builder()
            .user(customer)
            .badge(badge)
            .awardedBy(awardedBy)
            .awardedAt(LocalDateTime.now())
            .expiresAt(expiresAt)
            .status(CustomerBadge.BadgeStatus.ACTIVE)
            .reason(reason)
            .notificationSent(false)
            .build();
        
        CustomerBadge saved = customerBadgeRepository.save(customerBadge);
        
        log.info("Awarded badge '{}' to user chatId={} by admin chatId={}", 
            badge.getTitle(), customer.getChatId(), 
            awardedBy != null ? awardedBy.getChatId() : "system");
        
        return new AwardResult(true, null, saved);
    }
    
    /**
     * Отзывает бейдж
     */
    @Transactional
    public void revokeBadge(CustomerBadge badge, String reason) {
        badge.setStatus(CustomerBadge.BadgeStatus.REVOKED);
        customerBadgeRepository.save(badge);
        
        log.info("Revoked badge '{}' from user chatId={}, reason: {}", 
            badge.getBadge().getTitle(), badge.getUser().getChatId(), reason);
    }
    
    // ========== Применение perks ==========
    
    /**
     * Рассчитывает множитель штампов для пользователя (на основе активных бейджей)
     */
    public int calculateStampsMultiplier(User user) {
        List<CustomerBadge> activeBadges = customerBadgeRepository.findActiveByUserAndPerkType(
            user, ManualBadgeDefinition.PerkType.BONUS_STAMPS_MULTIPLIER, LocalDateTime.now());
        
        // Берём максимальный множитель (не складываем)
        int maxMultiplier = 1;
        for (CustomerBadge cb : activeBadges) {
            Integer value = cb.getBadge().getPerkValue();
            if (value != null && value > maxMultiplier) {
                maxMultiplier = value;
            }
        }
        
        return maxMultiplier;
    }
    
    /**
     * Рассчитывает бонусные штампы для пользователя
     */
    public int calculateBonusStamps(User user) {
        List<CustomerBadge> activeBadges = customerBadgeRepository.findActiveByUserAndPerkType(
            user, ManualBadgeDefinition.PerkType.BONUS_STAMPS_FLAT, LocalDateTime.now());
        
        // Суммируем все бонусные штампы
        int totalBonus = 0;
        for (CustomerBadge cb : activeBadges) {
            Integer value = cb.getBadge().getPerkValue();
            if (value != null) {
                totalBonus += value;
            }
        }
        
        return totalBonus;
    }
    
    /**
     * Рассчитывает множитель баллов для пользователя
     */
    public int calculatePointsMultiplier(User user) {
        List<CustomerBadge> activeBadges = customerBadgeRepository.findActiveByUserAndPerkType(
            user, ManualBadgeDefinition.PerkType.BONUS_POINTS_MULTIPLIER, LocalDateTime.now());
        
        int maxMultiplier = 1;
        for (CustomerBadge cb : activeBadges) {
            Integer value = cb.getBadge().getPerkValue();
            if (value != null && value > maxMultiplier) {
                maxMultiplier = value;
            }
        }
        
        return maxMultiplier;
    }
    
    /**
     * Рассчитывает бонусные баллы для пользователя
     */
    public int calculateBonusPoints(User user) {
        List<CustomerBadge> activeBadges = customerBadgeRepository.findActiveByUserAndPerkType(
            user, ManualBadgeDefinition.PerkType.BONUS_POINTS_FLAT, LocalDateTime.now());
        
        int totalBonus = 0;
        for (CustomerBadge cb : activeBadges) {
            Integer value = cb.getBadge().getPerkValue();
            if (value != null) {
                totalBonus += value;
            }
        }
        
        return totalBonus;
    }
    
    /**
     * Проверяет, защищён ли пользователь от потери статуса
     */
    public boolean hasStatusProtection(User user) {
        return customerBadgeRepository.hasActivePerkType(
            user, ManualBadgeDefinition.PerkType.STATUS_PROTECTION, LocalDateTime.now());
    }
    
    /**
     * Проверяет, есть ли у пользователя приоритетный статус
     */
    public boolean hasPriorityStatus(User user) {
        return customerBadgeRepository.hasActivePerkType(
            user, ManualBadgeDefinition.PerkType.PRIORITY_STATUS, LocalDateTime.now());
    }
    
    // ========== Получение информации ==========
    
    /**
     * Получает активные бейджи пользователя
     */
    public List<CustomerBadge> getActiveBadges(User user) {
        return customerBadgeRepository.findActiveByUser(user, LocalDateTime.now());
    }
    
    /**
     * Получает все бейджи пользователя (включая истёкшие)
     */
    public List<CustomerBadge> getAllBadges(User user) {
        return customerBadgeRepository.findByUserOrderByAwardedAtDesc(user);
    }
    
    /**
     * Получает все доступные для выдачи бейджи
     */
    public List<ManualBadgeDefinition> getAvailableBadges() {
        return badgeDefinitionRepository.findByIsActiveTrueOrderByDisplayOrderDesc();
    }
    
    /**
     * Получает бейдж по ID
     */
    public Optional<ManualBadgeDefinition> getBadgeById(Long id) {
        return badgeDefinitionRepository.findById(id);
    }
    
    /**
     * Форматирует бейджи для отображения клиенту
     */
    public String formatBadgesForDisplay(User user) {
        List<CustomerBadge> activeBadges = getActiveBadges(user);
        
        if (activeBadges.isEmpty()) {
            return "🎖 *Ваши бейджи*\n\nУ вас пока нет бейджей.\nБейджи выдаются администратором за особые заслуги!";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("🎖 *Ваши бейджи*\n\n");
        
        for (CustomerBadge cb : activeBadges) {
            sb.append(cb.getFormattedDisplay()).append("\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Форматирует список бейджей для выдачи (админ)
     */
    public String formatBadgesForAdmin() {
        List<ManualBadgeDefinition> badges = getAvailableBadges();
        
        if (badges.isEmpty()) {
            return "🎖 Нет доступных бейджей для выдачи";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("🎖 *Доступные бейджи:*\n\n");
        
        for (int i = 0; i < badges.size(); i++) {
            ManualBadgeDefinition badge = badges.get(i);
            sb.append(i + 1).append(". ").append(badge.getDisplayWithEmoji()).append("\n");
            sb.append("   _").append(badge.getPerkDescription()).append("_\n");
        }
        
        return sb.toString();
    }
    
    // ========== Scheduled jobs ==========
    
    /**
     * Обновляет статусы истёкших бейджей
     */
    @Scheduled(fixedRate = 3600000) // Каждый час
    @Transactional
    public void expireBadges() {
        List<CustomerBadge> expiredBadges = customerBadgeRepository.findExpiredActiveBadges(LocalDateTime.now());
        
        for (CustomerBadge badge : expiredBadges) {
            badge.setStatus(CustomerBadge.BadgeStatus.EXPIRED);
            customerBadgeRepository.save(badge);
            
            log.info("Badge '{}' expired for user chatId={}", 
                badge.getBadge().getTitle(), badge.getUser().getChatId());
        }
        
        if (!expiredBadges.isEmpty()) {
            log.info("Expired {} badges", expiredBadges.size());
        }
    }
    
    /**
     * Помечает уведомление как отправленное
     */
    @Transactional
    public void markNotificationSent(CustomerBadge badge) {
        badge.setNotificationSent(true);
        customerBadgeRepository.save(badge);
    }
    
    // ========== Создание бейджей ==========
    
    /**
     * Создаёт новый бейдж (для админки или миграций)
     */
    @Transactional
    public ManualBadgeDefinition createBadge(
            String title, String description, String emoji,
            ManualBadgeDefinition.PerkType perkType, Integer perkValue,
            Integer validDays, Integer maxPerCustomer) {
        
        ManualBadgeDefinition badge = ManualBadgeDefinition.builder()
            .title(title)
            .description(description)
            .emoji(emoji)
            .perkType(perkType)
            .perkValue(perkValue)
            .validDays(validDays)
            .maxPerCustomer(maxPerCustomer)
            .isActive(true)
            .build();
        
        return badgeDefinitionRepository.save(badge);
    }
}



