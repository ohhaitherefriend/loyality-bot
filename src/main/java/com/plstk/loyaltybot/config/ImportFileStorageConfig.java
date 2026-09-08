package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.service.importing.LocalImportFileStorage;
import com.plstk.loyaltybot.service.importing.S3ImportFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Selects the {@code ImportFileStorage} backend by {@code supplier-import.storage.provider}
 * (Stage 10, docs/DECISIONS.md ADR-014). Default ({@code local}) keeps today's behavior exactly -
 * this bean selection is additive and never runs the S3 branch unless an operator opts in, so a
 * shop that never configures {@code supplier-import.storage.provider=s3} sees no change at all.
 */
@Configuration
@Slf4j
public class ImportFileStorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "supplier-import.storage", name = "provider", havingValue = "local", matchIfMissing = true)
    public LocalImportFileStorage localImportFileStorage(SupplierImportProperties properties) {
        log.info("Import file storage: local (basePath={})", properties.getStorage().getBasePath());
        return new LocalImportFileStorage(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "supplier-import.storage", name = "provider", havingValue = "s3")
    public S3ImportFileStorage s3ImportFileStorage(SupplierImportProperties properties) {
        SupplierImportProperties.S3 s3Props = properties.getStorage().getS3();
        if (s3Props.getBucket() == null || s3Props.getBucket().isBlank()) {
            throw new IllegalStateException(
                    "supplier-import.storage.provider=s3 requires supplier-import.storage.s3.bucket to be configured");
        }
        S3Client client = buildS3Client(s3Props);
        log.info("Import file storage: s3 (bucket={}, region={}, endpointOverride={})",
                s3Props.getBucket(), s3Props.getRegion(), s3Props.getEndpoint().isBlank() ? "none" : s3Props.getEndpoint());
        return new S3ImportFileStorage(client, s3Props.getBucket(), s3Props.getKeyPrefix());
    }

    private S3Client buildS3Client(SupplierImportProperties.S3 s3Props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3Props.getRegion()))
                .credentialsProvider(credentialsProvider(s3Props))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3Props.isPathStyleAccess())
                        .build());
        if (!s3Props.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3Props.getEndpoint()));
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider(SupplierImportProperties.S3 s3Props) {
        if (!s3Props.getAccessKeyId().isBlank() && !s3Props.getSecretAccessKey().isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(s3Props.getAccessKeyId(), s3Props.getSecretAccessKey()));
        }
        // Falls back to the default AWS chain (env vars, instance profile, ECS task role, etc) -
        // preferred over explicit keys whenever the deployment environment already provides one.
        return DefaultCredentialsProvider.create();
    }
}
