-- V26: Production-hardening pass Stage 4 - shop-scoped, admin-managed brand alias table, replacing
-- the previous single hardcoded (Chanel/Шанель/Channel) Java constant.
--
-- Schema management: same note as V24 (Stage 6/ADR-013) - this migration actually runs against
-- `prod` (it is after spring.flyway.baseline-version).

CREATE TABLE IF NOT EXISTS brand_aliases (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    canonical_brand VARCHAR(255) NOT NULL,
    alias VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) NOT NULL,
    created_by VARCHAR(255),
    created_at TIMESTAMP,
    CONSTRAINT uk_brand_aliases_shop_normalized_alias UNIQUE (shop_id, normalized_alias)
);

CREATE INDEX IF NOT EXISTS idx_brand_aliases_shop_id ON brand_aliases (shop_id);

-- FK is added separately, not inline in the CREATE TABLE (Stage 6/ADR-013): BrandAlias.shopId is a
-- plain scalar field (no JPA @ManyToOne to Shop, matching the rest of this shop-scoped codebase),
-- so V0's Hibernate-generated baseline creates this table WITHOUT this FK - if it were inline here,
-- a fresh install would silently never get it at all once V0 already satisfies
-- "CREATE TABLE IF NOT EXISTS". Postgres has no `ADD CONSTRAINT IF NOT EXISTS`, hence the guard.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_brand_aliases_shop'
    ) THEN
        ALTER TABLE brand_aliases
            ADD CONSTRAINT fk_brand_aliases_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id);
    END IF;
END $$;

-- Seed the previously-hardcoded Chanel/Шанель/Channel group for every EXISTING shop, purely as a
-- starting-point example - it is no longer the only group that can ever exist (see
-- BrandAliasResolver/BrandAliasAdminController), and shops created after this migration runs start
-- with an empty table until an operator adds their own aliases via the admin UI/API.
INSERT INTO brand_aliases (shop_id, canonical_brand, alias, normalized_alias, created_by, created_at)
SELECT s.shop_id, 'Chanel', v.alias, LOWER(v.alias), 'SYSTEM', CURRENT_TIMESTAMP
FROM shops s
CROSS JOIN (VALUES ('Chanel'), ('Шанель'), ('Channel')) AS v(alias)
WHERE NOT EXISTS (
    SELECT 1 FROM brand_aliases ba WHERE ba.shop_id = s.shop_id AND ba.normalized_alias = LOWER(v.alias)
);
