# Prompt 02 — email-first ingestion

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Реализуй первый настоящий vertical slice: mailbox -> attachment -> STORED batch.

Создай MailboxClient interface и IMAP implementation. Поддержи OAuth2 или app
password для первого фактического провайдера. Secret шифруй AES-GCM/envelope с
master key из env либо используй существующий encryption service; никогда не
возвращай и не логируй plaintext.

SupplierSource: folder, sender/domain allowlist, subject/filename regex,
snapshotMode/scope, commission percent, price strategy, enabled. Polling каждые
5 минут configurable. Не mark read, delete или move письмо. Cursor:
mailbox + UIDVALIDITY + UID + attachment index/hash. Каждое допустимое xlsx/xls
attachment передай в AttachmentIngestionService.

Добавь scheduler locking/claims для нескольких replicas, test connection и
manual poll endpoints, mailbox/source minimal UI с health/last poll. Tests:
duplicate poll, multiple emails/attachments, reconnect/UIDVALIDITY, wrong
sender, unsupported type, oversize, secret redaction. Не добавляй parser,
DeepSeek или manual file upload.
```

