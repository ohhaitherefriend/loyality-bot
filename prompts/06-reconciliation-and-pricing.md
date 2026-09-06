# Prompt 06 — automatic pricing, reconciliation и apply

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Реализуй end-to-end автоматическую синхронизацию ассортимента без обязательной
кнопки пользователя.

Перед apply выполни batch guards: required columns, valid-row ratio, row-count
collapse, schema drift, duplicate explosion, price/stock deltas. Если
source.autoApply=true, нет blocking exceptions и guards пройдены, batch
автоматически APPLYING -> APPLIED. Иначе NEEDS_ATTENTION/QUARANTINED.

Для каждой актуальной строки upsert SupplierOffer: supplierPrice,
appliedCommissionPercent, calculatedSitePrice = moneyRound(supplierPrice *
(1 + commissionPercent/100)), stock, active=true, lastSeenBatchId=current.
Используй BigDecimal и versioned pricing/rounding policy.

После полностью успешного FULL snapshot деактивируй offers в том же supplier +
snapshotScope, которые не были seen в current batch. DELTA ничего не
деактивирует. Никогда не reconcile вне scope текущего файла.

Пересчитай storefront projection:
- один или больше active offers и manualHidden=false -> product виден;
- ноль active offers -> product не возвращается storefront API;
- Product физически не удаляется, order history и links сохраняются;
- reappeared offer автоматически возвращает product на сайт;
- при нескольких offers default public price = минимальный calculatedSitePrice.

Безопасные NEW_PRODUCT создаются и появляются автоматически. manualHidden —
явный override оператора, который sync не снимает.

Сохраняй SupplierProductLink после безопасного automatic/manual решения.
CatalogAlias только с ограниченным scope. Добавь double/concurrent apply,
rollback, FULL-vs-DELTA, scope isolation, commission+rounding, price change,
disappearance/reactivation и multi-supplier availability tests. Anomalous
row-count/price drop обязан quarantine batch до любых деактиваций. Реализуй
automatic resume/recovery после рестарта.
```

