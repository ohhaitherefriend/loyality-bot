package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.DiscountCode;
import com.plstk.loyaltybot.entity.Promotion;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {
    
    private final PromotionRepository promotionRepository;
    private final DiscountCodeService discountCodeService;
    private final UserService userService;
    
    /**
     * Создает новую промо-акцию и генерирует коды для всех зарегистрированных пользователей
     */
    @Transactional
    public Promotion createPromotion(Integer discountPercent, String description, 
                                     LocalDateTime expiresAt, User createdBy) {
        // Создаем промо-акцию
        Promotion promotion = Promotion.builder()
                .discountPercent(discountPercent)
                .description(description)
                .expiresAt(expiresAt)
                .createdBy(createdBy)
                .isActive(true)
                .build();
        
        Promotion savedPromotion = promotionRepository.save(promotion);
        log.info("Created promotion id={}, discount={}%, expires={}", 
            savedPromotion.getId(), discountPercent, expiresAt);
        
        // Генерируем коды для всех зарегистрированных пользователей
        List<User> users = userService.findAllRegisteredUsers();
        List<DiscountCode> codes = discountCodeService.generateCodesForAllUsers(
            users, description, discountPercent, expiresAt);
        
        log.info("Generated {} discount codes for promotion id={}", codes.size(), savedPromotion.getId());
        
        return savedPromotion;
    }
    
    /**
     * Генерирует промо-код для нового пользователя для всех активных акций
     */
    @Transactional
    public List<DiscountCode> generatePromotionCodesForNewUser(User user) {
        List<Promotion> activePromotions = getActivePromotions();
        
        if (activePromotions.isEmpty()) {
            return List.of();
        }
        
        List<DiscountCode> codes = new java.util.ArrayList<>();
        for (Promotion promotion : activePromotions) {
            List<DiscountCode> userCodes = discountCodeService.generateCodesForAllUsers(
                List.of(user), 
                promotion.getDescription(), 
                promotion.getDiscountPercent(), 
                promotion.getExpiresAt()
            );
            codes.addAll(userCodes);
        }
        
        log.info("Generated {} promotion codes for new user chatId={}", codes.size(), user.getChatId());
        return codes;
    }
    
    /**
     * Возвращает список всех активных акций
     */
    public List<Promotion> getActivePromotions() {
        return promotionRepository.findByIsActiveTrueAndExpiresAtAfter(LocalDateTime.now());
    }
    
    /**
     * Возвращает все акции (активные и неактивные)
     */
    public List<Promotion> getAllPromotions() {
        return promotionRepository.findAllByOrderByCreatedAtDesc();
    }
    
    /**
     * Деактивирует промо-акцию
     */
    @Transactional
    public Promotion deactivatePromotion(Long promotionId) {
        Promotion promotion = promotionRepository.findById(promotionId)
            .orElseThrow(() -> new IllegalArgumentException("Promotion not found"));
        
        promotion.setIsActive(false);
        Promotion saved = promotionRepository.save(promotion);
        
        log.info("Deactivated promotion id={}", promotionId);
        return saved;
    }
}


