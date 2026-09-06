import type {
  DecidedBy,
  ImportBatchStatus,
  ImportRowStatus,
  MatchDecisionType,
  RuleVersionStatus,
} from '@/api/types'

type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline' | 'success' | 'warning'

export const ROW_STATUS_LABELS: Record<ImportRowStatus, string> = {
  PENDING: 'В обработке',
  EXACT_MATCH: 'Точное совпадение',
  LEARNED_MATCH: 'Выученное совпадение',
  AI_MATCH: 'Совпадение (AI)',
  AUTO_APPROVED: 'Авто-подтверждено',
  NEEDS_REVIEW: 'Нужна проверка',
  NEW_PRODUCT: 'Новый товар',
  IGNORED: 'Пропущено',
  INVALID: 'Невалидная строка',
  APPROVED: 'Подтверждено',
  APPLIED: 'Применено',
}

export function rowStatusVariant(status: ImportRowStatus): BadgeVariant {
  switch (status) {
    case 'NEEDS_REVIEW':
      return 'warning'
    case 'INVALID':
      return 'destructive'
    case 'APPLIED':
    case 'AUTO_APPROVED':
    case 'EXACT_MATCH':
    case 'LEARNED_MATCH':
    case 'AI_MATCH':
    case 'APPROVED':
      return 'success'
    case 'IGNORED':
      return 'secondary'
    default:
      return 'outline'
  }
}

export const BATCH_STATUS_LABELS: Record<ImportBatchStatus, string> = {
  RECEIVED: 'Получен',
  STORED: 'Сохранён',
  PARSING: 'Разбор файла',
  NORMALIZING: 'Нормализация',
  MATCHING: 'Сопоставление',
  VALIDATING: 'Проверка',
  AUTO_APPROVED: 'Авто-подтверждён',
  NEEDS_ATTENTION: 'Требует внимания',
  APPROVED: 'Подтверждён',
  APPLYING: 'Применяется',
  APPLIED: 'Применён',
  QUARANTINED: 'В карантине',
  FAILED: 'Ошибка',
}

export function batchStatusVariant(status: ImportBatchStatus): BadgeVariant {
  switch (status) {
    case 'FAILED':
    case 'QUARANTINED':
      return 'destructive'
    case 'NEEDS_ATTENTION':
      return 'warning'
    case 'APPLIED':
    case 'AUTO_APPROVED':
      return 'success'
    case 'RECEIVED':
    case 'STORED':
      return 'secondary'
    default:
      return 'outline'
  }
}

const RUNNING_BATCH_STATUSES: ImportBatchStatus[] = [
  'RECEIVED',
  'STORED',
  'PARSING',
  'NORMALIZING',
  'MATCHING',
  'VALIDATING',
  'APPROVED',
  'APPLYING',
]

export function isRunningBatchStatus(status: ImportBatchStatus): boolean {
  return RUNNING_BATCH_STATUSES.includes(status)
}

export const DECISION_TYPE_LABELS: Record<MatchDecisionType, string> = {
  EXACT: 'Точное совпадение',
  LEARNED: 'Выученное правило',
  AI_MATCH: 'AI: совпадение',
  AI_NO_MATCH: 'AI: нет совпадения',
  NEW_PRODUCT: 'Новый товар',
  MANUAL: 'Ручное решение',
  NO_MATCH: 'Нет совпадения',
}

export const DECIDED_BY_LABELS: Record<DecidedBy, string> = {
  SYSTEM: 'Система',
  HUMAN: 'Оператор',
}

export const RULE_VERSION_STATUS_LABELS: Record<RuleVersionStatus, string> = {
  DRAFT: 'Черновик',
  ACTIVE: 'Активна',
  RETIRED: 'Устарела',
}

export function formatMoney(value: number | null | undefined): string {
  if (value == null) return '—'
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value)
}
