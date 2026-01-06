package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.DiscountCode;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.DiscountCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiscountCodeService {
    
    private final DiscountCodeRepository discountCodeRepository;
    private static final String CHARACTERS = "0123456789"; // Только цифры для удобства
    private static final SecureRandom random = new SecureRandom();
    
    @Value("${loyalty.discount-code.length:8}")
    private int codeLength;
    
    @Transactional
    public List<DiscountCode> generateCodesForAllUsers(List<User> users, String description, 
                                                       Integer discountPercent, LocalDateTime expiresAt) {
        List<DiscountCode> codes = new ArrayList<>();
        
        for (User user : users) {
            String code = generateUniqueCode();
            
            DiscountCode discountCode = DiscountCode.builder()
                    .code(code)
                    .user(user)
                    .description(description)
                    .discountPercent(discountPercent)
                    .status(DiscountCode.CodeStatus.ACTIVE)
                    .expiresAt(expiresAt)
                    .build();
            
            codes.add(discountCodeRepository.save(discountCode));
        }
        
        log.info("Generated {} discount codes", codes.size());
        return codes;
    }
    
    private String generateUniqueCode() {
        String code;
        do {
            code = generateRandomCode();
        } while (discountCodeRepository.findByCode(code).isPresent());
        return code;
    }
    
    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
    
    public Optional<DiscountCode> findByCode(String code) {
        return discountCodeRepository.findByCode(code);
    }
    
    @Transactional
    public DiscountCode useCode(DiscountCode code) {
        if (code.getStatus() != DiscountCode.CodeStatus.ACTIVE) {
            throw new IllegalStateException("Код уже использован");
        }
        if (code.isExpired()) {
            code.setStatus(DiscountCode.CodeStatus.EXPIRED);
            discountCodeRepository.save(code);
            throw new IllegalStateException("Код истек");
        }
        
        code.setStatus(DiscountCode.CodeStatus.USED);
        code.setUsedAt(LocalDateTime.now());
        
        return discountCodeRepository.save(code);
    }
    
    public List<DiscountCode> getActiveCodesForUser(User user) {
        return discountCodeRepository.findByUser(user).stream()
                .filter(code -> code.getStatus() == DiscountCode.CodeStatus.ACTIVE)
                .filter(code -> !code.isExpired())
                .toList();
    }
}