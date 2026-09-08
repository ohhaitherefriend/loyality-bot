-- V20: Per-source AI catalog matcher thresholds (Prompt 05).
--
-- Schema management: same note as V18 (Stage 6/ADR-013) - baselined away in `prod`, real/executed
-- everywhere else.
--
-- Both columns are nullable overrides of the global `supplier-import.matching.ai-auto-approve-min-
-- score` / `ai-min-confidence` defaults (same override pattern as `commission_percent_override`);
-- NULL means "use the global default". Changing a value only affects future MatchDecision rows -
-- past decisions remain an immutable audit record either way.

ALTER TABLE supplier_sources
    ADD COLUMN IF NOT EXISTS ai_auto_approve_min_score_override NUMERIC(5,4),
    ADD COLUMN IF NOT EXISTS ai_min_confidence_override NUMERIC(5,4);
