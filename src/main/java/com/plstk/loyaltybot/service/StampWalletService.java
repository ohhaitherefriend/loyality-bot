package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.RedeemCode;
import com.plstk.loyaltybot.entity.StampWallet;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.RedeemCodeRepository;
import com.plstk.loyaltybot.repository.StampWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Сервис для работы со штамп-картами.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StampWalletService {
    
    private final StampWalletRepository stampWalletRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final ShopSettingsService shopSettingsService;
    
    private static final String CODE_CHARACTERS = "0123456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom random = new SecureRandom();
    
    /**
     * Получает или создаёт кошелёк штампов для пользователя
     */
    @Transactional
    public StampWallet getOrCreateWallet(User user) {
        return stampWalletRepository.findByUser(user)
            .orElseGet(() -> {
                StampWallet wallet = StampWallet.builder()
                    .user(user)
                    .stampsCount(0)
                    .rewardsEarned(0)
                    .rewardsAvailable(0)
                    .rewardsRedeemed(0)
                    .totalStampsEarned(0)
                    .build();
                StampWallet saved = stampWalletRepository.save(wallet);
                log.info("Created stamp wallet for user chatId={}", user.getChatId());
                return saved;
            });
    }
    
    /**
     * Получает кошелёк штампов пользователя (если существует)
     */
    public Optional<StampWallet> getWallet(User user) {
        return stampWalletRepository.findByUser(user);
    }
    
    /**
     * Получает кошелёк штампов по chatId
     */
    public Optional<StampWallet> getWalletByChatId(Long chatId) {
        return stampWalletRepository.findByUserChatId(chatId);
    }
    
    /**
     * Результат добавления штампов
     */
    public record AddStampsResult(
        StampWallet wallet,
        int stampsAdded,
        boolean earnedReward,
        int newRewardsCount,
        int stampsUntilNextReward
    ) {}
    
    /**
     * Добавляет штампы пользователю
     * @return результат операции с информацией о заработанных наградах
     */
    @Transactional
    public AddStampsResult addStamps(User user, int stamps) {
        StampWallet wallet = getOrCreateWallet(user);
        int stampsRequired = shopSettingsService.getStampsRequiredForReward();
        
        int rewardsBefore = wallet.getRewardsAvailable() != null ? wallet.getRewardsAvailable() : 0;
        boolean earnedReward = wallet.addStamps(stamps, stampsRequired);
        int rewardsAfter = wallet.getRewardsAvailable() != null ? wallet.getRewardsAvailable() : 0;
        
        StampWallet saved = stampWalletRepository.save(wallet);
        
        log.info("Added {} stamps to user chatId={}, total={}, rewards={}", 
            stamps, user.getChatId(), saved.getStampsCount(), saved.getRewardsAvailable());
        
        return new AddStampsResult(
            saved,
            stamps,
            earnedReward,
            rewardsAfter - rewardsBefore,
            saved.getStampsUntilReward(stampsRequired)
        );
    }
    
    /**
     * Генерирует код для погашения награды
     */
    @Transactional
    public RedeemCode generateRedeemCode(User user) {
        StampWallet wallet = getOrCreateWallet(user);
        
        if (!wallet.hasAvailableRewards()) {
            throw new IllegalStateException("У вас нет доступных наград для погашения");
        }
        
        // Проверяем, нет ли уже активного кода
        if (redeemCodeRepository.existsByUserAndStatus(user, RedeemCode.RedeemCodeStatus.ACTIVE)) {
            throw new IllegalStateException("У вас уже есть активный код погашения. Дождитесь его использования или истечения.");
        }
        
        String code = generateUniqueCode();
        int ttlMinutes = shopSettingsService.getRedeemCodeTtlMinutes();
        
        RedeemCode redeemCode = RedeemCode.builder()
            .code(code)
            .user(user)
            .stampWallet(wallet)
            .rewardTitle(shopSettingsService.getRewardTitle())
            .rewardDescription(shopSettingsService.getRewardDescription())
            .status(RedeemCode.RedeemCodeStatus.ACTIVE)
            .expiresAt(LocalDateTime.now().plusMinutes(ttlMinutes))
            .build();
        
        RedeemCode saved = redeemCodeRepository.save(redeemCode);
        log.info("Generated redeem code {} for user chatId={}", code, user.getChatId());
        
        return saved;
    }
    
    /**
     * Находит код погашения
     */
    public Optional<RedeemCode> findRedeemCode(String code) {
        return redeemCodeRepository.findByCode(code.toUpperCase().trim());
    }
    
    /**
     * Результат погашения награды
     */
    public record RedeemResult(
        RedeemCode redeemCode,
        StampWallet wallet,
        String rewardTitle,
        User customer
    ) {}
    
    /**
     * Подтверждает погашение награды (вызывается админом)
     */
    @Transactional
    public RedeemResult confirmRedeem(String code, User admin) {
        RedeemCode redeemCode = redeemCodeRepository.findByCode(code.toUpperCase().trim())
            .orElseThrow(() -> new IllegalStateException("Код не найден"));
        
        if (redeemCode.getStatus() != RedeemCode.RedeemCodeStatus.ACTIVE) {
            throw new IllegalStateException("Код уже использован или отменён");
        }
        
        if (redeemCode.isExpired()) {
            redeemCode.setStatus(RedeemCode.RedeemCodeStatus.EXPIRED);
            redeemCodeRepository.save(redeemCode);
            throw new IllegalStateException("Код истёк");
        }
        
        StampWallet wallet = redeemCode.getStampWallet();
        if (!wallet.redeemReward()) {
            throw new IllegalStateException("Награда уже была погашена");
        }
        
        redeemCode.setStatus(RedeemCode.RedeemCodeStatus.USED);
        redeemCode.setUsedAt(LocalDateTime.now());
        redeemCode.setUsedByAdmin(admin);
        
        RedeemCode savedCode = redeemCodeRepository.save(redeemCode);
        StampWallet savedWallet = stampWalletRepository.save(wallet);
        
        log.info("Redeemed reward for user chatId={}, admin chatId={}", 
            wallet.getUser().getChatId(), admin.getChatId());
        
        return new RedeemResult(savedCode, savedWallet, redeemCode.getRewardTitle(), wallet.getUser());
    }
    
    /**
     * Отменяет код погашения
     */
    @Transactional
    public void cancelRedeemCode(RedeemCode redeemCode) {
        redeemCode.setStatus(RedeemCode.RedeemCodeStatus.CANCELLED);
        redeemCodeRepository.save(redeemCode);
        log.info("Cancelled redeem code {} for user chatId={}", 
            redeemCode.getCode(), redeemCode.getUser().getChatId());
    }
    
    /**
     * Получает активные коды погашения пользователя
     */
    public List<RedeemCode> getActiveRedeemCodes(User user) {
        return redeemCodeRepository.findByUserAndStatus(user, RedeemCode.RedeemCodeStatus.ACTIVE);
    }
    
    /**
     * Возвращает информацию о прогрессе штампов для отображения
     */
    public String getStampProgressInfo(User user) {
        Optional<StampWallet> walletOpt = getWallet(user);
        int stampsRequired = shopSettingsService.getStampsRequiredForReward();
        String rewardTitle = shopSettingsService.getRewardTitle();
        
        if (walletOpt.isEmpty()) {
            return String.format("☕ Штампы: 0/%d\n🎁 До награды \"%s\": %d", 
                stampsRequired, rewardTitle, stampsRequired);
        }
        
        StampWallet wallet = walletOpt.get();
        int stamps = wallet.getStampsCount() != null ? wallet.getStampsCount() : 0;
        int rewards = wallet.getRewardsAvailable() != null ? wallet.getRewardsAvailable() : 0;
        int untilReward = stampsRequired - stamps;
        
        StringBuilder sb = new StringBuilder();
        
        // Визуализация штампов
        sb.append("☕ Штампы: ").append(stamps).append("/").append(stampsRequired).append("\n");
        sb.append(generateStampVisual(stamps, stampsRequired)).append("\n\n");
        
        if (rewards > 0) {
            sb.append("🎉 *Доступно наград: ").append(rewards).append("*\n");
            sb.append("🎁 \"").append(rewardTitle).append("\"\n");
            sb.append("➡️ Нажмите \"Получить награду\" для погашения\n\n");
        }
        
        if (untilReward > 0) {
            sb.append("📊 До следующей награды: ").append(untilReward).append(" ");
            sb.append(getStampWord(untilReward));
        }
        
        return sb.toString();
    }
    
    /**
     * Генерирует визуальное представление штампов
     */
    public String generateStampVisual(int current, int required) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < required; i++) {
            if (i < current) {
                sb.append("☕");
            } else {
                sb.append("○");
            }
            if ((i + 1) % 5 == 0 && i < required - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }
    
    /**
     * Склонение слова "штамп"
     */
    private String getStampWord(int count) {
        int abs = Math.abs(count) % 100;
        int lastDigit = abs % 10;
        
        if (abs >= 11 && abs <= 19) {
            return "штампов";
        }
        
        return switch (lastDigit) {
            case 1 -> "штамп";
            case 2, 3, 4 -> "штампа";
            default -> "штампов";
        };
    }
    
    private String generateUniqueCode() {
        String code;
        do {
            code = generateRandomCode();
        } while (redeemCodeRepository.findByCode(code).isPresent());
        return code;
    }
    
    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARACTERS.charAt(random.nextInt(CODE_CHARACTERS.length())));
        }
        return sb.toString();
    }
    
    /**
     * Очистка истёкших кодов погашения
     */
    @Scheduled(fixedRate = 60000) // Каждую минуту
    @Transactional
    public void expireOldRedeemCodes() {
        List<RedeemCode> expiredCodes = redeemCodeRepository.findByStatusAndExpiresAtBefore(
            RedeemCode.RedeemCodeStatus.ACTIVE,
            LocalDateTime.now()
        );
        
        for (RedeemCode code : expiredCodes) {
            code.setStatus(RedeemCode.RedeemCodeStatus.EXPIRED);
            redeemCodeRepository.save(code);
        }
        
        if (!expiredCodes.isEmpty()) {
            log.info("Expired {} old redeem codes", expiredCodes.size());
        }
    }
    
    /**
     * Находит пользователей, которым осталось 1 штамп до награды
     */
    public List<StampWallet> findUsersOneStampAway() {
        int stampsRequired = shopSettingsService.getStampsRequiredForReward();
        return stampWalletRepository.findByStampsUntilReward(stampsRequired, 1);
    }
}



