-- V24: Production-hardening pass Stage 1/2 - optimistic locking on supplier_sources (safe
-- PATCH/graduate) and human-approval audit on import_batches (NEEDS_ATTENTION -> APPROVED).
--
-- Schema management (Stage 6/ADR-013): this is the first migration in the chain that Flyway
-- actually EXECUTES against the real `prod` database - see application-prod.yml's
-- spring.flyway.baseline-version. Everything up to and including V23 is assumed already present
-- (built up over time via Hibernate `ddl-auto: update`, which prod used exclusively until this
-- release); V24 onward is genuinely new schema and must use IF NOT EXISTS / IF EXISTS guards so it
-- applies cleanly both on that pre-existing prod schema and on a brand-new database migrated from
-- V1 (e.g. the Testcontainers suite, FlywayPostgresSchemaTest).

-- A concurrent PATCH/graduate call that read a stale version must fail fast (409) instead of
-- silently overwriting another operator's change - see SupplierSource.version /
-- SupplierSourceAdminService.
ALTER TABLE supplier_sources
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Who/when confirmed a NEEDS_ATTENTION batch out of the exception queue - see
-- ImportBatchApprovalService. Null for batches that never needed manual attention.
ALTER TABLE import_batches
    ADD COLUMN IF NOT EXISTS approved_by_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS approved_by_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;
