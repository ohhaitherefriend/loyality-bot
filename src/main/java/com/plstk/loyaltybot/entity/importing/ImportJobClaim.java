package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DB-backed claim/lease для resumable background jobs (mailbox polling, batch processing).
 * Ровно одна replica удерживает активный lease на {@code (jobType, jobKey)} в любой момент;
 * после истечения {@link #leaseExpiresAt} или явного {@link #releasedAt} другая replica может
 * безопасно перехватить job, в том числе после crash предыдущего владельца.
 */
@Entity
@Table(name = "import_job_claims", uniqueConstraints = {
    @UniqueConstraint(name = "uk_import_job_claims_type_key", columnNames = {"jobType", "jobKey"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportJobClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String jobType;

    @Column(nullable = false, length = 255)
    private String jobKey;

    @Column(nullable = false, length = 64)
    private String ownerToken;

    @Column(nullable = false)
    private LocalDateTime claimedAt;

    @Column(nullable = false)
    private LocalDateTime leaseExpiresAt;

    private LocalDateTime heartbeatAt;

    private LocalDateTime releasedAt;

    /** Optimistic lock: prevents lost-update races between concurrent claim attempts. */
    @Version
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
