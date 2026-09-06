# Зафиксированные решения

## D-001 — Email-first

Почта реализуется раньше ручной загрузки. Нормальная работа системы не требует загрузки файла через browser.

## D-002 — Модульный монолит

MVP остаётся в существующем Spring Boot приложении и React/Vite admin panel. Kafka, RabbitMQ, Kubernetes и отдельный AI-worker не добавляются без измеренной необходимости.

## D-003 — Canonical product и supplier offer разделены

`Product` — идентичность товара на сайте. `SupplierOffer` — цена, остаток и доступность конкретного поставщика. Один товар может иметь несколько offers.

## D-004 — Snapshot reconciliation

Успешный `FULL` импорт является authoritative snapshot только в пределах `snapshotScope`. Offers, не встреченные в новом batch этого scope, деактивируются. `DELTA` импорт ничего не деактивирует.

## D-005 — Не удалять Product физически

Когда активных offers нет, товар исчезает из storefront API, но остаётся в БД. Сохраняются order history, aliases и supplier links.

## D-006 — Автоматическое возвращение

Повторное появление offer реактивирует товар без нового ручного matching.

## D-007 — Процентная цена

```text
sitePrice = moneyRound(supplierPrice * (1 + commissionPercent / 100))
```

Используются `BigDecimal` и versioned rounding policy. Default commission задаётся на уровне магазина, supplier source может иметь override.

## D-008 — Несколько поставщиков

Товар остаётся на сайте, пока активен хотя бы один offer. Default public price — минимальный `calculatedSitePrice` среди активных offers. Стратегия конфигурируема.

## D-009 — AI ограничен кандидатами

DeepSeek не придумывает product ID. Matcher выбирает только candidate ID, переданный backend, либо `NO_MATCH`. Ответ проходит schema, membership и critical-conflict validation.

## D-010 — AI определяет layout

Для неизвестной структуры DeepSeek предлагает типизированный mapping. Backend валидирует JSON Schema и автоматический preview. Schema drift с низкой уверенностью переводит batch в quarantine.

## D-011 — Исключения вместо обязательного review

EXACT, LEARNED и безопасный AI match могут auto-apply. Человек работает с `NEEDS_REVIEW`, `INVALID`, `QUARANTINED` и suspicious batch alerts.

## D-012 — Защита от массового снятия

Деактивация отсутствующих offers выполняется только после обязательных batch guards: required columns, valid-row ratio, row-count collapse, duplicate explosion, schema drift и аномальные price/stock deltas.

## D-013 — Идемпотентность

Повторный mailbox poll, одинаковое вложение и повторный apply не создают дубликаты и не изменяют итог повторно.

## D-014 — Ручная загрузка поздняя

Manual upload реализуется в Prompt 08 и использует тот же `AttachmentIngestionService`, что email. Отдельного pipeline быть не должно.

## D-015 — Storefront isolation

Public API не возвращает supplier price, исходные строки, AI prompts/decisions, внутренние candidates и секреты.

