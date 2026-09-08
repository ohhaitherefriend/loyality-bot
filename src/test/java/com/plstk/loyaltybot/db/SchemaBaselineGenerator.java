package com.plstk.loyaltybot.db;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One-off dev tool (Stage 6/ADR-013) for regenerating db/migration/V0__baseline_schema.sql from
 * the current JPA entity mappings via Hibernate's schema-generation script export, using a real
 * PostgreSQL dialect/container so the output is genuine PostgreSQL DDL (BIGSERIAL, etc.), not
 * H2's.
 *
 * <p>Deliberately named without a Test/Tests/TestCase suffix so Maven Surefire's default include
 * patterns skip it during `mvn test` - it doesn't assert anything, it just writes a file as a
 * side effect of building the EntityManagerFactory. Run it explicitly after changing any entity:
 * `mvn test -Dtest=SchemaBaselineGenerator`, then hand-review the diff of
 * /tmp/v0-baseline-generated.sql against V0 before copying anything in - V0 already intentionally
 * duplicates parts of V17-V26 (see V0's header comment for why that's safe), and a raw diff will
 * also pick up harmless Hibernate output-ordering churn.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SchemaBaselineGenerator {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("loyalty_bot_schema_gen")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action", () -> "create");
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target",
                () -> "/tmp/v0-baseline-generated.sql");
    }

    @Test
    void generate() {
        // Intentionally empty: the schema-generation script is written as a side effect of the
        // EntityManagerFactory being created for this test's Spring context.
    }
}
