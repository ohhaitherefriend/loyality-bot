package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.CustomerStatus;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.CustomerAchievementRepository;
import com.plstk.loyaltybot.repository.RedeemCodeRepository;
import com.plstk.loyaltybot.repository.TransactionRepository;
import com.plstk.loyaltybot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Сервис для генерации еженедельных отчётов.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportService {
    
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerAchievementRepository achievementRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final ShopSettingsService shopSettingsService;
    
    private Consumer<String> reportSender;
    
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(new Locale("ru", "RU"));
    
    /**
     * Устанавливает функцию отправки отчёта (инжектится из бота)
     */
    public void setReportSender(Consumer<String> sender) {
        this.reportSender = sender;
    }
    
    /**
     * Генерирует еженедельный отчёт (каждый понедельник в 9:00)
     */
    @Scheduled(cron = "0 0 9 * * MON")
    public void generateWeeklyReport() {
        log.info("Generating weekly report...");
        
        String report = buildWeeklyReport();
        
        if (reportSender != null) {
            reportSender.accept(report);
            log.info("Weekly report sent successfully");
        } else {
            log.warn("Report sender not configured, report:\n{}", report);
        }
    }
    
    /**
     * Строит еженедельный отчёт за прошлую неделю
     */
    public String buildWeeklyReport() {
        // Период: прошлый понедельник 00:00 — этот понедельник 00:00
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thisMonday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime lastMonday = thisMonday.minusWeeks(1);
        
        return buildReport(lastMonday, thisMonday, "Недельный отчёт");
    }
    
    /**
     * Строит отчёт за произвольный период
     */
    public String buildReport(LocalDateTime from, LocalDateTime to, String title) {
        StringBuilder sb = new StringBuilder();
        
        // Заголовок
        sb.append("📊 *").append(title).append("*\n");
        sb.append("_").append(from.format(DATE_FORMAT)).append(" — ").append(to.format(DATE_FORMAT)).append("_\n\n");
        
        // Клиенты
        long newCustomers = userRepository.countNewCustomersBetween(from, to);
        long returningCustomers = userRepository.countReturningCustomersBetween(from, to);
        List<User> lostCustomers = userRepository.findByStatusChangedBetween(CustomerStatus.LOST, from, to);
        
        sb.append("👥 *Клиенты*\n");
        sb.append("• Новых: ").append(newCustomers).append("\n");
        sb.append("• Вернувшихся: ").append(returningCustomers).append("\n");
        sb.append("• Потерянных: ").append(lostCustomers.size()).append("\n\n");
        
        // Финансы
        long transactionsCount = transactionRepository.countBetween(from, to);
        Double totalRevenue = transactionRepository.sumAmountBetween(from, to);
        Double avgCheck = transactionRepository.averageAmountBetween(from, to);
        long fastCheckoutCount = transactionRepository.countFastCheckoutBetween(from, to);
        
        sb.append("💰 *Финансы*\n");
        sb.append("• Транзакций: ").append(transactionsCount).append("\n");
        if (totalRevenue != null && totalRevenue > 0) {
            sb.append("• Выручка: ").append(formatCurrency(totalRevenue)).append("\n");
        }
        if (avgCheck != null && avgCheck > 0) {
            sb.append("• Средний чек: ").append(formatCurrency(avgCheck)).append("\n");
        }
        if (fastCheckoutCount > 0) {
            sb.append("• Fast checkout: ").append(fastCheckoutCount).append(" (")
              .append(String.format("%.0f%%", (double) fastCheckoutCount / transactionsCount * 100))
              .append(")\n");
        }
        sb.append("\n");
        
        // Статусы клиентов (текущие)
        long newCount = userRepository.countByCustomerStatus(CustomerStatus.NEW);
        long regularCount = userRepository.countByCustomerStatus(CustomerStatus.REGULAR);
        long vipCount = userRepository.countByCustomerStatus(CustomerStatus.VIP);
        long lostCount = userRepository.countByCustomerStatus(CustomerStatus.LOST);
        
        sb.append("📈 *Статусы клиентов*\n");
        sb.append("• NEW: ").append(newCount).append("\n");
        sb.append("• REGULAR: ").append(regularCount).append("\n");
        sb.append("• VIP: ").append(vipCount).append("\n");
        sb.append("• LOST: ").append(lostCount).append("\n\n");
        
        // Топ-5 клиентов
        List<User> topCustomers = userRepository.findTopByTotalSpend();
        if (!topCustomers.isEmpty()) {
            sb.append("🏆 *Топ-5 клиентов (по сумме)*\n");
            int rank = 1;
            for (User user : topCustomers.stream().limit(5).toList()) {
                String name = user.getFirstName() != null ? user.getFirstName() : "Клиент";
                Double spend = user.getTotalSpend() != null ? user.getTotalSpend() : 0.0;
                sb.append(rank++).append(". ").append(name);
                if (spend > 0) {
                    sb.append(" — ").append(formatCurrency(spend));
                }
                if (user.getPurchasesCount() != null) {
                    sb.append(" (").append(user.getPurchasesCount()).append(" пок.)");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        
        // Награды (если штампы включены)
        if (shopSettingsService.isStampsEnabled()) {
            // Количество погашенных наград можно получить через RedeemCode
            // Пока показываем заглушку
            sb.append("🎁 *Награды*\n");
            sb.append("• Погашено: _см. детали в панели_\n\n");
        }
        
        // Достижения
        long achievementsAwarded = achievementRepository.countAwardedBetween(from, to);
        if (achievementsAwarded > 0) {
            sb.append("🏅 *Достижения*\n");
            sb.append("• Выдано: ").append(achievementsAwarded).append("\n\n");
        }
        
        sb.append("_Отчёт сгенерирован автоматически_");
        
        return sb.toString();
    }
    
    /**
     * Генерирует отчёт за сегодня
     */
    public String buildDailyReport() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        return buildReport(startOfDay, now, "Отчёт за сегодня");
    }
    
    /**
     * Генерирует отчёт за месяц
     */
    public String buildMonthlyReport() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfMonth = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime startOfLastMonth = startOfMonth.minusMonths(1);
        
        return buildReport(startOfLastMonth, startOfMonth, "Месячный отчёт");
    }
    
    /**
     * Форматирует сумму в рубли
     */
    private String formatCurrency(Double amount) {
        if (amount == null) return "0 ₽";
        return String.format("%.0f ₽", amount);
    }
    
    /**
     * Возвращает краткую статистику (для быстрого просмотра)
     */
    public String getQuickStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfWeek = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        long weekTransactions = transactionRepository.countBetween(startOfWeek, now);
        Double weekRevenue = transactionRepository.sumAmountBetween(startOfWeek, now);
        long weekNewCustomers = userRepository.countNewCustomersBetween(startOfWeek, now);
        
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Эта неделя:*\n");
        sb.append("• Транзакций: ").append(weekTransactions).append("\n");
        if (weekRevenue != null && weekRevenue > 0) {
            sb.append("• Выручка: ").append(formatCurrency(weekRevenue)).append("\n");
        }
        sb.append("• Новых клиентов: ").append(weekNewCustomers);
        
        return sb.toString();
    }
}



