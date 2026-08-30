-- V12: Commerce catalog, cart and orders

CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    supplier_guid VARCHAR(255),
    source_sheet VARCHAR(255),
    source_row INTEGER,
    brand VARCHAR(255),
    supplier_article VARCHAR(255),
    barcode VARCHAR(255),
    name VARCHAR(1024) NOT NULL,
    description TEXT,
    category_path VARCHAR(1024),
    supplier_price NUMERIC(19, 2),
    sale_price NUMERIC(19, 2),
    old_price NUMERIC(19, 2),
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    stock_quantity INTEGER,
    availability_mode VARCHAR(32) NOT NULL DEFAULT 'PREORDER',
    visible BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    main_image_url VARCHAR(2048),
    image_status VARCHAR(32) NOT NULL DEFAULT 'MISSING',
    price_list_date DATE,
    last_imported_at TIMESTAMP,
    image_updated_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_products_shop_id ON products(shop_id);
CREATE INDEX IF NOT EXISTS idx_products_shop_visible_active ON products(shop_id, visible, active);
CREATE INDEX IF NOT EXISTS idx_products_shop_brand ON products(shop_id, brand);
CREATE INDEX IF NOT EXISTS idx_products_shop_barcode ON products(shop_id, barcode);
CREATE UNIQUE INDEX IF NOT EXISTS uk_products_shop_supplier_guid
    ON products(shop_id, supplier_guid)
    WHERE supplier_guid IS NOT NULL AND supplier_guid <> '';

CREATE TABLE IF NOT EXISTS product_import_batches (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    filename VARCHAR(512),
    price_list_date DATE,
    total_rows INTEGER,
    imported_count INTEGER,
    updated_count INTEGER,
    skipped_count INTEGER,
    status VARCHAR(32),
    error_message TEXT,
    created_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_product_import_batches_shop_id ON product_import_batches(shop_id);

CREATE TABLE IF NOT EXISTS customer_orders (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    items_total NUMERIC(19, 2),
    bonus_spent NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_to_pay NUMERIC(19, 2),
    bonus_accrued NUMERIC(19, 2) NOT NULL DEFAULT 0,
    customer_phone VARCHAR(64),
    customer_name VARCHAR(255),
    delivery_type VARCHAR(32),
    delivery_address TEXT,
    customer_comment TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    CONSTRAINT fk_customer_orders_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_customer_orders_shop_created ON customer_orders(shop_id, created_at);
CREATE INDEX IF NOT EXISTS idx_customer_orders_shop_status ON customer_orders(shop_id, status);

CREATE TABLE IF NOT EXISTS cart_items (
    id BIGSERIAL PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_cart_items_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT uk_cart_items_user_product UNIQUE (user_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_cart_items_shop_user ON cart_items(shop_id, user_id);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_snapshot VARCHAR(255),
    barcode_snapshot VARCHAR(255),
    brand_snapshot VARCHAR(255),
    name_snapshot VARCHAR(1024),
    price_snapshot NUMERIC(19, 2),
    quantity INTEGER NOT NULL,
    line_total NUMERIC(19, 2),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES customer_orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
