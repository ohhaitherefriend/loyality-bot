package com.plstk.loyaltybot.service.commerce;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BackgroundRemovalResult {
    private boolean success;
    private byte[] transparentPng;
    private String provider;
    private String errorMessage;

    public static BackgroundRemovalResult failure(String provider, String errorMessage) {
        return BackgroundRemovalResult.builder()
                .success(false)
                .provider(provider)
                .errorMessage(errorMessage)
                .build();
    }

    public static BackgroundRemovalResult success(String provider, byte[] transparentPng) {
        return BackgroundRemovalResult.builder()
                .success(true)
                .provider(provider)
                .transparentPng(transparentPng)
                .build();
    }
}
