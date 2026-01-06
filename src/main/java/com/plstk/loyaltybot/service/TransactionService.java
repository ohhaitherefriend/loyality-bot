package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.PurchaseCode;
import com.plstk.loyaltybot.entity.Transaction;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {
    
    private final TransactionRepository transactionRepository;
    
    @Transactional
    public Transaction createTransaction(User user, int points, Transaction.TransactionType type, 
                                        String description, PurchaseCode purchaseCode, User admin) {
        return createTransaction(user, points, type, description, null, purchaseCode, admin);
    }
    
    @Transactional
    public Transaction createTransaction(User user, int points, Transaction.TransactionType type, 
                                        String description, Double amount, PurchaseCode purchaseCode, User admin) {
        Transaction transaction = Transaction.builder()
                .user(user)
                .points(points)
                .type(type)
                .description(description)
                .amount(amount)
                .purchaseCode(purchaseCode)
                .admin(admin)
                .build();
        
        Transaction saved = transactionRepository.save(transaction);
        log.info("Created transaction: user={}, points={}, type={}, amount={}", 
                user.getChatId(), points, type, amount);
        return saved;
    }
    
    public List<Transaction> getUserTransactionHistory(User user) {
        return transactionRepository.findByUserOrderByCreatedAtDesc(user);
    }
    
    public List<Transaction> getUserRecentTransactions(User user, int limit) {
        return transactionRepository.findTop10ByUserOrderByCreatedAtDesc(user);
    }
    
    /**
     * Возвращает сумму покупок пользователя с указанной даты
     */
    public double getAccumulatedAmountSince(User user, java.time.LocalDateTime since) {
        return transactionRepository.sumAmountByUserAndCreatedAtAfter(user, since);
    }
    
    /**
     * Возвращает общую сумму покупок за текущий месяц
     */
    public double getTotalAmountForCurrentMonth() {
        java.time.LocalDateTime startOfMonth = java.time.LocalDateTime.now()
            .withDayOfMonth(1)
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .withNano(0);
        java.time.LocalDateTime endOfMonth = startOfMonth.plusMonths(1);
        
        return transactionRepository.sumAmountBetween(startOfMonth, endOfMonth);
    }
}