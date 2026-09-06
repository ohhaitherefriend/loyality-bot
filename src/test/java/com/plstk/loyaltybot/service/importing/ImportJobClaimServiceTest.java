package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportJobClaim;
import com.plstk.loyaltybot.repository.ImportJobClaimRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({
        ImportJobClaimService.class,
        ImportJobClaimInsertWriter.class,
        ActiveClaimRegistry.class,
        ImportJobClaimServiceTest.TestConfig.class})
class ImportJobClaimServiceTest {

    private static final String JOB_TYPE = "mailbox-poll";
    private static final String JOB_KEY = "mailbox-1";

    @Autowired
    private ImportJobClaimService importJobClaimService;
    @Autowired
    private ImportJobClaimRepository importJobClaimRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void tryClaim_firstCaller_succeeds() {
        Optional<ImportJobClaimService.ClaimHandle> handle =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));

        assertTrue(handle.isPresent());
        assertEquals(1, importJobClaimRepository.count());
    }

    @Test
    void tryClaim_whileLeaseActive_secondCallerIsRejected() {
        Optional<ImportJobClaimService.ClaimHandle> first =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));
        assertTrue(first.isPresent());

        Optional<ImportJobClaimService.ClaimHandle> second =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));

        assertTrue(second.isEmpty());
    }

    @Test
    void tryClaim_afterLeaseExpires_anotherOwnerCanTakeOver() {
        Optional<ImportJobClaimService.ClaimHandle> first =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));
        assertTrue(first.isPresent());
        String firstOwnerToken = first.get().ownerToken();

        // Simulate a crashed worker: force the lease into the past directly in the DB.
        ImportJobClaim claim = importJobClaimRepository.findByJobTypeAndJobKey(JOB_TYPE, JOB_KEY).orElseThrow();
        claim.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        importJobClaimRepository.save(claim);
        entityManager.flush();
        entityManager.clear();

        Optional<ImportJobClaimService.ClaimHandle> second =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));

        assertTrue(second.isPresent());
        assertNotEquals(firstOwnerToken, second.get().ownerToken());
        assertEquals(1, importJobClaimRepository.count());
    }

    @Test
    void release_allowsImmediateReclaimEvenBeforeLeaseExpiry() {
        Optional<ImportJobClaimService.ClaimHandle> first =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));
        assertTrue(first.isPresent());

        importJobClaimService.release(first.get());
        entityManager.flush();
        entityManager.clear();

        Optional<ImportJobClaimService.ClaimHandle> second =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));

        assertTrue(second.isPresent());
    }

    @Test
    void heartbeat_extendsLease_soAnotherOwnerCannotTakeOverYet() {
        Optional<ImportJobClaimService.ClaimHandle> first =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofSeconds(1));
        assertTrue(first.isPresent());

        boolean renewed = importJobClaimService.heartbeat(first.get(), Duration.ofMinutes(5));
        assertTrue(renewed);

        // Even though the original lease duration (1s) would have elapsed logically, the
        // heartbeat pushed lease_expires_at far into the future, so a fresh attempt must fail.
        Optional<ImportJobClaimService.ClaimHandle> second =
                importJobClaimService.tryClaim(JOB_TYPE, JOB_KEY, Duration.ofMinutes(5));
        assertFalse(second.isPresent());
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SupplierImportMetrics supplierImportMetrics(MeterRegistry meterRegistry) {
            return new SupplierImportMetrics(meterRegistry);
        }
    }
}
