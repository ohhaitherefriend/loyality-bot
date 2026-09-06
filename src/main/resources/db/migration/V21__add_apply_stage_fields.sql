-- V21: Prompt 06 apply stage - manual visibility override, shop-level default commission,
-- and per-batch apply audit counters.
--
-- Same schema-management note as V17-V20: Flyway stays disabled in every profile, so this file is
-- the intended PostgreSQL-compatible target schema, not something actually applied. Dev/test/prod
-- all still rely on Hibernate `ddl-auto: update` against the JPA entities for the real schema.

-- Explicit operator override (D-006/docs/ARCHITECTURE.md §14.8): automatic sync never clears this.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS manual_hidden BOOLEAN NOT NULL DEFAULT FALSE;

-- Shop-level default supplier import commission percent (D-007). NULL means "use the global
-- supplier-import.pricing.default-commission-percent config default" - same override pattern as
-- supplier_sources.commission_percent_override.
ALTER TABLE shop_settings
    ADD COLUMN IF NOT EXISTS default_commission_percent NUMERIC(7,2);

-- Apply audit counters (docs/ARCHITECTURE.md §14.11) - all NULL until the batch reaches APPLIED.
ALTER TABLE import_batches
    ADD COLUMN IF NOT EXISTS applied_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS offers_added_count INTEGER,
    ADD COLUMN IF NOT EXISTS offers_updated_count INTEGER,
    ADD COLUMN IF NOT EXISTS offers_price_changed_count INTEGER,
    ADD COLUMN IF NOT EXISTS offers_unchanged_count INTEGER,
    ADD COLUMN IF NOT EXISTS products_removed_from_storefront_count INTEGER,
    ADD COLUMN IF NOT EXISTS products_reactivated_count INTEGER;
