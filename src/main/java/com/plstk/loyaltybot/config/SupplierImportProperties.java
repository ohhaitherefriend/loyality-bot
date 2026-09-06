package com.plstk.loyaltybot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация automation foundation (Prompt 01) + email ingestion (Prompt 02) + AI layout
 * detection/parser (Prompt 03). Mailbox/AI-специфичные свойства должны иметь безопасные
 * defaults: приложение обязано стартовать даже если DeepSeek не сконфигурирован (см.
 * {@code DisabledSpreadsheetLayoutDetector}).
 */
@Data
@ConfigurationProperties(prefix = "supplier-import")
public class SupplierImportProperties {

    private Storage storage = new Storage();

    private Job job = new Job();

    private Ai ai = new Ai();

    private Parser parser = new Parser();

    private Matching matching = new Matching();

    private Pricing pricing = new Pricing();

    private Reconciliation reconciliation = new Reconciliation();

    @Data
    public static class Storage {
        /** Provider-neutral local root; production может быть заменена S3-compatible реализацией. */
        private String basePath = "./data/import-files";
        /** Максимальный размер вложения, принимаемый ingestion service. */
        private long maxFileSizeBytes = 30L * 1024 * 1024;
    }

    @Data
    public static class Job {
        /** Default длительность lease для DB-backed claim/lease. */
        private long defaultLeaseSeconds = 300;

        /** Интервал между запусками mailbox polling job (Prompt 02), configurable. */
        private long mailboxPollIntervalMs = 300_000L;

        /** Задержка перед первым запуском mailbox polling job после старта приложения. */
        private long mailboxPollInitialDelayMs = 15_000L;

        /**
         * Верхняя граница числа новых сообщений, полностью разбираемых (MIME-парсинг вложений) за
         * один вызов {@code fetchNewMessages}. Без этого лимита ящик с большим backlog'ом (долгий
         * downtime, повторное включение отключённого mailbox) заставил бы один poll-цикл держать в
         * памяти и синхронно парсить потенциально тысячи сообщений в рамках одного claim/lease,
         * усугубляя риск того, что обработка переживёт свой lease (см. {@code ClaimHeartbeatSweeper}
         * — heartbeat смягчает, но не убирает сам факт долгого единичного poll'а). Курсор при этом
         * продвигается только до последнего фактически обработанного сообщения — остаток backlog'а
         * подхватывается следующими poll-циклами, не теряется.
         */
        private int mailboxPollMaxMessagesPerCycle = 200;

        /** Интервал между запусками автоматического STORED -> PARSING job (Prompt 03). */
        private long parsingIntervalMs = 30_000L;

        /** Задержка перед первым запуском parsing job после старта приложения. */
        private long parsingInitialDelayMs = 20_000L;

        /** Lease на обработку одного batch (jobKey = batchId), отдельно от mailbox lease. */
        private long parsingLeaseSeconds = 300;

        /** Интервал между запусками автоматического NORMALIZING -> MATCHING job (Prompt 04). */
        private long normalizingIntervalMs = 30_000L;

        /** Задержка перед первым запуском normalizing job после старта приложения. */
        private long normalizingInitialDelayMs = 25_000L;

        /** Lease на обработку одного batch (jobKey = batchId) в normalizing job. */
        private long normalizingLeaseSeconds = 300;

        /** Интервал между запусками автоматического MATCHING -> VALIDATING job (Prompt 05). */
        private long matchingIntervalMs = 30_000L;

        /** Задержка перед первым запуском matching job после старта приложения. */
        private long matchingInitialDelayMs = 35_000L;

        /** Lease на обработку одного batch (jobKey = batchId) в matching job. */
        private long matchingLeaseSeconds = 300;

        /** Интервал между запусками автоматического VALIDATING -> AUTO_APPROVED/NEEDS_ATTENTION/QUARANTINED job (Prompt 06). */
        private long validationIntervalMs = 30_000L;

        /** Задержка перед первым запуском validation (apply-guard) job после старта приложения. */
        private long validationInitialDelayMs = 40_000L;

        /** Lease на обработку одного batch (jobKey = batchId) в validation job. */
        private long validationLeaseSeconds = 300;

        /**
         * Интервал между запусками automatic apply job (Prompt 06): AUTO_APPROVED/APPROVED ->
         * APPLYING -> APPLIED, а также recovery уже застрявших в APPLYING batch (например, после
         * рестарта приложения посередине предыдущего apply).
         */
        private long applyIntervalMs = 30_000L;

        /** Задержка перед первым запуском apply job после старта приложения. */
        private long applyInitialDelayMs = 45_000L;

        /** Lease на обработку одного batch (jobKey = batchId) в apply job. */
        private long applyLeaseSeconds = 600;
    }

    @Data
    public static class Ai {
        private DeepSeek deepseek = new DeepSeek();
    }

    /** provider-neutral: baseUrl/apiKey/model/timeouts только из config/env, никогда в коде. */
    @Data
    public static class DeepSeek {
        private String apiKey = "";
        private String model = "deepseek-chat";
        private String baseUrl = "https://api.deepseek.com";
        /**
         * NOT currently wired to any per-request HTTP timeout - {@code DeepSeekCatalogMatcher}/
         * {@code DeepSeekSpreadsheetLayoutDetector} both use the single shared {@code RestTemplate}
         * bean ({@code RestTemplateConfig}, fixed 10s connect / 30s read timeout) that every other
         * outbound HTTP integration in this app also shares (Telegram, image search, payments).
         * Changing that shared client's timeout per-caller is out of scope for a supplier-import-only
         * fix; this field is kept for forward compatibility (a future dedicated DeepSeek
         * {@code RestTemplate}) but setting it today has no effect - do not rely on it to bound a
         * slow DeepSeek call, the shared 30s read timeout is what actually applies.
         */
        private int timeoutMs = 20000;
        /** Повторы только на retryable ошибки (timeout/429/5xx), не на malformed JSON. */
        private int maxRetries = 2;
        private long retryBackoffMs = 500;
    }

    /** Safe Apache POI parsing limits, shared by AI sampling, layout preview and full parse. */
    @Data
    public static class Parser {
        private int maxSheets = 10;
        private int maxRowsPerSheet = 20000;
        private int maxColumns = 200;
        private int maxCellLength = 4000;

        /** Сколько строк (после header) отправлять модели как sample для layout detection. */
        private int aiSampleRows = 15;
        private int aiSampleColumns = 30;
        private int aiSampleCellMaxLength = 160;

        /** Сколько строк парсить в automatic preview новой AI-сгенерированной rule. */
        private int previewRowCount = 30;
        /** Минимальная доля валидных строк preview, чтобы rule стала ACTIVE, а не QUARANTINED. */
        private double previewMinValidRowRatio = 0.5;

        /**
         * Минимальная доля валидных строк (а значит и валидных цен, т.к. price обязателен для
         * валидности строки) при полном parse. Если ratio упал ниже — весь batch quarantined и
         * не имеет права запускать assortment reconciliation (Prompt 06).
         */
        private double finalMinValidRowRatio = 0.5;
    }

    /**
     * Deterministic candidate search (Prompt 04). {@code pgTrgmEnabled} is a separate opt-in from
     * the {@code prod} Spring profile on purpose: Flyway stays disabled everywhere (see V17 note),
     * so a production deployment cannot be assumed to already have the {@code pg_trgm} extension and
     * trigram indexes from {@code V19} applied. Default (disabled) uses
     * {@code SimpleProductCandidateFetcher}, portable on H2 and PostgreSQL alike.
     */
    @Data
    public static class Matching {
        /** How many products SimpleProductCandidateFetcher loads per shop before Java-side scoring. */
        private int candidateFetchLimit = 300;

        /** How many top-scored candidates are persisted as explainable fuzzy candidates per row. */
        private int maxCandidates = 10;

        /** Below this trigram similarity a candidate is not worth keeping at all. */
        private double minSimilarityThreshold = 0.15;

        /** Opt-in once pg_trgm extension/index (V19) is confirmed applied on the target database. */
        private boolean pgTrgmEnabled = false;

        /**
         * Global default deterministic {@code ScoredCandidate.totalScore} floor an AI-selected
         * candidate must clear to auto-approve (Prompt 05). Overridable per {@code SupplierSource}
         * via {@code aiAutoApproveMinScoreOverride}. Model confidence alone can never substitute for
         * this - see {@code ImportBatchMatchingService}.
         */
        private double aiAutoApproveMinScore = 0.80;

        /** Global default AI confidence floor to auto-approve (Prompt 05). Overridable per source. */
        private double aiMinConfidence = 0.55;

        /**
         * Whether a row with zero catalog candidates (i.e. eligible for {@code NEW_PRODUCT}) must
         * have a non-blank normalized brand to be considered safe enough for automatic approval.
         * {@code rawName}/{@code supplierPrice}/an identifier are always already guaranteed present
         * by {@code SpreadsheetParser} row validity - brand is the only additional completeness gate.
         */
        private boolean newProductRequireBrand = true;
    }

    /**
     * Percentage-based public pricing (Prompt 06, D-007): {@code sitePrice = moneyRound(supplierPrice
     * * (1 + commissionPercent / 100))}. {@code defaultCommissionPercent} is the last-resort fallback
     * when neither {@code SupplierSource.commissionPercentOverride} nor
     * {@code ShopSettings.defaultCommissionPercent} is configured - see {@code PricingService}.
     */
    @Data
    public static class Pricing {
        private double defaultCommissionPercent = 30.0;
    }

    /**
     * Batch-level apply guards (Prompt 06, D-012, docs/ARCHITECTURE.md §14.2): evaluated once a
     * batch reaches {@code VALIDATING}, before any {@code SupplierOffer} is touched. A guard
     * failure quarantines the whole batch - an anomalous/corrupted file must never be allowed to
     * mass-deactivate or mass-reprice the assortment.
     */
    @Data
    public static class Reconciliation {

        /**
         * A FULL-snapshot batch's valid row count must be at least this fraction of the previous
         * successfully APPLIED batch's valid row count for the same supplier+snapshotScope, or the
         * batch is quarantined instead of being allowed to deactivate "missing" offers. Ignored (no
         * guard) when there is no previous APPLIED batch to compare against, and for DELTA batches
         * (which never deactivate anything regardless).
         */
        private double rowCountCollapseMinRatio = 0.5;

        /**
         * Above this fraction of appliable rows sharing a duplicate externalSku/barcode within one
         * batch, the batch is quarantined as a likely corrupted parse (merged cells, repeated
         * header, wrong sheet range) rather than applied as-is.
         */
        private double duplicateIdentifierMaxRatio = 0.2;

        /**
         * A single row's price change ratio (|newPrice - oldPrice| / oldPrice, against the existing
         * active {@code SupplierOffer} for the same product) above this value counts as one
         * anomalous price delta for {@link #priceDeltaMaxAnomalousRowRatio} below.
         */
        private double priceDeltaWarnRatio = 0.5;

        /**
         * Above this fraction of rows updating an existing offer showing an anomalous price delta
         * (see {@link #priceDeltaWarnRatio}), the whole batch is quarantined instead of applying a
         * possibly-corrupted price column.
         */
        private double priceDeltaMaxAnomalousRowRatio = 0.3;
    }
}
