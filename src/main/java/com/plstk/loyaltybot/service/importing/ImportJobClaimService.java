package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportJobClaim;
import com.plstk.loyaltybot.repository.ImportJobClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * DB-backed claim/lease abstraction for resumable background jobs (mailbox polling in
 * Prompt 02, batch processing jobs later). Exactly one replica holds an active lease per
 * {@code (jobType, jobKey)} at a time; after crash/timeout another replica can safely take
 * over once the lease expires. No vendor-specific advisory locks are used, so this works
 * identically on H2 (tests) and PostgreSQL (production).
 *
 * <p>Deliberately NOT {@code @Transactional} on {@link #tryClaim} itself: on PostgreSQL, once any
 * statement inside a transaction errors, the whole backend transaction is aborted and every later
 * statement fails with "current transaction is aborted" until rollback - so the original
 * implementation's "catch the unique-constraint race, then re-query in the same transaction" idiom
 * threw a second, unhandled exception in production despite passing every H2-backed test (H2 is far
 * more lenient here). Splitting the insert attempt into its own bean/transaction
 * ({@link ImportJobClaimInsertWriter}) means a lost race cleanly rolls back just that one
 * transaction, and the fallback lookup below always runs in a fresh transaction instead of a
 * poisoned one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportJobClaimService {

    private final ImportJobClaimRepository importJobClaimRepository;
    private final ImportJobClaimInsertWriter insertWriter;
    private final ActiveClaimRegistry activeClaimRegistry;
    private final SupplierImportMetrics metrics;

    public Optional<ClaimHandle> tryClaim(String jobType, String jobKey, Duration leaseDuration) {
        String ownerToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plus(leaseDuration);

        Optional<ImportJobClaim> existing = importJobClaimRepository.findByJobTypeAndJobKey(jobType, jobKey);
        if (existing.isEmpty()) {
            try {
                ImportJobClaim claim = insertWriter.insertNew(jobType, jobKey, ownerToken, now, leaseExpiresAt);
                metrics.jobClaim(jobType, "claimed");
                ClaimHandle handle = new ClaimHandle(claim.getId(), jobType, jobKey, ownerToken, leaseExpiresAt);
                activeClaimRegistry.register(handle, leaseDuration);
                return Optional.of(handle);
            } catch (DataIntegrityViolationException e) {
                // insertWriter.insertNew() ran in its own transaction, which has already rolled back
                // cleanly by the time this catch runs - this lookup starts a brand new transaction,
                // never reusing an aborted one (see class javadoc).
                existing = importJobClaimRepository.findByJobTypeAndJobKey(jobType, jobKey);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }

        ImportJobClaim current = existing.get();
        int updated = importJobClaimRepository.takeOverIfFree(current.getId(), ownerToken, now, leaseExpiresAt);
        if (updated == 1) {
            log.debug("Claimed job {}/{} (took over stale/released lease)", jobType, jobKey);
            metrics.jobClaim(jobType, "claimed");
            ClaimHandle handle = new ClaimHandle(current.getId(), jobType, jobKey, ownerToken, leaseExpiresAt);
            activeClaimRegistry.register(handle, leaseDuration);
            return Optional.of(handle);
        }
        log.debug("Job {}/{} is currently held by another owner", jobType, jobKey);
        metrics.jobClaim(jobType, "contended");
        return Optional.empty();
    }

    @Transactional
    public boolean heartbeat(ClaimHandle handle, Duration leaseDuration) {
        LocalDateTime now = LocalDateTime.now();
        int updated = importJobClaimRepository.renewLease(
                handle.id(), handle.ownerToken(), now, now.plus(leaseDuration));
        return updated == 1;
    }

    @Transactional
    public void release(ClaimHandle handle) {
        activeClaimRegistry.unregister(handle);
        importJobClaimRepository.release(handle.id(), handle.ownerToken(), LocalDateTime.now());
    }

    public record ClaimHandle(Long id, String jobType, String jobKey, String ownerToken, LocalDateTime leaseExpiresAt) {
    }
}
