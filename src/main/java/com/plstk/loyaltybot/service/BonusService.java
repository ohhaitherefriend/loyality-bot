package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BonusService {
    
    private final UserRepository userRepository;
    private final ShopSettingsService shopSettingsService;
    
    /**
     * Начисляет бонусные баллы за покупку.
     * @return количество начисленных баллов
     */
    @Transactional
    public double accrueBonus(User user, double purchaseAmount) {
        String shopId = user.getShopId();
        if (shopId == null) return 0;
        
        ShopSettings settings = shopSettingsService.getSettings(shopId);
        if (!Boolean.TRUE.equals(settings.getBonusPointsEnabled())) return 0;
        
        int cashbackPercent = settings.getBonusCashbackPercent() != null ? settings.getBonusCashbackPercent() : 5;
        double bonus = Math.floor(purchaseAmount * cashbackPercent / 100.0);
        
        if (bonus <= 0) return 0;
        
        double currentBalance = user.getBonusBalance() != null ? user.getBonusBalance() : 0.0;
        user.setBonusBalance(currentBalance + bonus);
        userRepository.save(user);
        
        log.info("Accrued {} bonus points for user {} (purchase={}, cashback={}%)", 
            bonus, user.getChatId(), purchaseAmount, cashbackPercent);
        
        return bonus;
    }
    
    /**
     * Списывает бонусные баллы.
     * @return фактически списанная сумма
     */
    @Transactional
    public double spendBonus(User user, double purchaseAmount, double requestedSpend) {
        String shopId = user.getShopId();
        if (shopId == null) return 0;
        
        ShopSettings settings = shopSettingsService.getSettings(shopId);
        if (!Boolean.TRUE.equals(settings.getBonusPointsEnabled())) return 0;
        
        double balance = user.getBonusBalance() != null ? user.getBonusBalance() : 0.0;
        if (balance <= 0) return 0;
        
        int maxSpendPercent = settings.getBonusMaxSpendPercent() != null ? settings.getBonusMaxSpendPercent() : 100;
        double maxSpend = purchaseAmount * maxSpendPercent / 100.0;
        
        double actualSpend = Math.min(requestedSpend, Math.min(balance, maxSpend));
        actualSpend = Math.floor(actualSpend);
        
        if (actualSpend <= 0) return 0;
        
        user.setBonusBalance(balance - actualSpend);
        userRepository.save(user);
        
        log.info("Spent {} bonus points for user {} (balance {} -> {})", 
            actualSpend, user.getChatId(), balance, user.getBonusBalance());
        
        return actualSpend;
    }
    
    public double getBalance(User user) {
        return user.getBonusBalance() != null ? user.getBonusBalance() : 0.0;
    }
    
    public boolean isEnabled(String shopId) {
        if (shopId == null) return false;
        return Boolean.TRUE.equals(shopSettingsService.getSettings(shopId).getBonusPointsEnabled());
    }
    
    public String getBalanceInfo(User user) {
        double balance = getBalance(user);
        if (balance <= 0) {
            return "💳 Бонусный баланс: *0* баллов";
        }
        return String.format("💳 Бонусный баланс: *%.0f* баллов (= %.0f₽)", balance, balance);
    }
}
