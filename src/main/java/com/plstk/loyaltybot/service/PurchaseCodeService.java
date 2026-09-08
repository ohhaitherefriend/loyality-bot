package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.PurchaseCode;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.PurchaseCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseCodeService {
    
    private final PurchaseCodeRepository purchaseCodeRepository;
    private static final String CHARACTERS = "0123456789"; // Только цифры для удобства ввода
    private static final SecureRandom random = new SecureRandom();
    
    @Value("${loyalty.purchase-code.expiration-minutes:10}")
    private int expirationMinutes;
    
    @Value("${loyalty.purchase-code.length:6}")
    private int codeLength;
    
    @Transactional
    public PurchaseCode generateCode(User user) {
        String code = generateUniqueCode();
        
        PurchaseCode purchaseCode = PurchaseCode.builder()
                .code(code)
                .user(user)
                .status(PurchaseCode.CodeStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                .build();
        
        PurchaseCode saved = purchaseCodeRepository.save(purchaseCode);
        log.info("Generated purchase code {} for user {}", code, user.getChatId());
        return saved;
    }
    
    private String generateUniqueCode() {
        String code;
        do {
            code = generateRandomCode();
        } while (purchaseCodeRepository.findByCode(code).isPresent());
        return code;
    }
    
    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
    
    /**
     * Looks up an active/used purchase code for admin redemption, scoped to the admin's shop
     * (Stage 8 security hardening) so an admin from one shop can never redeem another shop's
     * customer code even though {@code code} itself is a globally unique DB value.
     */
    public Optional<PurchaseCode> findByCode(String code, String shopId) {
        return purchaseCodeRepository.findByCodeAndUser_ShopId(code, shopId);
    }
    
    @Transactional
    public PurchaseCode useCode(PurchaseCode code, User admin) {
        if (code.getStatus() != PurchaseCode.CodeStatus.ACTIVE) {
            throw new IllegalStateException("Код уже использован");
        }
        if (code.isExpired()) {
            code.setStatus(PurchaseCode.CodeStatus.EXPIRED);
            purchaseCodeRepository.save(code);
            throw new IllegalStateException("Код истек");
        }
        
        code.setStatus(PurchaseCode.CodeStatus.USED);
        code.setUsedAt(LocalDateTime.now());
        code.setUsedByAdmin(admin);
        
        PurchaseCode saved = purchaseCodeRepository.save(code);
        log.info("Purchase code {} used by admin {}", code.getCode(), admin.getChatId());
        return saved;
    }
    
    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional
    public void expireOldCodes() {
        List<PurchaseCode> expiredCodes = purchaseCodeRepository.findByStatusAndExpiresAtBefore(
                PurchaseCode.CodeStatus.ACTIVE,
                LocalDateTime.now()
        );
        
        for (PurchaseCode code : expiredCodes) {
            code.setStatus(PurchaseCode.CodeStatus.EXPIRED);
            purchaseCodeRepository.save(code);
        }
        
        if (!expiredCodes.isEmpty()) {
            log.info("Expired {} old purchase codes", expiredCodes.size());
        }
    }
}