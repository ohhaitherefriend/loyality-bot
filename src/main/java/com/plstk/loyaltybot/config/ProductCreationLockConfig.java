package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.service.importing.LocalProductCreationLock;
import com.plstk.loyaltybot.service.importing.PostgresAdvisoryProductCreationLock;
import com.plstk.loyaltybot.service.importing.ProductCreationLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * ADR-031 (Section 4): selects the real, cross-instance-safe {@link PostgresAdvisoryProductCreationLock}
 * whenever the active datasource actually IS PostgreSQL, falling back to the JVM-local {@link
 * LocalProductCreationLock} otherwise (H2 in dev/test - see docs/DECISIONS.md "dev/test = H2, prod
 * = PostgreSQL"). Detected from the live JDBC connection's own metadata rather than a separate
 * config flag - unlike {@code pg_trgm} (an opt-in extension that might not be installed even on a
 * real PostgreSQL database), correct concurrent-creation protection must never depend on an
 * operator remembering to flip a flag; it should simply always be correct for whichever database
 * is actually configured.
 */
@Configuration
@Slf4j
public class ProductCreationLockConfig {

    @Bean
    @Primary
    ProductCreationLock productCreationLock(
            DataSource dataSource,
            PostgresAdvisoryProductCreationLock postgresLock,
            LocalProductCreationLock localLock) {
        if (isPostgres(dataSource)) {
            log.info("ProductCreationLock: PostgreSQL detected - using cross-instance pg_advisory_xact_lock");
            return postgresLock;
        }
        log.info("ProductCreationLock: non-PostgreSQL datasource detected - using JVM-local lock "
                + "(correct for a single instance only, e.g. H2 dev/test)");
        return localLock;
    }

    private boolean isPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            log.warn("ProductCreationLock: could not inspect datasource metadata - defaulting to JVM-local lock", e);
            return false;
        }
    }
}
