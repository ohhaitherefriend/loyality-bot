-- V25: Production-hardening pass Stage 3 - fix SupplierOffer identity so two independent FULL
-- snapshot scopes of the SAME supplier can never deactivate/overwrite each other's offer for the
-- same product.
--
-- Schema management: same note as V24 (Stage 6/ADR-013) - this migration actually runs against
-- `prod` (it is after spring.flyway.baseline-version), so its backfill UPDATE and constraint swap
-- must be safe to execute for real on top of the pre-existing V1-V23 schema.
--
-- Problem: the original unique key was (shop_id, supplier_id, product_id). If the same supplier's
-- catalog is split across independent snapshot scopes (e.g. one file per category) and the same
-- product happens to appear under two scopes, both scopes' applies collided onto ONE row - whichever
-- scope was applied last "stole" it, and the other scope's FULL reconciliation (which joined on the
-- offer's live supplier_source.snapshot_scope) lost track of "its" offer entirely.
--
-- Fix: persist snapshot_scope directly on the offer (copied from the writing SupplierSource at
-- apply time, not a live join) and widen identity to (shop_id, supplier_id, snapshot_scope,
-- product_id). Several transport sources that share one scope (e.g. a re-sent duplicate file under a
-- new SupplierSource row) still upsert the same logical offer; two different scopes never do.

ALTER TABLE supplier_offers
    ADD COLUMN IF NOT EXISTS snapshot_scope VARCHAR(255);

-- Backfill from the offer's current supplier_source (best available signal for pre-existing rows -
-- every row was, at the time it was last written, authoritative for that source's scope).
UPDATE supplier_offers o
SET snapshot_scope = COALESCE(
    (SELECT s.snapshot_scope FROM supplier_sources s WHERE s.id = o.supplier_source_id),
    'SUPPLIER_ALL')
WHERE o.snapshot_scope IS NULL;

ALTER TABLE supplier_offers
    ALTER COLUMN snapshot_scope SET NOT NULL,
    ALTER COLUMN snapshot_scope SET DEFAULT 'SUPPLIER_ALL';

-- The old (shop, supplier, product) key was strictly narrower than the new one, so backfilled data
-- can never violate the new constraint (at most one row already existed per shop+supplier+product).
ALTER TABLE supplier_offers
    DROP CONSTRAINT IF EXISTS uk_supplier_offers_shop_supplier_product;

-- Postgres has no `ADD CONSTRAINT IF NOT EXISTS`; guarded so this is safe to run both on a
-- pre-existing `prod` table (constraint genuinely new) and on a fresh install where V0's
-- Hibernate-generated baseline already created this exact named constraint from the current
-- SupplierOffer entity mapping (see V0's header comment, Stage 6/ADR-013).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_supplier_offers_shop_supplier_scope_product'
    ) THEN
        ALTER TABLE supplier_offers
            ADD CONSTRAINT uk_supplier_offers_shop_supplier_scope_product
                UNIQUE (shop_id, supplier_id, snapshot_scope, product_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_supplier_offers_shop_supplier_scope
    ON supplier_offers (shop_id, supplier_id, snapshot_scope);
