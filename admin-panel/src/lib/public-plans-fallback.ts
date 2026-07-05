import type { PlanDto } from '@/api/types'

/**
 * Должны совпадать с дефолтами в SubscriptionService.initDefaultPlans().
 * Используются, если API недоступен без авторизации или запрос не удался.
 */
export const PUBLIC_PLANS_FALLBACK: PlanDto[] = [
  {
    code: 'BASIC_MONTHLY',
    name: 'Базовый (месяц)',
    description: 'Базовый тариф на месяц',
    priceAmount: 99000,
    currency: 'RUB',
    periodDays: 30,
    isStub: true,
  },
  {
    code: 'BASIC_YEARLY',
    name: 'Базовый (год)',
    description: 'Базовый тариф на год',
    priceAmount: 990000,
    currency: 'RUB',
    periodDays: 365,
    isStub: true,
  },
]

export function resolvePaidPlans(plans: PlanDto[] | undefined): PlanDto[] {
  const fromApi = plans?.filter((p) => p.code !== 'FREE_TRIAL') ?? []
  return fromApi.length > 0 ? fromApi : PUBLIC_PLANS_FALLBACK
}
