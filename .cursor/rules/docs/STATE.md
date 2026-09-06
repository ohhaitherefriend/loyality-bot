# Supplier Sync State

Обновляется Cursor после каждого промпта. Не отмечать задачу выполненной без теста или иной указанной проверки.

## Current stage

- Stage: `NOT_STARTED`
- Active prompt: `00-repo-audit`
- Last verified commit: `UNKNOWN`
- Updated at: `NOT_STARTED`

## Stage status

| Prompt | Status | Verification |
| --- | --- | --- |
| 00 Repo audit | pending | `docs/SUPPLIER_IMPORT_AUDIT.md` reviewed |
| 01 Foundation | pending | migrations + repository tests |
| 02 Email ingestion | pending | email attachment creates one STORED batch |
| 03 AI layout/parser | pending | fixtures + mock provider tests |
| 04 Normalization | pending | matching integration tests |
| 05 AI matcher | pending | mock provider + decision gate tests |
| 06 Reconciliation | pending | pricing, disappearance/reactivation E2E |
| 07 Operations UI | pending | frontend build/tests |
| 08 Manual fallback | pending | same-pipeline tests |
| 09 Hardening | pending | complete E2E + deployment checks |
| 10 Final review | pending | release checklist + full suite |

## Verified facts from repository

Заполняется Prompt 00.

## Implemented

Пока ничего.

## Known failures / blockers

Пока не определены.

## Next action

Запустить `prompts/00-repo-audit.md` в отдельном Cursor Agent-чате.

