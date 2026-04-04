package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.BotInstance;
import com.plstk.loyaltybot.entity.MessengerPlatform;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.ShopMember;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.BotInstanceService;
import com.plstk.loyaltybot.service.ShopSettingsService;
import com.plstk.loyaltybot.service.SubscriptionService;
import com.plstk.loyaltybot.telegram.TelegramApiClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST API для Web-админки.
 * Позволяет подключать боты, управлять настройками магазинов,
 * получать QR-коды и отчёты.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AdminApiController {
    
    private final BotInstanceService botInstanceService;
    private final ShopSettingsService shopSettingsService;
    private final ShopMemberRepository shopMemberRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionService subscriptionService;
    
    // ========== Bot Connection ==========
    
    /**
     * Подключает новый бот к платформе.
     * 
     * POST /api/bots/connect
     */
    @PostMapping("/bots/connect")
    public ResponseEntity<ConnectBotResponse> connectBot(
            @Valid @RequestBody ConnectBotRequest request,
            @AuthenticationPrincipal AdminUser user) {
        log.info("Connect bot request: businessType={}, businessName={}, userId={}", 
            request.businessType(), request.businessName(), user != null ? user.getId() : "anonymous");
        
        BotInstance.BusinessType businessType = parseBusinessType(request.businessType());
        MessengerPlatform platform = parsePlatform(request.platform());
        
        BotInstanceService.ConnectResult result;
        if (platform == MessengerPlatform.MAX) {
            result = botInstanceService.connectMaxBot(
                request.botToken(), businessType, request.businessName(), request.ownerEmail());
        } else {
            result = botInstanceService.connectBot(
                request.botToken(), businessType, request.businessName(), request.ownerEmail());
        }
        
        if (!result.success()) {
            return ResponseEntity.badRequest()
                .body(ConnectBotResponse.error(result.error()));
        }
        
        // Создаём Shop и ShopMember для текущего пользователя
        if (user != null) {
            String shopId = result.shopId();
            
            // Создаём Shop если его нет
            if (!shopRepository.existsByShopId(shopId)) {
                Shop shop = Shop.builder()
                    .shopId(shopId)
                    .name(request.businessName() != null ? request.businessName() : "Мой магазин")
                    .timezone("Europe/Moscow")
                    .ownerId(user.getId())
                    .build();
                shopRepository.save(shop);
                log.info("Created shop for bot: shopId={}, userId={}", shopId, user.getId());
            }
            
            // Создаём ShopMember если его нет
            if (!shopMemberRepository.existsByUserIdAndShopId(user.getId(), shopId)) {
                ShopMember member = ShopMember.builder()
                    .userId(user.getId())
                    .shopId(shopId)
                    .role(ShopMember.MemberRole.OWNER)
                    .build();
                shopMemberRepository.save(member);
                log.info("Created shop member: userId={}, shopId={}", user.getId(), shopId);
            }
            
            // Создаём trial подписку
            subscriptionService.createTrialSubscription(shopId);
        }
        
        return ResponseEntity.ok(ConnectBotResponse.success(
            result.shopId(),
            result.botUsername(),
            result.buyDeepLink(),
            result.adminDeepLink(),
            result.botInstance().getId()
        ));
    }
    
    /**
     * Отключает бот от платформы.
     * 
     * DELETE /api/bots/{id}
     */
    @DeleteMapping("/bots/{id}")
    public ResponseEntity<Void> disconnectBot(@PathVariable Long id,
                                              @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hasAccessToBot(user, id)) {
            return ResponseEntity.status(403).build();
        }
        boolean success = botInstanceService.disconnectBot(id);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
    
    /**
     * Получает информацию о боте.
     * 
     * GET /api/bots/{id}
     */
    @GetMapping("/bots/{id}")
    public ResponseEntity<BotInfoResponse> getBotInfo(@PathVariable Long id,
                                                      @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hasAccessToBot(user, id)) {
            return ResponseEntity.status(403).build();
        }
        Optional<BotInstance> botOpt = botInstanceService.findById(id);
        
        if (botOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        BotInstance bot = botOpt.get();
        TelegramApiClient.WebhookInfo webhookInfo = botInstanceService.getWebhookInfo(id);
        
        return ResponseEntity.ok(new BotInfoResponse(
            bot.getId(),
            bot.getShopId(),
            bot.getBotUsername(),
            bot.getBusinessName(),
            bot.getStatus().name(),
            bot.getIsActive(),
            bot.getWebhookUrl(),
            bot.getUpdatesProcessed(),
            bot.getLastWebhookAt(),
            bot.getLastError(),
            webhookInfo != null ? webhookInfo.pendingUpdateCount() : 0,
            bot.generateBuyDeepLink(null),
            bot.generateAdminDeepLink()
        ));
    }
    
    /**
     * Получает список всех активных ботов.
     * 
     * GET /api/bots
     */
    @GetMapping("/bots")
    public ResponseEntity<List<BotListItem>> listBots(@AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        
        // Ищем боты по shop_members
        List<String> shopIds = shopMemberRepository.findByUserId(user.getId()).stream()
            .map(ShopMember::getShopId)
            .toList();
        
        List<BotInstance> bots = new java.util.ArrayList<>(botInstanceService.findActiveByShopIds(shopIds));
        
        // Также ищем боты по ownerEmail (для обратной совместимости со старыми ботами)
        List<BotInstance> botsByEmail = botInstanceService.findActiveByOwnerEmail(user.getEmail());
        for (BotInstance bot : botsByEmail) {
            if (bots.stream().noneMatch(b -> b.getId().equals(bot.getId()))) {
                bots.add(bot);
            }
        }
        
        List<BotListItem> items = bots.stream()
            .map(bot -> new BotListItem(
                bot.getId(),
                bot.getShopId(),
                bot.getBotUsername(),
                bot.getBusinessName(),
                bot.getStatus().name(),
                bot.getUpdatesProcessed(),
                bot.getPlatform() != null ? bot.getPlatform().name() : "TELEGRAM"
            ))
            .toList();
        
        return ResponseEntity.ok(items);
    }
    
    /**
     * Обновляет webhook для бота.
     * 
     * POST /api/bots/{id}/webhook
     */
    @PostMapping("/bots/{id}/webhook")
    public ResponseEntity<Void> updateWebhook(@PathVariable Long id,
                                              @Valid @RequestBody UpdateWebhookRequest request,
                                              @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hasAccessToBot(user, id)) {
            return ResponseEntity.status(403).build();
        }
        boolean success = botInstanceService.updateWebhook(id, request.baseUrl());
        return success ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }
    
    /**
     * Обновляет webhook для ВСЕХ активных ботов.
     * Используется при смене ngrok URL.
     * 
     * POST /api/admin/webhooks/update-all
     */
    @PostMapping("/admin/webhooks/update-all")
    public ResponseEntity<UpdateAllWebhooksResponse> updateAllWebhooks(
            @Valid @RequestBody UpdateWebhookRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        log.info("Updating webhooks for all bots to baseUrl={}", request.baseUrl());
        
        List<BotInstance> bots = botInstanceService.findAllActive();
        int success = 0;
        int failed = 0;
        
        for (BotInstance bot : bots) {
            try {
                if (botInstanceService.updateWebhook(bot.getId(), request.baseUrl())) {
                    success++;
                    log.info("Webhook updated for bot {}: {}", bot.getBotUsername(), request.baseUrl());
                } else {
                    failed++;
                    log.warn("Failed to update webhook for bot {}", bot.getBotUsername());
                }
            } catch (Exception e) {
                failed++;
                log.error("Error updating webhook for bot {}", bot.getBotUsername(), e);
            }
        }
        
        return ResponseEntity.ok(new UpdateAllWebhooksResponse(
            failed == 0,
            success,
            failed,
            String.format("Обновлено %d из %d ботов", success, success + failed)
        ));
    }
    
    // ========== Shop Settings ==========
    
    /**
     * Получает настройки магазина.
     * 
     * GET /api/shops/{shopId}/settings
     */
    @GetMapping("/shops/{shopId}/settings")
    public ResponseEntity<ShopSettings> getShopSettings(@PathVariable String shopId,
                                                        @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        ShopSettings settings = shopSettingsService.getSettings(shopId);
        
        if (settings == null || settings.getShopId() == null || !settings.getShopId().equals(shopId)) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(settings);
    }
    
    /**
     * Обновляет настройки магазина.
     * 
     * PUT /api/shops/{shopId}/settings
     */
    @PutMapping("/shops/{shopId}/settings")
    public ResponseEntity<ShopSettings> updateShopSettings(
            @PathVariable String shopId,
            @RequestBody UpdateSettingsRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        
        try {
            ShopSettings current = shopSettingsService.getSettings(shopId);
            
            // Обновляем только переданные поля
            if (request.shopName() != null) current.setShopName(request.shopName());
            if (request.fastCheckoutEnabled() != null) current.setFastCheckoutEnabled(request.fastCheckoutEnabled());
            if (request.stampsEnabled() != null) current.setStampsEnabled(request.stampsEnabled());
            if (request.discountTiersEnabled() != null) current.setDiscountTiersEnabled(request.discountTiersEnabled());
            if (request.stampsRequiredForReward() != null) current.setStampsRequiredForReward(request.stampsRequiredForReward());
            if (request.rewardTitle() != null) current.setRewardTitle(request.rewardTitle());
            if (request.rewardDescription() != null) current.setRewardDescription(request.rewardDescription());
            if (request.telegramChannelUrl() != null) current.setTelegramChannelUrl(request.telegramChannelUrl());
            if (request.defaultLocationId() != null) current.setDefaultLocationId(request.defaultLocationId());
            
            // Накопительные скидки
            if (request.discountTier1Amount() != null) current.setDiscountTier1Amount(request.discountTier1Amount());
            if (request.discountTier1Percent() != null) current.setDiscountTier1Percent(request.discountTier1Percent());
            if (request.discountTier2Amount() != null) current.setDiscountTier2Amount(request.discountTier2Amount());
            if (request.discountTier2Percent() != null) current.setDiscountTier2Percent(request.discountTier2Percent());
            if (request.discountTier3Amount() != null) current.setDiscountTier3Amount(request.discountTier3Amount());
            if (request.discountTier3Percent() != null) current.setDiscountTier3Percent(request.discountTier3Percent());
            if (request.discountValidityDays() != null) current.setDiscountValidityDays(request.discountValidityDays());
            
            // Постоянная скидка
            if (request.permanentDiscountEnabled() != null) current.setPermanentDiscountEnabled(request.permanentDiscountEnabled());
            if (request.permanentDiscountTiers() != null) current.setPermanentDiscountTiers(request.permanentDiscountTiers());
            
            // Кастомные сообщения
            if (request.welcomeMessage() != null) current.setWelcomeMessage(request.welcomeMessage());
            if (request.purchaseCodeMessage() != null) current.setPurchaseCodeMessage(request.purchaseCodeMessage());
            if (request.stampEarnedMessage() != null) current.setStampEarnedMessage(request.stampEarnedMessage());
            if (request.rewardEarnedMessage() != null) current.setRewardEarnedMessage(request.rewardEarnedMessage());
            
            ShopSettings updated = shopSettingsService.updateSettings(current);
            return ResponseEntity.ok(updated);
            
        } catch (Exception e) {
            log.error("Error updating settings for shopId={}", shopId, e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    // ========== Deep Links & QR ==========
    
    /**
     * Генерирует deep link для покупки.
     * 
     * GET /api/shops/{shopId}/deeplink
     */
    @GetMapping("/shops/{shopId}/deeplink")
    public ResponseEntity<DeepLinkResponse> getDeepLink(
            @PathVariable String shopId,
            @RequestParam(required = false) String locationId,
            @AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        
        Optional<BotInstance> botOpt = botInstanceService.findByShopId(shopId);
        
        if (botOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        BotInstance bot = botOpt.get();
        String buyLink = bot.generateBuyDeepLink(locationId);
        String adminLink = bot.generateAdminDeepLink();
        
        return ResponseEntity.ok(new DeepLinkResponse(buyLink, adminLink, shopId, locationId));
    }
    
    // ========== Statistics ==========
    
    /**
     * Общая статистика платформы.
     * 
     * GET /api/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<PlatformStats> getPlatformStats(@AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        long activeBots = botInstanceService.countActiveBots();
        // TODO: добавить больше статистики
        
        return ResponseEntity.ok(new PlatformStats(activeBots, 0L, 0L, 0L));
    }
    
    // ========== Helper Methods ==========
    
    private boolean hasAccessToShop(AdminUser user, String shopId) {
        if (shopRepository.findByShopId(shopId)
                .map(s -> s.getOwnerId().equals(user.getId()))
                .orElse(false)) {
            return true;
        }
        return shopMemberRepository.existsByUserIdAndShopId(user.getId(), shopId);
    }
    
    private boolean hasAccessToBot(AdminUser user, Long botId) {
        Optional<BotInstance> botOpt = botInstanceService.findById(botId);
        if (botOpt.isEmpty()) {
            return false;
        }
        return hasAccessToShop(user, botOpt.get().getShopId());
    }
    
    private BotInstance.BusinessType parseBusinessType(String type) {
        if (type == null) {
            return BotInstance.BusinessType.COFFEE;
        }
        
        return switch (type.toUpperCase()) {
            case "RETAIL" -> BotInstance.BusinessType.RETAIL;
            case "SERVICE" -> BotInstance.BusinessType.SERVICE;
            case "HYBRID" -> BotInstance.BusinessType.HYBRID;
            default -> BotInstance.BusinessType.COFFEE;
        };
    }
    
    private MessengerPlatform parsePlatform(String platform) {
        if (platform == null) {
            return MessengerPlatform.TELEGRAM;
        }
        return switch (platform.toUpperCase()) {
            case "MAX" -> MessengerPlatform.MAX;
            default -> MessengerPlatform.TELEGRAM;
        };
    }
    
    // ========== Request/Response DTOs ==========
    
    public record ConnectBotRequest(
        @NotBlank(message = "Bot token обязателен")
        String botToken,
        String businessType,
        @Size(max = 255, message = "Название бизнеса не может быть длиннее 255 символов")
        String businessName,
        String ownerEmail,
        String platform
    ) {}
    
    public record ConnectBotResponse(
        boolean success,
        String shopId,
        String botUsername,
        String buyDeepLink,
        String adminDeepLink,
        Long botInstanceId,
        String error
    ) {
        public static ConnectBotResponse success(String shopId, String botUsername, 
                                                 String buyDeepLink, String adminDeepLink, Long botInstanceId) {
            return new ConnectBotResponse(true, shopId, botUsername, buyDeepLink, adminDeepLink, botInstanceId, null);
        }
        
        public static ConnectBotResponse error(String error) {
            return new ConnectBotResponse(false, null, null, null, null, null, error);
        }
    }
    
    public record BotInfoResponse(
        Long id,
        String shopId,
        String botUsername,
        String businessName,
        String status,
        boolean isActive,
        String webhookUrl,
        Long updatesProcessed,
        java.time.LocalDateTime lastWebhookAt,
        String lastError,
        int pendingUpdates,
        String buyDeepLink,
        String adminDeepLink
    ) {}
    
    public record BotListItem(
        Long id,
        String shopId,
        String botUsername,
        String businessName,
        String status,
        Long updatesProcessed,
        String platform
    ) {}
    
    public record UpdateWebhookRequest(
        @NotBlank(message = "baseUrl обязателен")
        String baseUrl
    ) {}
    
    public record UpdateAllWebhooksResponse(
        boolean success,
        int updatedCount,
        int failedCount,
        String message
    ) {}
    
    public record UpdateSettingsRequest(
        String shopName,
        Boolean fastCheckoutEnabled,
        Boolean stampsEnabled,
        Boolean discountTiersEnabled,
        Integer stampsRequiredForReward,
        String rewardTitle,
        String rewardDescription,
        String telegramChannelUrl,
        String defaultLocationId,
        Double discountTier1Amount,
        Integer discountTier1Percent,
        Double discountTier2Amount,
        Integer discountTier2Percent,
        Double discountTier3Amount,
        Integer discountTier3Percent,
        Integer discountValidityDays,
        // Постоянная скидка
        Boolean permanentDiscountEnabled,
        String permanentDiscountTiers,
        // Кастомные сообщения
        String welcomeMessage,
        String purchaseCodeMessage,
        String stampEarnedMessage,
        String rewardEarnedMessage
    ) {}
    
    public record DeepLinkResponse(
        String buyDeepLink,
        String adminDeepLink,
        String shopId,
        String locationId
    ) {}
    
    public record PlatformStats(
        long activeBots,
        long totalUsers,
        long totalTransactions,
        long totalRevenue
    ) {}
}

