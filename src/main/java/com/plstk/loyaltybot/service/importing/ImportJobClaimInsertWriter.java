package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportJobClaim;
import com.plstk.loyaltybot.repository.ImportJobClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The insert-a-brand-new-claim half of {@link ImportJobClaimService#tryClaim}, kept as a separate
 * Spring bean (own transaction, own proxy) so that a lost unique-constraint race here rolls back
 * cleanly and in isolation, instead of poisoning a shared transaction that the caller would then try
 * to keep issuing statements against (see {@link ImportJobClaimService} class javadoc).
 */
@Service
@RequiredArgsConstructor
class ImportJobClaimInsertWriter {

    private final ImportJobClaimRepository importJobClaimRepository;

    @Transactional
    public ImportJobClaim insertNew(
            String jobType, String jobKey, String ownerToken, LocalDateTime now, LocalDateTime leaseExpiresAt) {
        ImportJobClaim claim = ImportJobClaim.builder()
                .jobType(jobType)
                .jobKey(jobKey)
                .ownerToken(ownerToken)
                .claimedAt(now)
                .leaseExpiresAt(leaseExpiresAt)
                .heartbeatAt(now)
                .build();
        claim = importJobClaimRepository.save(claim);
        importJobClaimRepository.flush();
        return claim;
    }
}
