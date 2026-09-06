# Prompt 01 — automation foundation

Перед выполнением прочитай `prompts/MASTER_PROMPT.md` и результат Prompt 00.

```text
На основе audit реализуй foundation, но не manual upload.

Зафиксируй ADR: modular monolith, email-first automation, existing products as
canonical catalog, supplier offers/links separated, AI provider-neutral,
manual upload only after complete email+AI pipeline.

Добавь Flyway/JPA domain для Supplier, SupplierSource, MailboxConnection,
MailboxCursor, ImportRuleVersion, ImportFile, ImportBatch, ImportRow,
SupplierProductLink, SupplierOffer, MatchDecision. Адаптируй имена и packages
к conventions реального проекта. Добавь shop-scoped indexes, FK/unique
constraints, JSONB raw/normalized data и NUMERIC(19,2).

SupplierSource поддерживает snapshotMode FULL/DELTA, snapshotScope,
commissionPercent override, publicPriceStrategy, rounding policy, shadowMode
и autoApply. SupplierOffer хранит supplierPrice, appliedCommissionPercent,
calculatedSitePrice, active и lastSeenBatchId.

Создай ImportFileStorage и AttachmentIngestionService, принимающий stream +
source metadata, считающий SHA-256, сохраняющий immutable file и идемпотентно
создающий STORED batch. Не создавай HTTP upload endpoint.

Добавь DB-backed claim/lease abstraction для resumable background jobs и tests
на tenant isolation, constraints, duplicate attachment и expired claim.
```

