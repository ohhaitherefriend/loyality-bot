package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.BotInstance;
import com.plstk.loyaltybot.entity.MessengerPlatform;
import com.plstk.loyaltybot.service.BotInstanceService;
import com.plstk.loyaltybot.max.MaxContext;
import com.plstk.loyaltybot.max.MaxUpdateRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Контроллер для приёма webhook запросов от Max.
 *
 * Endpoint: POST /max/webhook/{botInstanceId}/{secret}
 */
@RestController
@RequestMapping("/max")
@RequiredArgsConstructor
@Slf4j
public class MaxWebhookController {

    private final BotInstanceService botInstanceService;
    private final MaxUpdateRouter updateRouter;

    @PostMapping("/webhook/{botInstanceId}/{secret}")
    public ResponseEntity<Void> handleWebhook(
            @PathVariable Long botInstanceId,
            @PathVariable String secret,
            @RequestBody Map<String, Object> update) {

        try {
            Optional<BotInstance> botOpt = botInstanceService.validateWebhook(botInstanceId, secret);

            if (botOpt.isEmpty()) {
                log.warn("Invalid Max webhook request: botInstanceId={}, secret mismatch", botInstanceId);
                return ResponseEntity.ok().build();
            }

            BotInstance botInstance = botOpt.get();

            if (botInstance.getPlatform() != MessengerPlatform.MAX) {
                log.warn("Bot {} is not a Max bot, ignoring update on Max endpoint", botInstanceId);
                return ResponseEntity.ok().build();
            }

            if (!botInstance.getIsActive() || botInstance.getStatus() != BotInstance.BotStatus.ACTIVE) {
                log.warn("Max bot {} is not active, ignoring update", botInstanceId);
                return ResponseEntity.ok().build();
            }

            MaxContext context = botInstanceService.createMaxContext(botInstance, update);

            if (context != null) {
                updateRouter.route(context);
                botInstanceService.recordWebhook(botInstance);
            }

        } catch (Exception e) {
            log.error("Error processing Max webhook for bot {}: {}", botInstanceId, e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/webhook/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Max webhook endpoint is ready");
    }
}
