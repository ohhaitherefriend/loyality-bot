-- V23: Prompt 09 production hardening - composite indexes for exception-queue/dashboard queries
-- that filter by (shop_id, status) without a supplier predicate.
--
-- Schema management: same note as V18 (Stage 6/ADR-013) - baselined away in `prod`, real/executed
-- everywhere else. These two indexes are also declared as JPA @Index annotations on ImportRow /
-- ImportBatch, so ddl-auto has already created them in `prod` prior to this release regardless.

-- Exception queue (ImportRowRepository.findExceptionRows) and dashboard counts
-- (countByShopIdAndStatusIn) both filter by shop_id + status across a shop's entire row history.
CREATE INDEX IF NOT EXISTS idx_import_rows_shop_id_status ON import_rows (shop_id, status);

-- Dashboard/exceptions batch list (countByShopIdAndStatus(In), findByShopIdAndStatusIn...) filters
-- by shop_id + status without a supplier_source_id predicate, so it can't efficiently use the
-- existing idx_import_batches_shop_source_status (supplier_source_id sits between the two columns
-- it actually needs).
CREATE INDEX IF NOT EXISTS idx_import_batches_shop_id_status ON import_batches (shop_id, status);
