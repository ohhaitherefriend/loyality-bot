-- Schema management: same note as V9 (Stage 6/ADR-013) - IF NOT EXISTS added for fresh installs
-- (V0 already creates these columns from the current entity mappings).
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS permanent_discount_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS permanent_discount_tiers TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS permanent_discount_percent INTEGER;
