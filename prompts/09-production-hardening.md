# Prompt 09 — production hardening

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Проведи hardening без новых features.

Проверь Flyway production + ddl-auto validate/none; disabled AI/mailbox startup;
tenant/auth isolation; IMAP reconnect; scheduler single-claim; interrupted job
recovery; ZIP/XML/file limits; storage access/retention; secret redaction;
prompt injection через cell content; DB indexes/query plans; bounded AI/job
concurrency; metrics/alerts; backups; admin/public API separation.

Добавь E2E happy path:
email -> attachment -> AI layout -> parse -> match -> gates -> automatic apply
-> commission applied -> new product appears on storefront.

Добавь snapshot E2E: следующий FULL snapshot не содержит product -> его offer
deactivated и product исчезает со storefront; если другой supplier offer active,
product остаётся; при повторном появлении product возвращается автоматически.

Добавь exception E2E: schema drift/anomaly -> quarantine -> operator decision ->
resume. Обнови deployment docs и sample env без секретов.
```

