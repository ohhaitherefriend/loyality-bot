-- Schema management: same note as V9 (Stage 6/ADR-013) - IF NOT EXISTS added for fresh installs
-- (V0 already creates these columns from the current entity mappings).
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS bonus_points_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS bonus_cashback_percent INTEGER NOT NULL DEFAULT 5;
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS bonus_max_spend_percent INTEGER NOT NULL DEFAULT 100;
ALTER TABLE users ADD COLUMN IF NOT EXISTS bonus_balance DOUBLE PRECISION NOT NULL DEFAULT 0;
