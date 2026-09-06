# Master prompt

Этот блок действует для каждого этапа. Если содержимое комплекта перенесено в корень репозитория, Cursor также автоматически прочитает `.cursor/rules/zabotik-supplier-sync.mdc`.

```text
Ты работаешь в существующем репозитории loyalty-bot / Zabotik.
Предполагаемый стек — Spring Boot + Java + PostgreSQL + React/Vite, но
фактический код репозитория является единственным источником истины.

Цель продукта: полностью автоматический email-first pipeline. Прайс-листы
приходят на почту в большом количестве, система сама получает вложения,
распознаёт layout, сопоставляет позиции, рассчитывает цену с комиссией,
синхронизирует актуальный ассортимент и применяет безопасные изменения.
Человек работает только с исключениями. Manual upload — поздний fallback.

Перед изменениями:
1. Прочитай AGENTS.md, README, build files, application configs, docker compose,
   последние Flyway migrations и релевантные backend/frontend пакеты.
2. Прочитай docs/ARCHITECTURE.md, docs/PROJECT_CONTEXT.md,
   docs/DECISIONS.md и docs/STATE.md.
3. Найди существующие аналоги controllers/services/entities/components и
   следуй conventions репозитория. Не выдумывай пути и классы, если проект
   устроен иначе.
4. Сначала дай короткий audit и перечень файлов, которые собираешься менять.
   Затем реализуй только задачу текущего prompt.

Общие ограничения:
- multi-tenant isolation по shopId во всех queries и constraints;
- BigDecimal/NUMERIC для денег и процентов;
- canonical Product отдельно от SupplierOffer;
- валидные новые товары автоматически появляются на storefront;
- товар без active offers исчезает со storefront, но не удаляется физически;
- public price = supplier price + versioned commission percent + rounding;
- FULL snapshot reconcile только внутри snapshotScope и только после guards;
- DELTA никогда не деактивирует отсутствующие строки;
- source file и audit immutable; mailbox polling и apply идемпотентны;
- основной happy path не требует Review/Apply;
- model confidence не является единственным auto-apply сигналом;
- critical attribute conflicts всегда блокируют auto-match;
- admin/internal DTO не смешивать с public storefront DTO;
- secrets только encrypted/env/secret storage и никогда не логируются;
- не добавлять Kafka/RabbitMQ/microservices без доказанной необходимости;
- не ослаблять существующую авторизацию;
- номер Flyway migration определить по реальному репозиторию;
- не рефакторить несвязанный код.

После реализации:
1. Запусти релевантные backend/frontend tests, build и lint.
2. Исправь ошибки, вызванные изменениями.
3. Покажи изменённые файлы, команды проверки, результаты и оставшиеся риски.
4. Обнови docs/STATE.md и docs/DECISIONS.md. Не отмечай непроверенное как done.
5. Остановись и не начинай следующий этап.
```

