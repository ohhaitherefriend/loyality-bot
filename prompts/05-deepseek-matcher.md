# Prompt 05 — DeepSeek matcher и automation gates

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Добавь AiCatalogMatcher и автоматические row-level decision gates.

DeepSeek получает row и максимум 10 реальных candidates. Strict JSON может
выбрать только candidateId из списка либо NO_MATCH. Backend проверяет schema,
candidate membership, required attributes и critical conflicts. Итоговый score
рассчитывает приложение из deterministic signals + AI result; model confidence
не может быть единственным основанием.

EXACT/LEARNED и сильный AI_MATCH без conflicts -> AUTO_APPROVED. Слабый match,
NO_MATCH, invalid response или timeout -> NEEDS_REVIEW, но batch продолжает
обрабатываться. Thresholds versioned per SupplierSource; поддержи shadowMode и
autoApply flags.

Валидная строка без существующего candidate может стать NEW_PRODUCT: AI
нормализует только разрешённые поля, backend валидирует обязательные данные.
Полностью описанная безопасная NEW_PRODUCT допускается к AUTO_APPROVED;
неполная или противоречивая остаётся исключением.

Добавь retry/backoff только для retryable status, rate limit, circuit breaker,
tokens/latency/promptVersion audit и redacted logs. Tests: invented ID,
malformed JSON, conflict, boundary thresholds, disabled provider, shadow mode.
```

