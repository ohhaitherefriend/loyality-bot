package com.plstk.loyaltybot.max;

import com.plstk.loyaltybot.service.MaxBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Роутер для обработки Max Updates.
 * Асинхронная обработка для быстрого ответа Max серверу.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaxUpdateRouter {

    private final MaxBotService maxBotService;

    @Async("maxUpdateExecutor")
    public void route(MaxContext context) {
        if (context == null) {
            log.warn("Received null Max context, skipping");
            return;
        }

        try {
            log.debug("Routing Max update for shopId={}, chatId={}, type={}",
                    context.getShopId(), context.getChatId(), context.getUpdateType());

            maxBotService.processUpdate(context);

        } catch (Exception e) {
            log.error("Error routing Max update for shopId={}, chatId={}: {}",
                    context.getShopId(), context.getChatId(), e.getMessage(), e);

            try {
                maxBotService.sendErrorMessage(context, "Произошла ошибка. Попробуйте позже.");
            } catch (Exception ex) {
                log.error("Failed to send Max error message", ex);
            }
        }
    }
}
