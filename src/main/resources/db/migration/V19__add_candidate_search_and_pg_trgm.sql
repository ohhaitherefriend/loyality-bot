-- V19: Normalization and deterministic candidate search (Prompt 04).
--
-- Schema management: same TEXT-not-JSONB rationale as V17 (Stage 6/ADR-012) - the entity persists
-- this column as `@Column(columnDefinition = "TEXT") String candidateSearchResult`. Like V17/V18,
-- this file is baselined away in `prod` (ADR-013) - CREATE EXTENSION/CREATE INDEX here are
-- idempotent (IF NOT EXISTS), but since ddl-auto never ran arbitrary SQL, `prod` was never
-- guaranteed to actually have pg_trgm installed; V27 re-asserts it for real on top of the baseline
-- so this doesn't silently stay missing.
--
-- pg_trgm is only used by TrigramProductCandidateFetcher, which is wired in instead of the default
-- SimpleProductCandidateFetcher via `supplier-import.matching.pg-trgm-enabled=true` (opt-in even
-- though the extension/index are now guaranteed to exist - see V27 - kept opt-in because it was
-- historically not guaranteed and flipping the default on its own is out of scope here).

ALTER TABLE import_rows
    ADD COLUMN IF NOT EXISTS candidate_search_result TEXT;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_products_name_trgm
    ON products USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_products_brand_trgm
    ON products USING gin (brand gin_trgm_ops);
