-- V18: Email ingestion mailbox routing filters (Prompt 02).
--
-- Schema management (Stage 6/ADR-013): Flyway is now enabled for `prod` with
-- baseline-version=23 - this file is baselined away there (its schema is assumed already present
-- from years of Hibernate `ddl-auto: update`) but is the real, executed source of truth for every
-- fresh database (dev/test Testcontainers, new environments). See V24's comment for the full
-- rationale.
--
-- One MailboxConnection can be shared by several SupplierSource rows (e.g. one shop inbox
-- receiving price lists from several suppliers), so `folder` lives on the mailbox (it pins the
-- single MailboxCursor UIDVALIDITY/UID to one physical IMAP folder), while sender/subject/filename
-- routing filters live on supplier_sources.

ALTER TABLE mailbox_connections
    ADD COLUMN IF NOT EXISTS folder VARCHAR(255) NOT NULL DEFAULT 'INBOX';

ALTER TABLE supplier_sources
    ADD COLUMN IF NOT EXISTS mailbox_connection_id BIGINT,
    ADD COLUMN IF NOT EXISTS sender_allowlist TEXT,
    ADD COLUMN IF NOT EXISTS subject_pattern VARCHAR(512),
    ADD COLUMN IF NOT EXISTS filename_pattern VARCHAR(512);

ALTER TABLE supplier_sources
    ADD CONSTRAINT fk_supplier_sources_mailbox
        FOREIGN KEY (mailbox_connection_id) REFERENCES mailbox_connections(id);

CREATE INDEX IF NOT EXISTS idx_supplier_sources_mailbox_connection
    ON supplier_sources(mailbox_connection_id);
