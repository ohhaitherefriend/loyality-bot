ALTER TABLE shop_settings ADD COLUMN permanent_discount_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop_settings ADD COLUMN permanent_discount_tiers TEXT;
ALTER TABLE users ADD COLUMN permanent_discount_percent INTEGER;
