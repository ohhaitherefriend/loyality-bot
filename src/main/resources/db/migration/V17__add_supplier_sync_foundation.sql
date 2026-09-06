-- V17: Supplier sync automation foundation (Prompt 01).
--
-- NOTE on schema management: Flyway is currently disabled in all profiles
-- (see application.yml / application-prod.yml) and both dev/prod rely on
-- Hibernate `ddl-auto: update` against JPA entity annotations for the actual
-- applied schema, exactly like V1-V16 before this file. This migration is
-- written to be the intended PostgreSQL-compatible schema for when Flyway is
-- enabled (tracked as a separate, already-flagged blocker in docs/STATE.md -
-- production baseline is unconfirmed and older migrations mix MySQL/Postgres
-- dialect assumptions). JSON columns are declared JSONB here; the entities
-- currently persist them as TEXT via Hibernate to stay portable between H2
-- (tests/dev) and PostgreSQL (prod) without a native JSON type converter.

CREATE TABLE IF NOT EXISTS suppliers (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_suppliers_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT uk_suppliers_shop_name UNIQUE (shop_id, name)
);

CREATE INDEX IF NOT EXISTS idx_suppliers_shop_id ON suppliers(shop_id);

CREATE TABLE IF NOT EXISTS supplier_sources (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_id BIGINT NOT NULL,
    label VARCHAR(255) NOT NULL,
    snapshot_mode VARCHAR(16) NOT NULL DEFAULT 'FULL',
    snapshot_scope VARCHAR(255) NOT NULL DEFAULT 'SUPPLIER_ALL',
    commission_percent_override NUMERIC(7, 2),
    public_price_strategy VARCHAR(32) NOT NULL DEFAULT 'LOWEST_ACTIVE_OFFER',
    rounding_policy VARCHAR(32) NOT NULL DEFAULT 'WHOLE_UNIT_HALF_UP',
    shadow_mode BOOLEAN NOT NULL DEFAULT TRUE,
    auto_apply BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_supplier_sources_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_supplier_sources_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT uk_supplier_sources_shop_supplier_label UNIQUE (shop_id, supplier_id, label)
);

CREATE INDEX IF NOT EXISTS idx_supplier_sources_shop_id ON supplier_sources(shop_id);
CREATE INDEX IF NOT EXISTS idx_supplier_sources_shop_supplier ON supplier_sources(shop_id, supplier_id);

CREATE TABLE IF NOT EXISTS mailbox_connections (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    label VARCHAR(255) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_secret TEXT,
    auth_mode VARCHAR(32),
    use_tls BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_poll_at TIMESTAMP,
    last_poll_success_at TIMESTAMP,
    last_poll_error VARCHAR(1024),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_mailbox_connections_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT uk_mailbox_connections_shop_label UNIQUE (shop_id, label)
);

CREATE INDEX IF NOT EXISTS idx_mailbox_connections_shop_id ON mailbox_connections(shop_id);

CREATE TABLE IF NOT EXISTS mailbox_cursors (
    id BIGSERIAL PRIMARY KEY,
    mailbox_connection_id BIGINT NOT NULL,
    uid_validity BIGINT,
    last_seen_uid BIGINT,
    last_advanced_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_mailbox_cursors_mailbox FOREIGN KEY (mailbox_connection_id) REFERENCES mailbox_connections(id),
    CONSTRAINT uk_mailbox_cursors_mailbox UNIQUE (mailbox_connection_id)
);

CREATE TABLE IF NOT EXISTS import_rule_versions (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_source_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    rule_definition JSONB NOT NULL,
    created_at TIMESTAMP,
    CONSTRAINT fk_import_rule_versions_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_import_rule_versions_source FOREIGN KEY (supplier_source_id) REFERENCES supplier_sources(id),
    CONSTRAINT uk_import_rule_versions_source_version UNIQUE (supplier_source_id, version)
);

CREATE INDEX IF NOT EXISTS idx_import_rule_versions_shop_id ON import_rule_versions(shop_id);
CREATE INDEX IF NOT EXISTS idx_import_rule_versions_source_status ON import_rule_versions(supplier_source_id, status);

CREATE TABLE IF NOT EXISTS import_files (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_source_id BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(255) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    source_identity JSONB,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_import_files_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_import_files_source FOREIGN KEY (supplier_source_id) REFERENCES supplier_sources(id),
    CONSTRAINT uk_import_files_shop_source_sha256 UNIQUE (shop_id, supplier_source_id, sha256)
);

CREATE INDEX IF NOT EXISTS idx_import_files_shop_id ON import_files(shop_id);
CREATE INDEX IF NOT EXISTS idx_import_files_shop_source ON import_files(shop_id, supplier_source_id);

CREATE TABLE IF NOT EXISTS import_batches (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_source_id BIGINT NOT NULL,
    import_file_id BIGINT NOT NULL,
    rule_version_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'STORED',
    attempt_number INTEGER NOT NULL DEFAULT 1,
    total_rows INTEGER,
    valid_rows INTEGER,
    invalid_rows INTEGER,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_import_batches_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_import_batches_source FOREIGN KEY (supplier_source_id) REFERENCES supplier_sources(id),
    CONSTRAINT fk_import_batches_file FOREIGN KEY (import_file_id) REFERENCES import_files(id),
    CONSTRAINT fk_import_batches_rule_version FOREIGN KEY (rule_version_id) REFERENCES import_rule_versions(id),
    CONSTRAINT uk_import_batches_import_file UNIQUE (import_file_id)
);

CREATE INDEX IF NOT EXISTS idx_import_batches_shop_id ON import_batches(shop_id);
CREATE INDEX IF NOT EXISTS idx_import_batches_shop_source_status ON import_batches(shop_id, supplier_source_id, status);

CREATE TABLE IF NOT EXISTS import_rows (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    import_batch_id BIGINT NOT NULL,
    source_sheet VARCHAR(255),
    source_row_number INTEGER,
    raw_data JSONB,
    normalized_data JSONB,
    status VARCHAR(32) DEFAULT 'PENDING',
    matched_product_id BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_import_rows_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_import_rows_batch FOREIGN KEY (import_batch_id) REFERENCES import_batches(id),
    CONSTRAINT fk_import_rows_product FOREIGN KEY (matched_product_id) REFERENCES products(id)
);

CREATE INDEX IF NOT EXISTS idx_import_rows_shop_id ON import_rows(shop_id);
CREATE INDEX IF NOT EXISTS idx_import_rows_batch_id ON import_rows(import_batch_id);

CREATE TABLE IF NOT EXISTS supplier_product_links (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_id BIGINT NOT NULL,
    product_id BIGINT,
    external_sku VARCHAR(255),
    barcode VARCHAR(255),
    fingerprint VARCHAR(512),
    confirmed_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_supplier_product_links_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_supplier_product_links_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT fk_supplier_product_links_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT uk_supplier_product_links_sku UNIQUE (shop_id, supplier_id, external_sku),
    CONSTRAINT uk_supplier_product_links_barcode UNIQUE (shop_id, supplier_id, barcode)
);

CREATE INDEX IF NOT EXISTS idx_supplier_product_links_shop_id ON supplier_product_links(shop_id);
CREATE INDEX IF NOT EXISTS idx_supplier_product_links_shop_supplier ON supplier_product_links(shop_id, supplier_id);

CREATE TABLE IF NOT EXISTS supplier_offers (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_id BIGINT NOT NULL,
    supplier_source_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    external_sku VARCHAR(255),
    barcode VARCHAR(255),
    supplier_price NUMERIC(19, 2) NOT NULL,
    applied_commission_percent NUMERIC(7, 2) NOT NULL,
    calculated_site_price NUMERIC(19, 2) NOT NULL,
    stock_quantity INTEGER,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_batch_id BIGINT,
    first_seen_at TIMESTAMP,
    last_seen_at TIMESTAMP,
    deactivated_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_supplier_offers_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_supplier_offers_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT fk_supplier_offers_source FOREIGN KEY (supplier_source_id) REFERENCES supplier_sources(id),
    CONSTRAINT fk_supplier_offers_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_supplier_offers_last_seen_batch FOREIGN KEY (last_seen_batch_id) REFERENCES import_batches(id),
    CONSTRAINT uk_supplier_offers_shop_supplier_product UNIQUE (shop_id, supplier_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_supplier_offers_shop_id ON supplier_offers(shop_id);
CREATE INDEX IF NOT EXISTS idx_supplier_offers_shop_product_active ON supplier_offers(shop_id, product_id, active);
CREATE INDEX IF NOT EXISTS idx_supplier_offers_shop_source ON supplier_offers(shop_id, supplier_source_id);

CREATE TABLE IF NOT EXISTS match_decisions (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    import_row_id BIGINT NOT NULL,
    candidate_product_ids JSONB,
    chosen_product_id BIGINT,
    decision_type VARCHAR(16) NOT NULL,
    confidence_score NUMERIC(5, 4),
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    prompt_version VARCHAR(64),
    conflicts JSONB,
    reason VARCHAR(512),
    decided_by VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    decided_at TIMESTAMP,
    created_at TIMESTAMP,
    CONSTRAINT fk_match_decisions_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id),
    CONSTRAINT fk_match_decisions_row FOREIGN KEY (import_row_id) REFERENCES import_rows(id),
    CONSTRAINT fk_match_decisions_product FOREIGN KEY (chosen_product_id) REFERENCES products(id)
);

CREATE INDEX IF NOT EXISTS idx_match_decisions_shop_id ON match_decisions(shop_id);
CREATE INDEX IF NOT EXISTS idx_match_decisions_import_row_id ON match_decisions(import_row_id);

CREATE TABLE IF NOT EXISTS import_job_claims (
    id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(128) NOT NULL,
    job_key VARCHAR(255) NOT NULL,
    owner_token VARCHAR(64) NOT NULL,
    claimed_at TIMESTAMP NOT NULL,
    lease_expires_at TIMESTAMP NOT NULL,
    heartbeat_at TIMESTAMP,
    released_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_import_job_claims_type_key UNIQUE (job_type, job_key)
);
