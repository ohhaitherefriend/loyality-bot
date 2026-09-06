# Prompt 10 — финальная ревизия

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Не добавляй features. Проведи senior Java/React review supplier automation.

Ищи silent corruption, пропущенные письма, duplicate processing, cross-tenant
access, non-idempotent transitions, scheduler races, locale/money bugs, N+1,
unbounded files/queries, prompt injection, invented AI IDs, false auto-apply,
ошибочное массовое снятие offers, secret leakage и public/admin DTO leaks.

Сначала findings по severity с точными файлами. Исправь Critical/High и
безопасные Medium в scope. Запусти полный backend/frontend/E2E suite. Создай
docs/SUPPLIER_IMPORT_RELEASE_CHECKLIST.md, включив automation rate,
shadow-mode acceptance и безопасный FULL snapshot reconciliation.
```

