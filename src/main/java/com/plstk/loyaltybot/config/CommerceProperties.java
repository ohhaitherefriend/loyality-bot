package com.plstk.loyaltybot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "commerce")
public class CommerceProperties {

    private Import importConfig = new Import();
    private ImageSearch imageSearch = new ImageSearch();
    private ImageRanker imageRanker = new ImageRanker();
    private ImageNormalization imageNormalization = new ImageNormalization();
    private ImageStorage imageStorage = new ImageStorage();
    private MiniApp miniApp = new MiniApp();

    @Data
    public static class MiniApp {
        private boolean enabled = true;
        private String publicUrl = "http://localhost:3000";
        private boolean requireTelegramAuth = true;
        private int initDataMaxAgeSeconds = 86400;
    }

    @Data
    public static class Import {
        private int defaultMarkupPercent = 35;
    }

    @Data
    public static class ImageSearch {
        private boolean enabled = false;
        private String provider = "disabled";
        private int maxResultsPerQuery = 10;
        private int minConfidenceToDownload = 50;
        private int maxProductsPerBatch = 50;
        private int requestTimeoutMs = 10000;
        private int rateLimitMs = 500;
        private Brave brave = new Brave();
    }

    @Data
    public static class Brave {
        private String apiKey = "";
    }

    @Data
    public static class ImageRanker {
        private String provider = "rule-based";
        private DeepSeek deepseek = new DeepSeek();
    }

    @Data
    public static class DeepSeek {
        private String apiKey = "";
        private String model = "deepseek-chat";
        private String baseUrl = "https://api.deepseek.com";
        private int timeoutMs = 15000;
    }

    @Data
    public static class ImageNormalization {
        private int outputSize = 1024;
        private String background = "#FFFFFF";
        private BackgroundRemoval backgroundRemoval = new BackgroundRemoval();
    }

    @Data
    public static class BackgroundRemoval {
        private String provider = "disabled";
        private int timeoutMs = 30000;
        private Rembg rembg = new Rembg();
    }

    @Data
    public static class Rembg {
        private String url = "http://localhost:7000/remove";
        private String healthUrl = "http://localhost:7000/health";
    }

    @Data
    public static class ImageStorage {
        private String basePath = "./data/product-images";
        private String publicUrlPrefix = "/files/product-images";
    }

    public int getDefaultMarkupPercent() {
        return importConfig.getDefaultMarkupPercent();
    }

    public String getImageStorageBasePath() {
        return imageStorage.getBasePath();
    }

    public String getImagePublicUrlPrefix() {
        return imageStorage.getPublicUrlPrefix();
    }
}
