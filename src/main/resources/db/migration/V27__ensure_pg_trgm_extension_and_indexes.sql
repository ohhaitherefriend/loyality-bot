-- V27: Stage 6/ADR-013 baseline-safety net.
--
-- `prod` baselines Flyway at V23 (see application-prod.yml's spring.flyway.baseline-version),
-- meaning V17-V23 are assumed already applied there via years of Hibernate `ddl-auto: update` and
-- are never actually re-executed. That assumption holds for anything Hibernate itself creates from
-- entity mappings (tables/columns/plain-btree @Index annotations), but V19's `CREATE EXTENSION
-- pg_trgm` and its two GIN trigram indexes are raw SQL that ddl-auto never ran - so `prod` may
-- never have actually gotten them. This migration re-asserts them for real, after the baseline, so
-- every environment (including `prod`) ends up with them regardless of which historical migrations
-- were baselined away. It is a pure no-op everywhere else (a fresh database migrated from V1
-- already created these in V19; IF NOT EXISTS makes re-declaring them here harmless).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_products_name_trgm
    ON products USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_products_brand_trgm
    ON products USING gin (brand gin_trgm_ops);
