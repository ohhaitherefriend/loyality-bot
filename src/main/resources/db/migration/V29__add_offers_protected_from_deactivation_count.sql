-- V29: FULL-snapshot apply must distinguish "product genuinely absent from the file" from
-- "product present in the file but its row failed processing this batch" (ADR-022). The latter
-- case must never silently deactivate/hide the product's existing offer; this counter makes that
-- outcome visible on the batch instead of it being a silent no-op.
ALTER TABLE import_batches
    ADD COLUMN IF NOT EXISTS offers_protected_from_deactivation_count INTEGER;
