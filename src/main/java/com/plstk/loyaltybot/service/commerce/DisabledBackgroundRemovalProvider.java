package com.plstk.loyaltybot.service.commerce;

import org.springframework.stereotype.Component;

@Component
public class DisabledBackgroundRemovalProvider implements BackgroundRemovalProvider {

    @Override
    public BackgroundRemovalResult removeBackground(byte[] originalImage, String filename, String contentType) {
        return BackgroundRemovalResult.failure(providerName(), "Background removal disabled");
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String providerName() {
        return "disabled";
    }
}
