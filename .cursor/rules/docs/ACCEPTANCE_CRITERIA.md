# MVP Acceptance Criteria

## Happy path

- Реальное письмо с допустимым Excel-вложением обнаруживается автоматически.
- Вложение превращается ровно в один import batch.
- Известный layout разбирается без AI; неизвестный безопасно определяется AI или отправляется в quarantine.
- Позиции нормализуются и сопоставляются без выдуманных product ID.
- Успешный batch проходит до `APPLIED` без кнопок Review/Apply.
- Supplier price 1000 ₽ при commission 30% даёт public price 1300 ₽ согласно rounding policy.
- Валидный новый товар автоматически появляется на storefront.

## Snapshot reconciliation

- Товар, отсутствующий в следующем успешном `FULL` snapshot, исчезает с сайта.
- Product физически остаётся в БД, его order history и learned links сохраняются.
- При повторном появлении offer товар автоматически возвращается.
- Если offer исчез у одного поставщика, но остался у другого, товар остаётся на сайте.
- Public price пересчитывается по оставшимся активным offers.
- Snapshot одной категории/склада не деактивирует товары другого scope.
- `DELTA` импорт не деактивирует отсутствующие позиции.

## Safety

- Пустой, обрезанный или изменивший структуру файл не снимает ассортимент.
- Резкое падение row count или valid-price ratio переводит batch в quarantine до reconciliation.
- `50 ml` не auto-match с `100 ml`.
- Tester не auto-match с retail, set — с одиночным товаром, разные shades — друг с другом.
- DeepSeek не может выбрать candidate ID вне списка backend.
- AI timeout не теряет email и не блокирует другие attachments.

## Idempotency and security

- Повторный poll или повторное вложение не дублируют batch/offers.
- Повторный и конкурентный apply не искажают результат.
- Все данные shop-scoped.
- Mailbox/API secrets не возвращаются frontend и не попадают в логи.
- Storefront API не раскрывает supplier prices, raw rows и AI audit.

## Operations

- Dashboard показывает automation rate, mailbox health, added/updated/priceChanged/removed/reactivated и exceptions.
- Оператор открывает только исключения, а не каждый успешный файл.
- Прерванная background job безопасно продолжает работу после рестарта.

