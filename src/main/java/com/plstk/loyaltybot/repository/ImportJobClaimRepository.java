package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.ImportJobClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ImportJobClaimRepository extends JpaRepository<ImportJobClaim, Long> {

    Optional<ImportJobClaim> findByJobTypeAndJobKey(String jobType, String jobKey);

    /**
     * Atomically takes over a claim row only if it is free: either explicitly released or its
     * lease has expired. Returns the number of updated rows (0 = lost the race / still held by
     * another owner, 1 = claimed successfully). Portable SQL, no vendor-specific UPSERT.
     */
    @Modifying
    @Query("""
            UPDATE ImportJobClaim c
            SET c.ownerToken = :newOwnerToken,
                c.claimedAt = :now,
                c.leaseExpiresAt = :leaseExpiresAt,
                c.heartbeatAt = :now,
                c.releasedAt = null
            WHERE c.id = :id
              AND (c.releasedAt IS NOT NULL OR c.leaseExpiresAt < :now)
            """)
    int takeOverIfFree(
            @Param("id") Long id,
            @Param("newOwnerToken") String newOwnerToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Modifying
    @Query("""
            UPDATE ImportJobClaim c
            SET c.leaseExpiresAt = :leaseExpiresAt, c.heartbeatAt = :now
            WHERE c.id = :id AND c.ownerToken = :ownerToken AND c.releasedAt IS NULL
            """)
    int renewLease(
            @Param("id") Long id,
            @Param("ownerToken") String ownerToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Modifying
    @Query("""
            UPDATE ImportJobClaim c
            SET c.releasedAt = :now
            WHERE c.id = :id AND c.ownerToken = :ownerToken
            """)
    int release(@Param("id") Long id, @Param("ownerToken") String ownerToken, @Param("now") LocalDateTime now);
}
