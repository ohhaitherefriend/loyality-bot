# Prompt 03 — AI layout detection и безопасный parser

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Реализуй автоматический переход STORED -> PARSING без ручной настройки файла.

Добавь provider-neutral AiSpreadsheetLayoutDetector и DeepSeek implementation.
baseUrl/apiKey/model/timeouts из config/env. Модель получает только workbook
metadata, headers и ограниченную выборку строк, возвращает strict JSON rule:
sheets, header/data rows, typed column mapping, skip rules, defaults. Никакого
произвольного SpEL/JavaScript/SQL.

Backend валидирует JSON Schema, обязательные fields, уникальность mapping и
запускает automatic preview на 20-50 строках. Известный layout переиспользует
published rule без AI. Новый валидный layout создаёт immutable rule version;
сомнительный/schema-drift batch -> QUARANTINED.

Apache POI parser: safe ZIP/XML limits, no formula execution, limits на sheets,
rows/cell length, per-row INVALID без падения batch, source sheet/row + raw
JSON, repeat-safe persistence. Добавь fixtures для «Косметика и уход» и
«Парфюмерия» с header row 6, а также изменённого layout. Mock DeepSeek tests:
valid, invented column, malformed/empty JSON, timeout, 429/5xx.

Для FULL snapshot supplier price и устойчивый identifier/name обязательны.
Если price column пропала или valid-price ratio резко упал, quarantine весь
batch: он не имеет права запускать assortment reconciliation.
```

