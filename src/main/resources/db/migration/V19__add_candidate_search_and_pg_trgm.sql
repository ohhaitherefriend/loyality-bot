-- V19: Normalization and deterministic candidate search (Prompt 04).
--
-- Same schema-management note as V17/V18: Flyway stays disabled in every profile, so this file is
-- the intended PostgreSQL-compatible target schema, not something actually applied. Dev/test/prod
-- all still rely on Hibernate `ddl-auto: update` against the JPA entities for the real schema; the
-- entity persists this new column as TEXT (not JSONB) for the same H2/PostgreSQL portability reason
-- documented in V17.
--
-- pg_trgm is only used by TrigramProductCandidateFetcher, which is wired in instead of the default
-- SimpleProductCandidateFetcher via `supplier-import.matching.pg-trgm-enabled=true` (opt-in, NOT tied
-- to the `prod` Spring profile, because Flyway being disabled means a `prod` deployment cannot be
-- assumed to have this extension/index applied yet).

ALTER TABLE import_rows
    ADD COLUMN IF NOT EXISTS candidate_search_result JSONB;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_products_name_trgm
    ON products USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_products_brand_trgm
    ON products USING gin (brand gin_trgm_ops);
