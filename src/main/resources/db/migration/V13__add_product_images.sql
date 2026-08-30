-- V13: Product images

CREATE TABLE IF NOT EXISTS product_images (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    product_id BIGINT NOT NULL,
    image_type VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    status VARCHAR(32) NOT NULL DEFAULT 'DOWNLOADED',
    source_type VARCHAR(32),
    source_url VARCHAR(2048),
    source_page_url VARCHAR(2048),
    source_domain VARCHAR(255),
    original_url VARCHAR(2048),
    normalized_url VARCHAR(2048),
    confidence NUMERIC(10, 2),
    matched_by VARCHAR(64),
    approved_by_admin BOOLEAN NOT NULL DEFAULT FALSE,
    ai_normalized BOOLEAN NOT NULL DEFAULT FALSE,
    reject_reason VARCHAR(512),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE INDEX IF NOT EXISTS idx_product_images_shop_product ON product_images(shop_id, product_id);
