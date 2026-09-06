# Prompt 00 — аудит проекта

Перед выполнением прочитай `prompts/MASTER_PROMPT.md`.

```text
Выполни только audit для автоматического email-first импорта supplier Excel.
Код пока не изменяй.

Найди и опиши:
- фактические версии Java/Spring/React/Node;
- auth и shop access;
- Product/commerce model: canonical product, supplier identity/price, public
  price и stock;
- текущие visible/active/delete rules и влияние удаления на order history;
- несколько supplier offers на один product и текущую стратегию выбора цены;
- существующие ProductImportService, Apache POI rules, endpoints и UI;
- последнюю Flyway version и ddl-auto settings во всех profiles;
- catalog/order/image/storefront readiness;
- scheduler/job locking, mail, encryption и storage patterns;
- test/Docker infrastructure;
- незакоммиченные или частично реализованные участки, которые нельзя затереть.

Создай/обнови docs/SUPPLIER_IMPORT_AUDIT.md. Добавь mapping-таблицу reuse /
extend / replace / missing и отдельно опиши кратчайший email-to-stored-batch
vertical slice. Сверь assumptions из docs/PROJECT_CONTEXT.md с кодом и обнови
docs/STATE.md. Никаких production code changes.
```

