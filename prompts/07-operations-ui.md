# Prompt 07 — operations dashboard и исключения

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Реализуй web UI как панель контроля автоматики, а не ручной импортёр.

Dashboard: automation rate, processed emails/files/rows, mailbox health,
added/updated/priceChanged/removed/reactivated товары, running/failed/quarantined
batches и supplier exception rates. Главный экран не требует открытия каждого
успешного batch.

Единая exception queue: NEEDS_REVIEW, INVALID, QUARANTINED, suspicious price/
row-count/schema changes. Detail показывает raw/normalized row, candidates,
score, conflicts, AI/model/prompt audit и batch diff. Actions: MATCH, NO_MATCH,
CREATE_PRODUCT, IGNORE, SET_MANUAL_HIDDEN, approve layout, resume batch.
Используй optimistic locking, audit reviewer/time/previous decision; bulk action
только для совместимых rows.

Добавь shop-scoped APIs, pagination/filtering и frontend tests. Успешный
auto-applied batch доступен как audit/report. Manual upload не добавляй.
```

