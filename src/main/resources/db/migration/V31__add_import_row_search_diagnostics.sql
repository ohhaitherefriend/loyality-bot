-- ADR-030: persists SearchCompleteness (whether the required unbounded exact-identity catalog
-- check actually ran for this row, vs was skipped because the row had no usable brand/fingerprint)
-- alongside the existing candidate_search_result. Nullable and additive - existing rows simply
-- have NULL here, which ImportBatchMatchingService treats as "unknown/incomplete", never as
-- "search was complete", so no already-persisted batch is retroactively (mis)trusted.
ALTER TABLE import_rows
    ADD COLUMN candidate_search_diagnostics TEXT NULL;
