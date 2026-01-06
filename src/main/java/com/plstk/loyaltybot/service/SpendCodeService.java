package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.SpendCode;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.SpendCodeRepository;
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
public class SpendCodeService {
    
    private final SpendCodeRepository spendCodeRepository;
    private static final String CHARACTERS = "0123456789"; // Только цифры
    private static final SecureRandom random = new SecureRandom();
    
    @Value("${loyalty.spend-code.expiration-minutes:10}")
    private int expirationMinutes;
    
    @Value("${loyalty.spend-code.length:6}")
    private int codeLength;
    
    @Transactional
    public SpendCode generateCode(User user, int pointsToSpend) {
        String code = generateUniqueCode();
        
        SpendCode spendCode = SpendCode.builder()
                .code(code)
                .user(user)
                .pointsToSpend(pointsToSpend)
                .status(SpendCode.CodeStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                .build();
        
        SpendCode saved = spendCodeRepository.save(spendCode);
        log.info("Generated spend code {} for user {} ({} points)", 
            code, user.getChatId(), pointsToSpend);
        return saved;
    }
    
    private String generateUniqueCode() {
        String code;
        do {
            code = generateRandomCode();
        } while (spendCodeRepository.findByCode(code).isPresent());
        return code;
    }
    
    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
    
    public Optional<SpendCode> findByCode(String code) {
        return spendCodeRepository.findByCode(code);
    }
    
    @Transactional
    public SpendCode useCode(SpendCode code, User admin) {
        if (code.getStatus() != SpendCode.CodeStatus.ACTIVE) {
            throw new IllegalStateException("Код уже использован");
        }
        if (code.isExpired()) {
            code.setStatus(SpendCode.CodeStatus.EXPIRED);
            spendCodeRepository.save(code);
            throw new IllegalStateException("Код истек");
        }
        
        code.setStatus(SpendCode.CodeStatus.USED);
        code.setUsedAt(LocalDateTime.now());
        code.setUsedByAdmin(admin);
        
        SpendCode saved = spendCodeRepository.save(code);
        log.info("Spend code {} used by admin {}", code.getCode(), admin.getChatId());
        return saved;
    }
    
    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional
    public void expireOldCodes() {
        List<SpendCode> expiredCodes = spendCodeRepository.findByStatusAndExpiresAtBefore(
                SpendCode.CodeStatus.ACTIVE,
                LocalDateTime.now()
        );
        
        for (SpendCode code : expiredCodes) {
            code.setStatus(SpendCode.CodeStatus.EXPIRED);
            spendCodeRepository.save(code);
        }
        
        if (!expiredCodes.isEmpty()) {
            log.info("Expired {} old spend codes", expiredCodes.size());
        }
    }
}
