-- V22: Prompt 07 operations UI - optimistic locking on import rows and human reviewer audit on
-- match decisions.
--
-- Same schema-management note as V17-V21: Flyway stays disabled in every profile, so this file is
-- the intended PostgreSQL-compatible target schema, not something actually applied. Dev/test/prod
-- all still rely on Hibernate `ddl-auto: update` against the JPA entities for the real schema.

-- Optimistic lock for human row-decision actions (MATCH/NO_MATCH/CREATE_PRODUCT/IGNORE): a stale
-- version fails the write instead of silently overwriting a concurrent decision.
ALTER TABLE import_rows
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Operator identity for DecidedBy=HUMAN match decisions (audit only, never used for authorization).
ALTER TABLE match_decisions
    ADD COLUMN IF NOT EXISTS reviewer_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS reviewer_email VARCHAR(255);
