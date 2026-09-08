package com.plstk.loyaltybot.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.CustomerStatus;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.CustomerAchievementRepository;
import com.plstk.loyaltybot.repository.RedeemCodeRepository;
import com.plstk.loyaltybot.repository.TransactionRepository;
import com.plstk.loyaltybot.repository.UserRepository;
import com.plstk.loyaltybot.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * REST API для отчётов магазина.
 */
@RestController
@RequestMapping("/api/shops/{shopId}/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportsController {
    
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerAchievementRepository achievementRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final AuthorizationService authorizationService;
    
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_DATE_TIME;
    
    /**
     * Недельный отчёт для магазина.
     * 
     * GET /api/shops/{shopId}/reports/weekly
     */
    @GetMapping("/weekly")
    public ResponseEntity<WeeklyReportResponse> getWeeklyReport(@PathVariable String shopId,
                                                                 @AuthenticationPrincipal AdminUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        if (!hasAccessToShop(user, shopId)) return ResponseEntity.status(403).build();
        log.info("Generating weekly report for shopId={}", shopId);
        
        // Период: последние 7 дней
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);
        
        return ResponseEntity.ok(buildReport(shopId, weekAgo, now));
    }
    
    /**
     * Дневной отчёт для магазина.
     * 
     * GET /api/shops/{shopId}/reports/daily
     */
    @GetMapping("/daily")
    public ResponseEntity<WeeklyReportResponse> getDailyReport(@PathVariable String shopId,
                                                                @AuthenticationPrincipal AdminUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        if (!hasAccessToShop(user, shopId)) return ResponseEntity.status(403).build();
        log.info("Generating daily report for shopId={}", shopId);
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        return ResponseEntity.ok(buildReport(shopId, startOfDay, now));
    }
    
    /**
     * Месячный отчёт для магазина.
     * 
     * GET /api/shops/{shopId}/reports/monthly
     */
    @GetMapping("/monthly")
    public ResponseEntity<WeeklyReportResponse> getMonthlyReport(@PathVariable String shopId,
                                                                  @AuthenticationPrincipal AdminUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        if (!hasAccessToShop(user, shopId)) return ResponseEntity.status(403).build();
        log.info("Generating monthly report for shopId={}", shopId);
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthAgo = now.minusDays(30);
        
        return ResponseEntity.ok(buildReport(shopId, monthAgo, now));
    }
    
    /**
     * Строит отчёт за указанный период.
     */
    private WeeklyReportResponse buildReport(String shopId, LocalDateTime from, LocalDateTime to) {
        // Клиенты
        long registeredCustomers = userRepository.countRegisteredBetweenByShopId(shopId, from, to);
        long newCustomers = userRepository.countNewCustomersBetweenByShopId(shopId, from, to);
        long returningCustomers = userRepository.countReturningCustomersBetweenByShopId(shopId, from, to);
        long lostCustomers = userRepository.countLostCustomersBetweenByShopId(shopId, from, to);
        
        // Финансы
        long transactionsCount = transactionRepository.countBetweenByShopId(shopId, from, to);
        Double totalRevenue = transactionRepository.sumAmountBetweenByShopId(shopId, from, to);
        Double avgCheck = transactionRepository.averageAmountBetweenByShopId(shopId, from, to);
        long fastCheckoutCount = transactionRepository.countFastCheckoutBetweenByShopId(shopId, from, to);
        
        double fastCheckoutPercent = transactionsCount > 0 
            ? Math.round((double) fastCheckoutCount / transactionsCount * 100) 
            : 0;
        
        // Статусы клиентов (текущие)
        long newCount = userRepository.countByShopIdAndCustomerStatus(shopId, CustomerStatus.NEW);
        long regularCount = userRepository.countByShopIdAndCustomerStatus(shopId, CustomerStatus.REGULAR);
        long vipCount = userRepository.countByShopIdAndCustomerStatus(shopId, CustomerStatus.VIP);
        long lostCount = userRepository.countByShopIdAndCustomerStatus(shopId, CustomerStatus.LOST);
        
        // Топ клиентов
        List<User> topUsers = userRepository.findTopByTotalSpendByShopId(shopId);
        List<TopCustomerDto> topCustomers = topUsers.stream()
            .limit(5)
            .map(u -> new TopCustomerDto(
                u.getFirstName() != null ? u.getFirstName() : "Клиент",
                u.getTotalSpend() != null ? u.getTotalSpend() : 0.0,
                u.getPurchasesCount() != null ? u.getPurchasesCount() : 0
            ))
            .toList();
        
        // Достижения и награды
        long achievements = achievementRepository.countAwardedBetweenByShopId(shopId, from, to);
        long rewardsRedeemed = redeemCodeRepository.countRedeemedBetweenByShopId(shopId, from, to);
        
        return new WeeklyReportResponse(
            new PeriodDto(from.format(ISO_FORMAT), to.format(ISO_FORMAT)),
            new CustomersDto(newCustomers, registeredCustomers, returningCustomers, lostCustomers),
            new FinancesDto(
                transactionsCount,
                totalRevenue != null ? totalRevenue : 0.0,
                avgCheck != null ? avgCheck : 0.0,
                fastCheckoutCount,
                fastCheckoutPercent
            ),
            new StatusesDto(newCount, regularCount, vipCount, lostCount),
            topCustomers,
            achievements,
            rewardsRedeemed
        );
    }
    
    private boolean hasAccessToShop(AdminUser user, String shopId) {
        return authorizationService.hasAccess(user, shopId);
    }
    
    // ========== Response DTOs ==========
    
    public record WeeklyReportResponse(
        PeriodDto period,
        CustomersDto customers,
        FinancesDto finances,
        StatusesDto statuses,
        List<TopCustomerDto> topCustomers,
        long achievements,
        long rewardsRedeemed
    ) {}
    
    public record PeriodDto(String from, String to) {}
    
    public record CustomersDto(
        @JsonProperty("new") long newCustomers,
        long registered,
        long returning, 
        long lost
    ) {}
    
    public record FinancesDto(
        long transactions,
        double totalRevenue,
        double avgCheck,
        long fastCheckoutCount,
        double fastCheckoutPercent
    ) {}
    
    public record StatusesDto(
        @JsonProperty("new") long newCustomers, 
        long regular, 
        long vip, 
        long lost
    ) {}
    
    public record TopCustomerDto(String name, double totalSpend, int purchasesCount) {}
}
