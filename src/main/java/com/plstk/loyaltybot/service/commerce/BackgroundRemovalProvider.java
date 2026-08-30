package com.plstk.loyaltybot.service.commerce;

public interface BackgroundRemovalProvider {

    BackgroundRemovalResult removeBackground(byte[] originalImage, String filename, String contentType);

    boolean isConfigured();

    String providerName();
}
