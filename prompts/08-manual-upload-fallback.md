# Prompt 08 — manual upload fallback

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Только теперь добавь резервную manual upload функцию. Она не должна создавать
отдельную логику импорта.

POST shop-scoped multipart endpoint валидирует type/signature/size и передаёт
stream в тот же AttachmentIngestionService, что mailbox. После этого работают
те же AI layout, parser, matcher, gates, pricing, reconciliation и automatic
apply policies. В UI помести upload в secondary action «Загрузить файл
вручную», не на главный dashboard.

Добавь tests на duplicate email-vs-manual attachment, wrong tenant/type/size и
повторный request. Не меняй основной email-first UX.
```

