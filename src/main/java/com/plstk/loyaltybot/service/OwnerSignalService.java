package com.plstk.loyaltybot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.repository.OwnerSignalRepository;
import com.plstk.loyaltybot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Сервис для генерации и управления сигналами владельцу.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerSignalService {
    
    private final OwnerSignalRepository ownerSignalRepository;
    private final UserRepository userRepository;
    private final ShopSettingsService shopSettingsService;
    private final StampWalletService stampWalletService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // ========== Создание сигналов ==========
    
    /**
     * Создаёт новый сигнал
     */
    @Transactional
    public OwnerSignal createSignal(
            OwnerSignal.SignalType type,
            OwnerSignal.Severity severity,
            String title,
            String description,
            User customer,
            String suggestedAction,
            Map<String, Object> payload,
            LocalDateTime expiresAt) {
        
        // Проверяем, нет ли недавнего дубликата
        if (customer != null) {
            LocalDateTime since = LocalDateTime.now().minusHours(24);
            if (ownerSignalRepository.existsRecentSignalForCustomer(type, customer, since)) {
                log.debug("Skipping duplicate signal {} for customer {}", type, customer.getChatId());
                return null;
            }
        }
        
        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize payload", e);
            }
        }
        
        OwnerSignal signal = OwnerSignal.builder()
            .signalType(type)
            .severity(severity)
            .title(title)
            .description(description)
            .customer(customer)
            .suggestedAction(suggestedAction)
            .payload(payloadJson)
            .expiresAt(expiresAt)
            .isSeen(false)
            .isDismissed(false)
            .build();
        
        OwnerSignal saved = ownerSignalRepository.save(signal);
        log.info("Created signal: {} - {}", type, title);
        
        return saved;
    }
    
    // ========== Scheduled генерация сигналов ==========
    
    /**
     * Ежедневная проверка потерянных клиентов
     */
    @Scheduled(cron = "0 0 9 * * *") // Каждый день в 9:00
    @Transactional
    public void checkLostCustomers() {
        int lostDays = shopSettingsService.getLostDaysSinceLastPurchase();
        LocalDateTime threshold = LocalDateTime.now().minusDays(lostDays);
        
        List<User> lostCustomers = userRepository.findByState(User.UserState.REGISTERED).stream()
            .filter(u -> u.getLastPurchaseAt() != null && u.getLastPurchaseAt().isBefore(threshold))
            .filter(u -> u.getCustomerStatus() != CustomerStatus.LOST) // Ещё не помечены как LOST
            .toList();
        
        if (lostCustomers.isEmpty()) {
            return;
        }
        
        // Создаём один общий сигнал о потерянных клиентах
        Map<String, Object> payload = new HashMap<>();
        payload.put("customerCount", lostCustomers.size());
        payload.put("customerIds", lostCustomers.stream().map(User::getId).limit(10).toList());
        
        createSignal(
            OwnerSignal.SignalType.LOST_CUSTOMERS,
            OwnerSignal.Severity.WARNING,
            "Потерянные клиенты: " + lostCustomers.size(),
            "Клиенты не совершали покупок более " + lostDays + " дней",
            null,
            "Отправьте им напоминание через Message Composer",
            payload,
            LocalDateTime.now().plusDays(7)
        );
        
        log.info("Created LOST_CUSTOMERS signal for {} customers", lostCustomers.size());
    }
    
    /**
     * Проверка неактивных VIP клиентов
     */
    @Scheduled(cron = "0 0 10 * * *") // Каждый день в 10:00
    @Transactional
    public void checkInactiveVipCustomers() {
        int inactiveDays = 14; // VIP неактивен 2 недели
        LocalDateTime threshold = LocalDateTime.now().minusDays(inactiveDays);
        
        List<User> inactiveVips = userRepository.findByState(User.UserState.REGISTERED).stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.VIP)
            .filter(u -> u.getLastPurchaseAt() != null && u.getLastPurchaseAt().isBefore(threshold))
            .toList();
        
        for (User vip : inactiveVips) {
            long daysSince = ChronoUnit.DAYS.between(vip.getLastPurchaseAt(), LocalDateTime.now());
            
            createSignal(
                OwnerSignal.SignalType.VIP_INACTIVE,
                OwnerSignal.Severity.IMPORTANT,
                "VIP клиент неактивен: " + vip.getFirstName(),
                "Не был " + daysSince + " дней. Всего покупок: " + vip.getPurchasesCount(),
                vip,
                "Отправьте персональное сообщение",
                Map.of("daysSince", daysSince, "totalSpend", vip.getTotalSpend()),
                LocalDateTime.now().plusDays(7)
            );
        }
        
        if (!inactiveVips.isEmpty()) {
            log.info("Created VIP_INACTIVE signals for {} VIP customers", inactiveVips.size());
        }
    }
    
    /**
     * Проверка клиентов близких к REGULAR
     */
    @Scheduled(cron = "0 30 10 * * *") // Каждый день в 10:30
    @Transactional
    public void checkAlmostRegularCustomers() {
        int threshold = shopSettingsService.getRegularThresholdPurchases();
        
        List<User> almostRegular = userRepository.findByState(User.UserState.REGISTERED).stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.NEW)
            .filter(u -> u.getPurchasesCount() != null && u.getPurchasesCount() == threshold - 1)
            .toList();
        
        for (User user : almostRegular) {
            createSignal(
                OwnerSignal.SignalType.ALMOST_REGULAR,
                OwnerSignal.Severity.INFO,
                "Почти постоянный: " + user.getFirstName(),
                "Осталась 1 покупка до статуса REGULAR",
                user,
                "Клиент близок к лояльности!",
                Map.of("purchasesCount", user.getPurchasesCount()),
                LocalDateTime.now().plusDays(7)
            );
        }
    }
    
    /**
     * Проверка клиентов близких к VIP
     */
    @Scheduled(cron = "0 30 10 * * *") // Каждый день в 10:30
    @Transactional
    public void checkAlmostVipCustomers() {
        int threshold = shopSettingsService.getVipThresholdPurchases();
        
        List<User> almostVip = userRepository.findByState(User.UserState.REGISTERED).stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.REGULAR)
            .filter(u -> u.getPurchasesCount() != null && u.getPurchasesCount() == threshold - 1)
            .toList();
        
        for (User user : almostVip) {
            createSignal(
                OwnerSignal.SignalType.ALMOST_VIP,
                OwnerSignal.Severity.INFO,
                "Почти VIP: " + user.getFirstName(),
                "Осталась 1 покупка до статуса VIP!",
                user,
                "Отличный клиент, скоро станет VIP",
                Map.of("purchasesCount", user.getPurchasesCount(), "totalSpend", user.getTotalSpend()),
                LocalDateTime.now().plusDays(7)
            );
        }
    }
    
    /**
     * Еженедельный отчёт (каждый понедельник)
     */
    @Scheduled(cron = "0 0 9 * * MON")
    @Transactional
    public void generateWeeklySummary() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        
        List<User> allUsers = userRepository.findByState(User.UserState.REGISTERED);
        
        // Статистика за неделю
        long newCustomers = allUsers.stream()
            .filter(u -> u.getFirstPurchaseAt() != null && u.getFirstPurchaseAt().isAfter(weekAgo))
            .count();
        
        long returnedCustomers = allUsers.stream()
            .filter(u -> u.getLastPurchaseAt() != null && u.getLastPurchaseAt().isAfter(weekAgo))
            .filter(u -> u.getCustomerStatus() == CustomerStatus.LOST || 
                        (u.getPurchasesCount() != null && u.getPurchasesCount() > 1))
            .count();
        
        long lostCustomers = allUsers.stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.LOST)
            .count();
        
        long vipCount = allUsers.stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.VIP)
            .count();
        
        double avgCheck = allUsers.stream()
            .filter(u -> u.getAverageCheck() != null && u.getAverageCheck() > 0)
            .mapToDouble(User::getAverageCheck)
            .average()
            .orElse(0);
        
        StringBuilder description = new StringBuilder();
        description.append("📊 Статистика за неделю:\n");
        description.append("• Новых клиентов: ").append(newCustomers).append("\n");
        description.append("• Вернувшихся: ").append(returnedCustomers).append("\n");
        description.append("• Потерянных: ").append(lostCustomers).append("\n");
        description.append("• VIP клиентов: ").append(vipCount).append("\n");
        description.append("• Средний чек: ").append(String.format("%.2f", avgCheck)).append(" руб.");
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("newCustomers", newCustomers);
        payload.put("returnedCustomers", returnedCustomers);
        payload.put("lostCustomers", lostCustomers);
        payload.put("vipCount", vipCount);
        payload.put("avgCheck", avgCheck);
        payload.put("totalCustomers", allUsers.size());
        
        createSignal(
            OwnerSignal.SignalType.WEEKLY_SUMMARY,
            OwnerSignal.Severity.INFO,
            "Недельный отчёт",
            description.toString(),
            null,
            null,
            payload,
            LocalDateTime.now().plusDays(7)
        );
        
        log.info("Generated weekly summary signal");
    }
    
    // ========== Управление сигналами ==========
    
    /**
     * Получает активные сигналы
     */
    public List<OwnerSignal> getActiveSignals() {
        return ownerSignalRepository.findActiveSignals(LocalDateTime.now());
    }
    
    /**
     * Получает непрочитанные сигналы
     */
    public List<OwnerSignal> getUnseenSignals() {
        return ownerSignalRepository.findUnseenSignals(LocalDateTime.now());
    }
    
    /**
     * Считает непрочитанные сигналы
     */
    public long countUnseenSignals() {
        return ownerSignalRepository.countUnseenSignals(LocalDateTime.now());
    }
    
    /**
     * Помечает сигнал как прочитанный
     */
    @Transactional
    public void markAsSeen(Long signalId) {
        ownerSignalRepository.findById(signalId).ifPresent(signal -> {
            signal.setIsSeen(true);
            signal.setSeenAt(LocalDateTime.now());
            ownerSignalRepository.save(signal);
        });
    }
    
    /**
     * Помечает все сигналы как прочитанные
     */
    @Transactional
    public void markAllAsSeen() {
        List<OwnerSignal> unseen = getUnseenSignals();
        LocalDateTime now = LocalDateTime.now();
        
        for (OwnerSignal signal : unseen) {
            signal.setIsSeen(true);
            signal.setSeenAt(now);
        }
        
        ownerSignalRepository.saveAll(unseen);
    }
    
    /**
     * Скрывает (dismiss) сигнал
     */
    @Transactional
    public void dismissSignal(Long signalId) {
        ownerSignalRepository.findById(signalId).ifPresent(signal -> {
            signal.setIsDismissed(true);
            signal.setDismissedAt(LocalDateTime.now());
            ownerSignalRepository.save(signal);
        });
    }
    
    /**
     * Очистка истёкших сигналов
     */
    @Scheduled(cron = "0 0 3 * * *") // Каждый день в 3:00
    @Transactional
    public void cleanupExpiredSignals() {
        List<OwnerSignal> expired = ownerSignalRepository
            .findByExpiresAtBeforeAndIsDismissedFalse(LocalDateTime.now());
        
        for (OwnerSignal signal : expired) {
            signal.setIsDismissed(true);
            signal.setDismissedAt(LocalDateTime.now());
        }
        
        ownerSignalRepository.saveAll(expired);
        
        if (!expired.isEmpty()) {
            log.info("Cleaned up {} expired signals", expired.size());
        }
    }
    
    /**
     * Форматирует сигналы для отображения в боте
     */
    public String formatSignalsForDisplay(List<OwnerSignal> signals, int limit) {
        if (signals.isEmpty()) {
            return "✅ Нет новых уведомлений";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("🔔 *Уведомления* (").append(signals.size()).append(")\n\n");
        
        int count = 0;
        for (OwnerSignal signal : signals) {
            if (count >= limit) {
                sb.append("\n... и ещё ").append(signals.size() - limit);
                break;
            }
            
            sb.append(signal.getFormattedDisplay()).append("\n\n");
            count++;
        }
        
        return sb.toString();
    }
}



