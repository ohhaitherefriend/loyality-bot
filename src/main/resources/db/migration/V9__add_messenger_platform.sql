-- Schema management: same note as V18 (Stage 6/ADR-013) - baselined away in `prod`, real/executed
-- everywhere else, so this needs IF NOT EXISTS for a fresh install where V0 already created this
-- column from the current BotInstance entity mapping.
ALTER TABLE bot_instances ADD COLUMN IF NOT EXISTS platform VARCHAR(16) DEFAULT 'TELEGRAM' NOT NULL;
