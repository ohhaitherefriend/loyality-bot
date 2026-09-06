-- V18: Email ingestion mailbox routing filters (Prompt 02).
--
-- Same schema-management note as V17: Flyway stays disabled in every profile, so this file is
-- the intended PostgreSQL-compatible target schema, not something actually applied. Dev/test/prod
-- all still rely on Hibernate `ddl-auto: update` against the JPA entities for the real schema.
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
