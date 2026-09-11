-- ADR-030: SupplierProductLink.fingerprint is a PERSISTED value computed at link-confirmation
-- time by RowAttributeNormalizer. Changing the fingerprint algorithm (alias-canonical brand +
-- brand-stripped line, see RowAttributeNormalizer NORMALIZATION_VERSION) changes what string a
-- given brand/name produces - a link's already-stored fingerprint must not be silently compared
-- against a freshly-computed one from a DIFFERENT algorithm version as if they were the same
-- format. normalization_version records which algorithm version produced the CURRENTLY stored
-- fingerprint value; existing rows default to 1 (the only version that ever existed before this
-- migration) and are backfilled to the current version by
-- SupplierLinkFingerprintMigrationService, not by this migration itself (recomputing a fingerprint
-- requires shop-scoped BrandAlias data and the live Product catalog, which is application logic,
-- not something a schema migration should embed).
ALTER TABLE supplier_product_links
    ADD COLUMN normalization_version INT NOT NULL DEFAULT 1;
