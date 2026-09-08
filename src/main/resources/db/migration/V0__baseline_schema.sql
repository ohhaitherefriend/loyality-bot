-- V0: Stage 6/ADR-013 baseline schema.
--
-- Every table/column in this file is auto-generated straight from the current JPA entity mappings
-- via Hibernate's schema-generation script export against a real PostgreSQL dialect
-- (see SchemaBaselineGenerator, a one-off dev tool under src/test - regenerate this file with
-- `mvn test -Dtest=SchemaBaselineGenerator` after changing any entity, then hand-review the diff).
--
-- Why this exists: V1 through V23 were never actually schema-authoritative anywhere - `prod` has
-- always relied on Hibernate `ddl-auto: update` to build/evolve its real schema (V1/V2 literally
-- say so - "SELECT 1", "Hibernate will create the table"), and no environment has ever had Flyway
-- enabled. Without a real CREATE TABLE for e.g. `users`, running the migration chain from scratch
-- against an empty database (a brand-new dev/CI/Testcontainers database) fails immediately on the
-- first ALTER TABLE that touches a table nothing ever created - see FlywayPostgresSchemaTest.
--
-- What V0 does and does not need to cover: it reflects the CURRENT (final, post-Stage-6) entity
-- mappings, i.e. it already includes things V17-V27 also (re)state, such as
-- SupplierOffer.snapshotScope and its uk_supplier_offers_shop_supplier_scope_product unique
-- constraint, or the brand_aliases table. That is intentional, not a duplication bug: every
-- ALTER TABLE ... ADD COLUMN/CREATE TABLE in V1-V27 uses IF NOT EXISTS (or, for the one named
-- UNIQUE constraint that Postgres has no IF NOT EXISTS for, an explicit guarded DO block - see
-- V25), so those files simply no-op when V0 already put the same thing in place, and stay the real
-- source of truth for `prod`, which baselines Flyway at V23 and therefore never actually executes
-- V0 at all (see application-prod.yml's spring.flyway.baseline-version and V24's comment). V0 does
-- NOT include raw SQL that Hibernate cannot express from an entity mapping - the pg_trgm extension
-- and its two trigram indexes (V19/V27) and any pure-data migrations (V1/V3/V26's Chanel alias
-- seed) still run for real on every fresh install too.


    create table achievement_definitions (
        cooldown_days integer,
        display_order integer,
        is_active boolean not null,
        max_awards_per_customer integer,
        reward_value integer,
        trigger_value_int integer,
        trigger_value_period_days integer,
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        description varchar(255),
        emoji varchar(255) not null,
        reward_type varchar(255) check (reward_type in ('NONE','BONUS_POINTS','BONUS_STAMPS','DISCOUNT_PERCENT','DISCOUNT_FIXED')),
        title varchar(255) not null,
        trigger_params TEXT,
        trigger_type varchar(255) not null check (trigger_type in ('FIRST_PURCHASE','N_PURCHASES_TOTAL','N_VISITS_TOTAL','N_PURCHASES_IN_PERIOD','COME_BACK_AFTER_DAYS','PURCHASE_TIME_WINDOW','SPEND_IN_PERIOD','BECAME_REGULAR','BECAME_VIP','N_STAMPS_TOTAL','N_REWARDS_REDEEMED')),
        primary key (id)
    );

    create table admin_users (
        email_verified boolean not null,
        is_active boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        last_login_at timestamp(6),
        updated_at timestamp(6),
        email varchar(255) not null unique,
        name varchar(255),
        password_hash varchar(255) not null,
        primary key (id)
    );

    create table bot_instances (
        is_active boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        last_webhook_at timestamp(6),
        owner_chat_id bigint,
        telegram_bot_id bigint not null,
        updated_at timestamp(6),
        updates_processed bigint,
        platform varchar(16) not null check (platform in ('TELEGRAM','MAX')),
        shop_id varchar(36) not null unique,
        bot_username varchar(64) not null,
        webhook_secret varchar(64) not null,
        bot_token varchar(512) not null,
        webhook_url varchar(512),
        last_error varchar(1024),
        business_name varchar(255),
        business_type varchar(255) not null check (business_type in ('COFFEE','RETAIL','SERVICE','HYBRID')),
        owner_email varchar(255),
        status varchar(255) not null check (status in ('PENDING','CONNECTING','ACTIVE','ERROR','DISABLED')),
        primary key (id)
    );

    create table brand_aliases (
        created_at timestamp(6),
        id bigserial not null,
        shop_id varchar(36) not null,
        alias varchar(255) not null,
        canonical_brand varchar(255) not null,
        created_by varchar(255),
        normalized_alias varchar(255) not null,
        primary key (id),
        constraint uk_brand_aliases_shop_normalized_alias unique (shop_id, normalized_alias)
    );

    create table cart_items (
        quantity integer not null,
        created_at timestamp(6),
        id bigserial not null,
        product_id bigint not null,
        updated_at timestamp(6),
        user_id bigint not null,
        shop_id varchar(36) not null,
        primary key (id),
        constraint uk_cart_items_user_product unique (user_id, product_id)
    );

    create table client_notes (
        is_archived boolean not null,
        archived_at timestamp(6),
        archived_by_id bigint,
        created_at timestamp(6) not null,
        created_by_id bigint not null,
        customer_id bigint not null,
        id bigserial not null,
        updated_at timestamp(6),
        text varchar(200) not null,
        primary key (id)
    );

    create table customer_achievements (
        notification_sent boolean,
        reward_applied boolean,
        achievement_id bigint not null,
        awarded_at timestamp(6) not null,
        id bigserial not null,
        reward_applied_at timestamp(6),
        user_id bigint not null,
        metadata TEXT,
        primary key (id)
    );

    create table customer_badges (
        notification_sent boolean,
        awarded_at timestamp(6) not null,
        awarded_by_id bigint,
        badge_id bigint not null,
        expires_at timestamp(6),
        id bigserial not null,
        user_id bigint not null,
        metadata TEXT,
        reason varchar(255),
        status varchar(255) check (status in ('ACTIVE','EXPIRED','REVOKED')),
        primary key (id)
    );

    create table customer_orders (
        bonus_accrued numeric(19,2),
        bonus_spent numeric(19,2),
        items_total numeric(19,2),
        total_to_pay numeric(19,2),
        cancelled_at timestamp(6),
        completed_at timestamp(6),
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        user_id bigint not null,
        delivery_type varchar(32) check (delivery_type in ('PICKUP','COURIER','CDEK','OTHER')),
        source varchar(32) check (source in ('BOT','MINI_APP','ADMIN')),
        status varchar(32) not null check (status in ('DRAFT','CREATED','CONFIRMED','PACKING','READY_FOR_PICKUP','SHIPPED','COMPLETED','CANCELLED')),
        shop_id varchar(36) not null,
        customer_phone varchar(64),
        customer_comment TEXT,
        customer_name varchar(255),
        delivery_address TEXT,
        primary key (id)
    );

    create table discount_codes (
        discount_percent integer,
        created_at timestamp(6),
        expires_at timestamp(6),
        id bigserial not null,
        used_at timestamp(6),
        user_id bigint not null,
        code varchar(255) not null unique,
        description varchar(255),
        status varchar(255) check (status in ('ACTIVE','USED','EXPIRED')),
        primary key (id)
    );

    create table import_batches (
        attempt_number integer not null,
        invalid_rows integer,
        offers_added_count integer,
        offers_price_changed_count integer,
        offers_unchanged_count integer,
        offers_updated_count integer,
        products_reactivated_count integer,
        products_removed_from_storefront_count integer,
        total_rows integer,
        valid_rows integer,
        applied_at timestamp(6),
        approved_at timestamp(6),
        approved_by_user_id bigint,
        created_at timestamp(6),
        finished_at timestamp(6),
        id bigserial not null,
        import_file_id bigint not null unique,
        rule_version_id bigint,
        started_at timestamp(6),
        supplier_source_id bigint not null,
        updated_at timestamp(6),
        status varchar(32) not null check (status in ('RECEIVED','STORED','PARSING','NORMALIZING','MATCHING','VALIDATING','AUTO_APPROVED','NEEDS_ATTENTION','APPROVED','APPLYING','APPLIED','QUARANTINED','FAILED')),
        shop_id varchar(36) not null,
        approved_by_email varchar(255),
        error_message TEXT,
        primary key (id)
    );

    create table import_files (
        id bigserial not null,
        received_at timestamp(6) not null,
        size_bytes bigint not null,
        storage_deleted_at timestamp(6),
        supplier_source_id bigint not null,
        shop_id varchar(36) not null,
        sha256 varchar(64) not null,
        original_filename varchar(512) not null,
        storage_key varchar(1024) not null,
        media_type varchar(255) not null,
        source_identity TEXT,
        primary key (id),
        constraint uk_import_files_shop_source_sha256 unique (shop_id, supplier_source_id, sha256)
    );

    create table import_job_claims (
        claimed_at timestamp(6) not null,
        created_at timestamp(6),
        heartbeat_at timestamp(6),
        id bigserial not null,
        lease_expires_at timestamp(6) not null,
        released_at timestamp(6),
        updated_at timestamp(6),
        version bigint,
        owner_token varchar(64) not null,
        job_type varchar(128) not null,
        job_key varchar(255) not null,
        primary key (id),
        constraint uk_import_job_claims_type_key unique (job_type, job_key)
    );

    create table import_rows (
        source_row_number integer,
        created_at timestamp(6),
        id bigserial not null,
        import_batch_id bigint not null,
        matched_product_id bigint,
        updated_at timestamp(6),
        version bigint,
        status varchar(32) check (status in ('PENDING','EXACT_MATCH','LEARNED_MATCH','AI_MATCH','AUTO_APPROVED','NEEDS_REVIEW','NEW_PRODUCT','IGNORED','INVALID','APPROVED','APPLIED')),
        shop_id varchar(36) not null,
        candidate_search_result TEXT,
        normalized_data TEXT,
        raw_data TEXT,
        source_sheet varchar(255),
        primary key (id)
    );

    create table import_rule_versions (
        version integer not null,
        created_at timestamp(6),
        id bigserial not null,
        supplier_source_id bigint not null,
        source varchar(16) not null check (source in ('MANUAL','AI_GENERATED')),
        status varchar(16) not null check (status in ('DRAFT','ACTIVE','RETIRED')),
        shop_id varchar(36) not null,
        rule_definition TEXT not null,
        primary key (id),
        constraint uk_import_rule_versions_source_version unique (supplier_source_id, version)
    );

    create table mailbox_connections (
        enabled boolean not null,
        port integer not null,
        use_tls boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        last_poll_at timestamp(6),
        last_poll_success_at timestamp(6),
        updated_at timestamp(6),
        auth_mode varchar(32) check (auth_mode in ('OAUTH2','APP_PASSWORD')),
        shop_id varchar(36) not null,
        last_poll_error varchar(1024),
        encrypted_secret TEXT,
        folder varchar(255) not null,
        host varchar(255) not null,
        label varchar(255) not null,
        username varchar(255) not null,
        primary key (id),
        constraint uk_mailbox_connections_shop_label unique (shop_id, label)
    );

    create table mailbox_cursors (
        created_at timestamp(6),
        id bigserial not null,
        last_advanced_at timestamp(6),
        last_seen_uid bigint,
        mailbox_connection_id bigint not null unique,
        uid_validity bigint,
        updated_at timestamp(6),
        primary key (id)
    );

    create table manual_badge_definitions (
        display_order integer,
        is_active boolean not null,
        max_awards_per_month integer,
        max_per_customer integer,
        perk_value integer,
        valid_days integer,
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        description varchar(255),
        emoji varchar(255) not null,
        perk_type varchar(255) check (perk_type in ('NONE','BONUS_STAMPS_MULTIPLIER','BONUS_STAMPS_FLAT','BONUS_POINTS_MULTIPLIER','BONUS_POINTS_FLAT','PRIORITY_STATUS','STATUS_PROTECTION')),
        title varchar(255) not null,
        primary key (id)
    );

    create table match_decisions (
        confidence_score numeric(5,4),
        chosen_product_id bigint,
        created_at timestamp(6),
        decided_at timestamp(6),
        id bigserial not null,
        import_row_id bigint not null,
        reviewer_user_id bigint,
        decided_by varchar(16) not null check (decided_by in ('SYSTEM','HUMAN')),
        decision_type varchar(16) not null check (decision_type in ('EXACT','LEARNED','AI_MATCH','AI_NO_MATCH','NEW_PRODUCT','MANUAL','NO_MATCH')),
        shop_id varchar(36) not null,
        model_provider varchar(64),
        prompt_version varchar(64),
        model_name varchar(128),
        reason varchar(512),
        candidate_product_ids TEXT,
        conflicts TEXT,
        reviewer_email varchar(255),
        primary key (id)
    );

    create table message_logs (
        achievement_id bigint,
        id bigserial not null,
        sent_at timestamp(6) not null,
        sent_by_id bigint,
        signal_id bigint,
        template_id bigint,
        user_id bigint not null,
        content TEXT not null,
        error_message varchar(255),
        message_type varchar(255) not null check (message_type in ('MANUAL','AUTO_TRIGGER','ACHIEVEMENT','STATUS_CHANGE','REMINDER','PROMOTION','SYSTEM')),
        status varchar(255) check (status in ('SENT','DELIVERED','FAILED','BLOCKED')),
        primary key (id)
    );

    create table message_templates (
        is_active boolean,
        is_system boolean,
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        auto_trigger varchar(255) check (auto_trigger in ('FIRST_PURCHASE','ONE_STAMP_LEFT','BECAME_REGULAR','BECAME_VIP','VIP_BECAME_LOST','ACHIEVEMENT_EARNED','REWARD_AVAILABLE','BIRTHDAY','INACTIVE_REMINDER')),
        category varchar(255) check (category in ('GENERAL','WELCOME','PROMOTION','REMINDER','ACHIEVEMENT','STATUS_CHANGE')),
        content TEXT not null,
        name varchar(255) not null,
        primary key (id)
    );

    create table onboarding_states (
        completed boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        user_id bigint not null,
        shop_id varchar(36),
        data_json TEXT,
        step varchar(255) not null check (step in ('START','SHOP_CREATED','BOT_CONNECTED','SETTINGS_DONE','COMPLETED')),
        primary key (id)
    );

    create table order_items (
        line_total numeric(19,2),
        price_snapshot numeric(19,2),
        quantity integer not null,
        id bigserial not null,
        order_id bigint not null,
        product_id bigint not null,
        availability_mode_snapshot varchar(32) check (availability_mode_snapshot in ('PREORDER','IN_STOCK','OUT_OF_STOCK')),
        name_snapshot varchar(1024),
        barcode_snapshot varchar(255),
        brand_snapshot varchar(255),
        sku_snapshot varchar(255),
        primary key (id)
    );

    create table owner_signals (
        is_dismissed boolean,
        is_seen boolean,
        created_at timestamp(6) not null,
        customer_id bigint,
        dismissed_at timestamp(6),
        expires_at timestamp(6),
        id bigserial not null,
        seen_at timestamp(6),
        description TEXT,
        payload TEXT,
        severity varchar(255) not null check (severity in ('INFO','WARNING','IMPORTANT','CRITICAL')),
        signal_type varchar(255) not null check (signal_type in ('LOST_CUSTOMERS','VIP_INACTIVE','ALMOST_REGULAR','ALMOST_VIP','DROPPING_TRAFFIC','TRAFFIC_SPIKE','CUSTOMER_RETURNED','NEW_VIP','UNCLAIMED_REWARDS','WEEKLY_SUMMARY')),
        suggested_action varchar(255),
        title varchar(255) not null,
        primary key (id)
    );

    create table plans (
        currency varchar(3) not null,
        is_active boolean not null,
        is_stub boolean not null,
        period_days integer not null,
        price_amount integer not null,
        created_at timestamp(6),
        id bigserial not null,
        code varchar(64) not null unique,
        description varchar(1024),
        name varchar(255) not null,
        primary key (id)
    );

    create table product_images (
        ai_normalized boolean not null,
        angle_normalized boolean not null,
        approved_by_admin boolean not null,
        background_removed boolean not null,
        confidence numeric(10,2),
        scale_normalized boolean not null,
        visual_quality_score integer,
        created_at timestamp(6),
        id bigserial not null,
        product_id bigint not null,
        updated_at timestamp(6),
        image_type varchar(32) not null check (image_type in ('MAIN','CANDIDATE','PLACEHOLDER')),
        source_type varchar(32) check (source_type in ('SUPPLIER','BRAND_OFFICIAL','MARKETPLACE_CANDIDATE','MANUAL_UPLOAD','MANUAL_URL','AI_PLACEHOLDER')),
        status varchar(32) not null check (status in ('MISSING','CANDIDATE_FOUND','DOWNLOADED','NORMALIZED','NEEDS_REVIEW','APPROVED','REJECTED','FAILED')),
        shop_id varchar(36) not null,
        matched_by varchar(64),
        normalization_provider varchar(64),
        quality_decision varchar(64),
        manual_review_reason varchar(512),
        ranker_reason varchar(512),
        reject_reason varchar(512),
        normalized_url varchar(2048),
        original_url varchar(2048),
        source_page_url varchar(2048),
        source_url varchar(2048),
        quality_warnings TEXT,
        ranker_warnings TEXT,
        source_domain varchar(255),
        primary key (id)
    );

    create table product_import_batches (
        imported_count integer,
        price_list_date date,
        skipped_count integer,
        total_rows integer,
        updated_count integer,
        created_at timestamp(6),
        finished_at timestamp(6),
        id bigserial not null,
        status varchar(32),
        shop_id varchar(36) not null,
        filename varchar(512),
        error_message TEXT,
        primary key (id)
    );

    create table products (
        active boolean not null,
        currency varchar(3) not null,
        manual_hidden boolean not null,
        old_price numeric(19,2),
        price_list_date date,
        sale_price numeric(19,2),
        source_row integer,
        stock_quantity integer,
        supplier_price numeric(19,2),
        visible boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        image_updated_at timestamp(6),
        last_imported_at timestamp(6),
        updated_at timestamp(6),
        availability_mode varchar(32) not null check (availability_mode in ('PREORDER','IN_STOCK','OUT_OF_STOCK')),
        image_status varchar(32) not null check (image_status in ('MISSING','CANDIDATE_FOUND','DOWNLOADED','NORMALIZED','NEEDS_REVIEW','APPROVED','REJECTED','FAILED')),
        shop_id varchar(36) not null,
        category_path varchar(1024),
        name varchar(1024) not null,
        main_image_url varchar(2048),
        barcode varchar(255),
        brand varchar(255),
        description TEXT,
        source_sheet varchar(255),
        supplier_article varchar(255),
        supplier_guid varchar(255),
        primary key (id)
    );

    create table promotions (
        discount_percent integer not null,
        is_active boolean not null,
        created_at timestamp(6),
        created_by bigint not null,
        expires_at timestamp(6) not null,
        id bigserial not null,
        description varchar(255) not null,
        primary key (id)
    );

    create table purchase_codes (
        fast_checkout boolean,
        from_deep_link boolean,
        purchase_amount float(53),
        created_at timestamp(6),
        expires_at timestamp(6),
        id bigserial not null,
        used_at timestamp(6),
        used_by_admin_id bigint,
        user_id bigint not null,
        code varchar(255) not null unique,
        location_id varchar(255),
        status varchar(255) check (status in ('ACTIVE','USED','EXPIRED')),
        primary key (id)
    );

    create table redeem_codes (
        created_at timestamp(6),
        expires_at timestamp(6),
        id bigserial not null,
        stamp_wallet_id bigint not null,
        used_at timestamp(6),
        used_by_admin_id bigint,
        user_id bigint not null,
        code varchar(255) not null unique,
        reward_description varchar(255),
        reward_title varchar(255) not null,
        status varchar(255) check (status in ('ACTIVE','USED','EXPIRED','CANCELLED')),
        primary key (id)
    );

    create table shop_members (
        created_at timestamp(6),
        id bigserial not null,
        user_id bigint not null,
        shop_id varchar(36) not null,
        role varchar(255) not null check (role in ('OWNER','ADMIN','STAFF')),
        primary key (id),
        constraint uk_shop_member_user_shop unique (user_id, shop_id)
    );

    create table shop_settings (
        auto_messages_daily_limit_per_customer integer not null,
        auto_messages_enabled boolean not null,
        bonus_cashback_percent integer not null,
        bonus_max_spend_percent integer not null,
        bonus_points_enabled boolean not null,
        default_commission_percent numeric(7,2),
        discount_tier1amount float(53) not null,
        discount_tier1percent integer not null,
        discount_tier2amount float(53) not null,
        discount_tier2percent integer not null,
        discount_tier3amount float(53) not null,
        discount_tier3percent integer not null,
        discount_tiers_enabled boolean not null,
        discount_validity_days integer not null,
        fast_checkout_cooldown_minutes integer not null,
        fast_checkout_daily_limit_per_customer integer not null,
        fast_checkout_enabled boolean not null,
        fast_checkout_value integer not null,
        lost_days_since_last_purchase integer not null,
        permanent_discount_enabled boolean not null,
        redeem_code_ttl_minutes integer not null,
        redeem_requires_cashier_confirm boolean not null,
        regular_threshold_purchases integer not null,
        stamps_enabled boolean not null,
        stamps_per_fast_purchase integer not null,
        stamps_required_for_reward integer not null,
        vip_threshold_purchases integer not null,
        vip_threshold_total_spend float(53),
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        shop_id varchar(36) unique,
        default_location_id varchar(255),
        fast_checkout_type varchar(255) check (fast_checkout_type in ('STAMP','FIXED_POINTS')),
        permanent_discount_tiers TEXT,
        purchase_code_message TEXT,
        reward_description varchar(255),
        reward_earned_message TEXT,
        reward_title varchar(255) not null,
        shop_name varchar(255) not null,
        stamp_earned_message TEXT,
        telegram_channel_url varchar(255),
        welcome_message TEXT,
        primary key (id)
    );

    create table shops (
        created_at timestamp(6),
        id bigserial not null,
        owner_id bigint not null,
        updated_at timestamp(6),
        shop_id varchar(36) not null unique,
        timezone varchar(64),
        name varchar(255) not null,
        primary key (id)
    );

    create table spend_codes (
        points_to_spend integer not null,
        created_at timestamp(6),
        expires_at timestamp(6),
        id bigserial not null,
        used_at timestamp(6),
        used_by_admin_id bigint,
        user_id bigint not null,
        code varchar(255) not null unique,
        status varchar(255) check (status in ('ACTIVE','USED','EXPIRED')),
        primary key (id)
    );

    create table stamp_wallets (
        rewards_available integer not null,
        rewards_earned integer not null,
        rewards_redeemed integer not null,
        stamps_count integer not null,
        total_stamps_earned integer not null,
        created_at timestamp(6),
        id bigserial not null,
        last_stamp_at timestamp(6),
        updated_at timestamp(6),
        user_id bigint not null,
        primary key (id),
        constraint UKtop6rrkqvekopc0fsc5qoeiqr unique (user_id)
    );

    create table subscriptions (
        expiration_notified boolean not null,
        free_forever boolean not null,
        created_at timestamp(6),
        current_period_end_at timestamp(6),
        current_period_start_at timestamp(6),
        id bigserial not null,
        trial_end_at timestamp(6),
        trial_start_at timestamp(6),
        updated_at timestamp(6),
        shop_id varchar(36) not null unique,
        plan_code varchar(64) not null,
        external_subscription_id varchar(255),
        provider varchar(255) not null check (provider in ('STUB','CLOUDPAYMENTS','YOOKASSA')),
        status varchar(255) not null check (status in ('TRIALING','ACTIVE','EXPIRED','CANCELLED','PAST_DUE')),
        primary key (id)
    );

    create table supplier_offers (
        active boolean not null,
        applied_commission_percent numeric(7,2) not null,
        calculated_site_price numeric(19,2) not null,
        stock_quantity integer,
        supplier_price numeric(19,2) not null,
        created_at timestamp(6),
        deactivated_at timestamp(6),
        first_seen_at timestamp(6),
        id bigserial not null,
        last_seen_at timestamp(6),
        last_seen_batch_id bigint,
        product_id bigint not null,
        supplier_id bigint not null,
        supplier_source_id bigint not null,
        updated_at timestamp(6),
        shop_id varchar(36) not null,
        barcode varchar(255),
        external_sku varchar(255),
        snapshot_scope varchar(255) not null,
        primary key (id),
        constraint uk_supplier_offers_shop_supplier_scope_product unique (shop_id, supplier_id, snapshot_scope, product_id)
    );

    create table supplier_product_links (
        confirmed_at timestamp(6),
        created_at timestamp(6),
        id bigserial not null,
        product_id bigint,
        supplier_id bigint not null,
        updated_at timestamp(6),
        confirmed_source varchar(16) not null check (confirmed_source in ('AUTOMATIC','LEARNED','MANUAL')),
        shop_id varchar(36) not null,
        fingerprint varchar(512),
        barcode varchar(255),
        external_sku varchar(255),
        primary key (id),
        constraint uk_supplier_product_links_sku unique (shop_id, supplier_id, external_sku),
        constraint uk_supplier_product_links_barcode unique (shop_id, supplier_id, barcode)
    );

    create table supplier_sources (
        ai_auto_approve_min_score_override numeric(5,4),
        ai_min_confidence_override numeric(5,4),
        auto_apply boolean not null,
        commission_percent_override numeric(7,2),
        enabled boolean not null,
        shadow_mode boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        mailbox_connection_id bigint,
        supplier_id bigint not null,
        updated_at timestamp(6),
        version bigint not null,
        snapshot_mode varchar(16) not null check (snapshot_mode in ('FULL','DELTA')),
        public_price_strategy varchar(32) not null check (public_price_strategy in ('LOWEST_ACTIVE_OFFER')),
        rounding_policy varchar(32) not null check (rounding_policy in ('WHOLE_UNIT_HALF_UP','MINOR_UNIT_HALF_UP')),
        shop_id varchar(36) not null,
        filename_pattern varchar(512),
        subject_pattern varchar(512),
        label varchar(255) not null,
        sender_allowlist TEXT,
        snapshot_scope varchar(255) not null,
        primary key (id),
        constraint uk_supplier_sources_shop_supplier_label unique (shop_id, supplier_id, label)
    );

    create table suppliers (
        active boolean not null,
        created_at timestamp(6),
        id bigserial not null,
        updated_at timestamp(6),
        shop_id varchar(36) not null,
        code varchar(255),
        name varchar(255) not null,
        primary key (id),
        constraint uk_suppliers_shop_name unique (shop_id, name)
    );

    create table transactions (
        amount float(53),
        points integer not null,
        admin_id bigint,
        created_at timestamp(6),
        id bigserial not null,
        purchase_code_id bigint,
        user_id bigint not null,
        description varchar(255),
        type varchar(255) check (type in ('EARN','SPEND','BONUS')),
        primary key (id)
    );

    create table users (
        bonus_balance float(53),
        discount_level integer,
        fast_checkout_today_count integer,
        monthly_spent float(53),
        permanent_discount_percent integer,
        purchases_count integer,
        total_spend float(53),
        visits_count integer,
        chat_id bigint not null,
        created_at timestamp(6),
        discount_earned_at timestamp(6),
        fast_checkout_count_reset_at timestamp(6),
        first_purchase_at timestamp(6),
        id bigserial not null,
        last_fast_checkout_at timestamp(6),
        last_month_reset timestamp(6),
        last_purchase_at timestamp(6),
        status_updated_at timestamp(6),
        updated_at timestamp(6),
        shop_id varchar(36),
        customer_status varchar(255) check (customer_status in ('NEW','REGULAR','VIP','LOST')),
        first_name varchar(255),
        last_name varchar(255),
        phone_number varchar(255) not null,
        role varchar(255) check (role in ('USER','ADMIN')),
        state varchar(255) check (state in ('NEW','AWAITING_PHONE','REGISTERED','AWAITING_ADMIN_CODE','AWAITING_PURCHASE_AMOUNT','AWAITING_FAST_OR_AMOUNT_CHOICE','AWAITING_REDEEM_CODE','AWAITING_PROMOTION_DISCOUNT','AWAITING_PROMOTION_DURATION','AWAITING_PROMOTION_DESCRIPTION','AWAITING_CLIENT_NOTE','AWAITING_ORDER_ADDRESS')),
        username varchar(255),
        primary key (id),
        constraint idx_user_chat_id_shop_id unique (chat_id, shop_id)
    );

    create index idx_bot_instance_bot_username 
       on bot_instances (bot_username);

    create index idx_brand_aliases_shop_id 
       on brand_aliases (shop_id);

    create index idx_cart_items_shop_user 
       on cart_items (shop_id, user_id);

    create index idx_customer_orders_shop_created 
       on customer_orders (shop_id, created_at);

    create index idx_customer_orders_shop_status 
       on customer_orders (shop_id, status);

    create index idx_import_batches_shop_id 
       on import_batches (shop_id);

    create index idx_import_batches_shop_source_status 
       on import_batches (shop_id, supplier_source_id, status);

    create index idx_import_batches_shop_id_status 
       on import_batches (shop_id, status);

    create index idx_import_files_shop_id 
       on import_files (shop_id);

    create index idx_import_files_shop_source 
       on import_files (shop_id, supplier_source_id);

    create index idx_import_rows_shop_id 
       on import_rows (shop_id);

    create index idx_import_rows_batch_id 
       on import_rows (import_batch_id);

    create index idx_import_rows_shop_id_status 
       on import_rows (shop_id, status);

    create index idx_import_rule_versions_shop_id 
       on import_rule_versions (shop_id);

    create index idx_import_rule_versions_source_status 
       on import_rule_versions (supplier_source_id, status);

    create index idx_mailbox_connections_shop_id 
       on mailbox_connections (shop_id);

    create index idx_match_decisions_shop_id 
       on match_decisions (shop_id);

    create index idx_match_decisions_import_row_id 
       on match_decisions (import_row_id);

    create index idx_onboarding_user_id 
       on onboarding_states (user_id);

    create index idx_onboarding_shop_id 
       on onboarding_states (shop_id);

    create index idx_order_items_order_id 
       on order_items (order_id);

    create index idx_product_images_shop_product 
       on product_images (shop_id, product_id);

    create index idx_product_import_batches_shop_id 
       on product_import_batches (shop_id);

    create index idx_products_shop_id 
       on products (shop_id);

    create index idx_products_shop_visible_active 
       on products (shop_id, visible, active);

    create index idx_products_shop_brand 
       on products (shop_id, brand);

    create index idx_products_shop_barcode 
       on products (shop_id, barcode);

    create index idx_shop_member_user_id 
       on shop_members (user_id);

    create index idx_shop_member_shop_id 
       on shop_members (shop_id);

    create index idx_subscription_status 
       on subscriptions (status);

    create index idx_supplier_offers_shop_id 
       on supplier_offers (shop_id);

    create index idx_supplier_offers_shop_product_active 
       on supplier_offers (shop_id, product_id, active);

    create index idx_supplier_offers_shop_source 
       on supplier_offers (shop_id, supplier_source_id);

    create index idx_supplier_offers_shop_supplier_scope 
       on supplier_offers (shop_id, supplier_id, snapshot_scope);

    create index idx_supplier_product_links_shop_id 
       on supplier_product_links (shop_id);

    create index idx_supplier_product_links_shop_supplier 
       on supplier_product_links (shop_id, supplier_id);

    create index idx_supplier_sources_shop_id 
       on supplier_sources (shop_id);

    create index idx_supplier_sources_shop_supplier 
       on supplier_sources (shop_id, supplier_id);

    create index idx_suppliers_shop_id 
       on suppliers (shop_id);

    create index idx_user_phone_shop_id 
       on users (phone_number, shop_id);

    create index idx_user_shop_id 
       on users (shop_id);

    alter table if exists cart_items 
       add constraint FK1re40cjegsfvw58xrkdp6bac6 
       foreign key (product_id) 
       references products;

    alter table if exists cart_items 
       add constraint FK709eickf3kc0dujx3ub9i7btf 
       foreign key (user_id) 
       references users;

    alter table if exists client_notes 
       add constraint FK2rgfwkof89kn93xch83utxlst 
       foreign key (archived_by_id) 
       references users;

    alter table if exists client_notes 
       add constraint FK7uy9ywwxnoryaqwg2jx4cwenu 
       foreign key (created_by_id) 
       references users;

    alter table if exists client_notes 
       add constraint FKhdhrfgt6t7blflh1ahv43yesh 
       foreign key (customer_id) 
       references users;

    alter table if exists customer_achievements 
       add constraint FK33is8b3jutma32fiww1o7qxr2 
       foreign key (achievement_id) 
       references achievement_definitions;

    alter table if exists customer_achievements 
       add constraint FKqihy23xh6i1w7q89gl6d1dstd 
       foreign key (user_id) 
       references users;

    alter table if exists customer_badges 
       add constraint FKsuxusfyxvblf84nj1nth7w6m5 
       foreign key (awarded_by_id) 
       references users;

    alter table if exists customer_badges 
       add constraint FKckqvu44r6uer35d7akmpw5abr 
       foreign key (badge_id) 
       references manual_badge_definitions;

    alter table if exists customer_badges 
       add constraint FK659b6d7cpr325gjlmhm6ujvik 
       foreign key (user_id) 
       references users;

    alter table if exists customer_orders 
       add constraint FKt8e9kp8f6kmy0a3rytsmkkr8g 
       foreign key (user_id) 
       references users;

    alter table if exists discount_codes 
       add constraint FKsgredgbwhbqjshhutmb2vns1t 
       foreign key (user_id) 
       references users;

    alter table if exists import_batches 
       add constraint FKkpwffapff7wyqutwys6g1t683 
       foreign key (import_file_id) 
       references import_files;

    alter table if exists import_batches 
       add constraint FKhiqax3se0dbnh57fawauesbap 
       foreign key (rule_version_id) 
       references import_rule_versions;

    alter table if exists import_batches 
       add constraint FKd9kqbdt8mb5nepq9c1spvs1rn 
       foreign key (supplier_source_id) 
       references supplier_sources;

    alter table if exists import_files 
       add constraint FKl41attq2qw3b5axobrtivdo1 
       foreign key (supplier_source_id) 
       references supplier_sources;

    alter table if exists import_rows 
       add constraint FKmbw8jan5y6mej1bga0mjky6sx 
       foreign key (import_batch_id) 
       references import_batches;

    alter table if exists import_rows 
       add constraint FKemf7qn3gq5yohw1uoayq38dm7 
       foreign key (matched_product_id) 
       references products;

    alter table if exists import_rule_versions 
       add constraint FKjl94yetfv8gi8lf35gmf0i9po 
       foreign key (supplier_source_id) 
       references supplier_sources;

    alter table if exists mailbox_cursors 
       add constraint FKmrporngyaing8e481x8phkf0d 
       foreign key (mailbox_connection_id) 
       references mailbox_connections;

    alter table if exists match_decisions 
       add constraint FKk49bohakfjb2s6o8hp40hw1kh 
       foreign key (chosen_product_id) 
       references products;

    alter table if exists match_decisions 
       add constraint FKlyvqu7odupyd9u2myy7d4uagh 
       foreign key (import_row_id) 
       references import_rows;

    alter table if exists message_logs 
       add constraint FKr5pxq0dlve589u3lf9wfpjn76 
       foreign key (achievement_id) 
       references customer_achievements;

    alter table if exists message_logs 
       add constraint FK6db27ddp8e8tma2pgbx4vasif 
       foreign key (sent_by_id) 
       references users;

    alter table if exists message_logs 
       add constraint FKtrwkpnjiaravrnlekov4vig1r 
       foreign key (signal_id) 
       references owner_signals;

    alter table if exists message_logs 
       add constraint FKse62wqvrrsq2xr2s8fec4xl65 
       foreign key (template_id) 
       references message_templates;

    alter table if exists message_logs 
       add constraint FKjfletghvcyea2r1u075gn9t3t 
       foreign key (user_id) 
       references users;

    alter table if exists order_items 
       add constraint FKb2vrrqy10nnyqhb5ergl5498r 
       foreign key (order_id) 
       references customer_orders;

    alter table if exists order_items 
       add constraint FKocimc7dtr037rh4ls4l95nlfi 
       foreign key (product_id) 
       references products;

    alter table if exists owner_signals 
       add constraint FK106w2qtji0ndbexnfinipiqgo 
       foreign key (customer_id) 
       references users;

    alter table if exists product_images 
       add constraint FKqnq71xsohugpqwf3c9gxmsuy 
       foreign key (product_id) 
       references products;

    alter table if exists promotions 
       add constraint FKdmyppdycrsqwl5mikrw105clk 
       foreign key (created_by) 
       references users;

    alter table if exists purchase_codes 
       add constraint FKhway9i9pyuf58rtihiv1asqry 
       foreign key (used_by_admin_id) 
       references users;

    alter table if exists purchase_codes 
       add constraint FKecjlodo2a907d803gc113so2n 
       foreign key (user_id) 
       references users;

    alter table if exists redeem_codes 
       add constraint FKdchrroy4e518axnfe7o2ew52y 
       foreign key (stamp_wallet_id) 
       references stamp_wallets;

    alter table if exists redeem_codes 
       add constraint FK75airrls5qdktj4oycmsd627v 
       foreign key (used_by_admin_id) 
       references users;

    alter table if exists redeem_codes 
       add constraint FK7c42dvf46m9heniryuvl4m4f 
       foreign key (user_id) 
       references users;

    alter table if exists spend_codes 
       add constraint FKqar53w96pd6rpc1w2t3husbcf 
       foreign key (used_by_admin_id) 
       references users;

    alter table if exists spend_codes 
       add constraint FKc8uj5tu8gopy8iv7wdwn58l2b 
       foreign key (user_id) 
       references users;

    alter table if exists stamp_wallets 
       add constraint FKojp19606smxf6xxvf7xw865oo 
       foreign key (user_id) 
       references users;

    alter table if exists supplier_offers 
       add constraint FKpb4jbgufd0exkm9r360n5ixnj 
       foreign key (last_seen_batch_id) 
       references import_batches;

    alter table if exists supplier_offers 
       add constraint FKqgaak3cyp7nmru1c0sr0frcf4 
       foreign key (product_id) 
       references products;

    alter table if exists supplier_offers 
       add constraint FKi9qjjjyicy2332tfynvli6ioo 
       foreign key (supplier_id) 
       references suppliers;

    alter table if exists supplier_offers 
       add constraint FK1hqf0gac2n36tf4mii8w35nj 
       foreign key (supplier_source_id) 
       references supplier_sources;

    alter table if exists supplier_product_links 
       add constraint FKpgmpy1is7uxg7tlmm8vd7cjwf 
       foreign key (product_id) 
       references products;

    alter table if exists supplier_product_links 
       add constraint FKgkqwxisp06jygn8ihevsne1h1 
       foreign key (supplier_id) 
       references suppliers;

    alter table if exists supplier_sources 
       add constraint FKdmr2bva6n960ctfvqee8f5348 
       foreign key (mailbox_connection_id) 
       references mailbox_connections;

    alter table if exists supplier_sources 
       add constraint FKmrf85i7n813h7283hydiw450f 
       foreign key (supplier_id) 
       references suppliers;

    alter table if exists transactions 
       add constraint FKmq0dnayapvq16pusf3l0j2e4t 
       foreign key (admin_id) 
       references users;

    alter table if exists transactions 
       add constraint FK61bok0mxn7uxslh1ehubsqxjk 
       foreign key (purchase_code_id) 
       references purchase_codes;

    alter table if exists transactions 
       add constraint FKqwv7rmvc8va8rep7piikrojds 
       foreign key (user_id) 
       references users;
