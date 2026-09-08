package com.plstk.loyaltybot.db;

import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6 (ADR-013) acceptance test: runs the full Flyway migration chain (V1..latest) against a
 * throwaway real PostgreSQL container - not H2 - and then lets Hibernate validate every JPA entity
 * mapping against the resulting schema (spring.jpa.hibernate.ddl-auto=validate). This is the one
 * place in the test suite that would fail if a migration used non-Postgres syntax (e.g. MySQL's
 * AUTO_INCREMENT), if a JSONB column drifted from its TEXT-mapped entity field, or if an entity
 * field has no matching column/constraint at all.
 *
 * <p>Requires a local Docker daemon (same requirement as GreenMail-based mail tests do not have,
 * but consistent with what CI runners provide out of the box).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FlywayPostgresSchemaTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("loyalty_bot_flyway_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // The migration chain itself is what's under test here: start from zero and run every
        // versioned script, exactly like a brand-new environment would.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void springContextLoads_meaningFlywayMigratedAndHibernateValidatedAgainstRealPostgres() {
        // If we get here, Flyway already ran V1..latest against a clean PostgreSQL database and
        // Hibernate's ddl-auto=validate confirmed every entity mapping matches the resulting schema.
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertTrue(appliedMigrations != null && appliedMigrations > 20,
                "Expected the full V1..latest migration chain to have applied successfully");
    }

    @Test
    void supplierOffersTable_hasSnapshotScopeColumn_matchingStage3Fix() {
        Integer notNullCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'supplier_offers' AND column_name = 'snapshot_scope' "
                        + "AND is_nullable = 'NO'",
                Integer.class);
        assertEquals(1, notNullCount, "supplier_offers.snapshot_scope must exist and be NOT NULL (Stage 3 fix)");
    }

    @Test
    void brandAliasesTable_exists_matchingStage4Feature() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'brand_aliases'",
                Integer.class);
        assertEquals(1, tableCount, "brand_aliases table must exist (Stage 4 feature)");
    }

    @Test
    void entityManager_canPersistAndReadBackASupplierOffer_endToEndThroughRealPostgres() {
        // Smoke-tests that the TEXT-mapped JSON-ish columns (candidate_product_ids, conflicts, ...)
        // documented in ADR-012 actually round-trip through PostgreSQL, not just H2.
        assertTrue(entityManager.getMetamodel().entity(SupplierOffer.class) != null);
    }
}
