# Prompt 04 — normalization и deterministic candidates

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Реализуй автоматический PARSING -> NORMALIZING -> candidate search без ручного
экрана.

Извлекай brand, line/name, variant, volume+unit, concentration, shade,
tester/set markers, SKU, barcode, supplierPrice, stock. Сохраняй original и
normalized JSON. Порядок matching: SupplierProductLink, exact barcode, safe
fingerprint, затем PostgreSQL pg_trgm top-10 candidates с explainable score.

Critical conflicts volume/unit, concentration, shade, set composition и
tester/retail запрещают auto-match. Channel нельзя глобально заменять на Chanel;
это только scoped search signal. Добавь tests Chanel/Шанель/Channel, 50/100 ml,
tester/set, duplicate barcode и cross-shop isolation. DeepSeek matcher пока не
добавляй.
```

