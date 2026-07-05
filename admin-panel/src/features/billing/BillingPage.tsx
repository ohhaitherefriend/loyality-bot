import { useEffect, useCallback } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  CreditCard,
  Clock,
  CheckCircle,
  AlertTriangle,
  Sparkles,
  Shield,
  Zap,
  Crown,
} from 'lucide-react'

import { api } from '@/api/client'
import { useAuthStore } from '@/lib/auth-store'
import { useShopStore } from '@/lib/store'
import { toast } from '@/components/ui/use-toast'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from '@/components/ui/card'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Separator } from '@/components/ui/separator'

declare global {
  interface Window {
    cp: {
      CloudPayments: new () => {
        pay: (
          method: string,
          options: Record<string, unknown>,
          callbacks: {
            onSuccess?: (options: unknown) => void
            onFail?: (reason: string, options: unknown) => void
            onComplete?: (paymentResult: unknown, options: unknown) => void
          }
        ) => void
      }
    }
  }
}

export function BillingPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { shops } = useAuthStore()
  const { shopId } = useShopStore()

  const currentShopId = shopId || shops[0]?.shopId

  const { data: subscription, isLoading } = useQuery({
    queryKey: ['subscription', currentShopId],
    queryFn: () => currentShopId ? api.getSubscription(currentShopId) : null,
    enabled: !!currentShopId,
  })

  const { data: plans } = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.getPlans(),
  })

  useEffect(() => {
    if (!currentShopId && shops.length === 0) {
      navigate('/onboarding')
    }
  }, [currentShopId, shops, navigate])

  const handlePayment = useCallback(async (planCode: string) => {
    if (!currentShopId) return

    try {
      const config = await api.getPaymentConfig(currentShopId, planCode)

      if (!config.publicId) {
        toast({
          title: 'Ошибка',
          description: 'Платёжная система не настроена. Обратитесь в поддержку.',
          variant: 'destructive',
        })
        return
      }

      const widget = new window.cp.CloudPayments()

      const recurrentData: Record<string, unknown> = {}
      if (config.recurrentInterval) {
        recurrentData.cloudPayments = {
          recurrent: {
            interval: config.recurrentInterval,
            period: config.recurrentPeriod,
          },
        }
      }

      widget.pay('charge', {
        publicId: config.publicId,
        description: config.description,
        amount: config.amount,
        currency: config.currency,
        accountId: config.accountId,
        invoiceId: config.invoiceId,
        skin: 'modern',
        data: recurrentData,
      }, {
        onSuccess: async () => {
          try {
            await api.confirmPayment(currentShopId!, planCode)
          } catch (e) {
            // webhook мог уже активировать подписку
          }
          queryClient.invalidateQueries({ queryKey: ['subscription'] })
          toast({
            title: 'Оплата прошла успешно!',
            description: 'Подписка активирована. Спасибо!',
          })
        },
        onFail: (reason: string) => {
          toast({
            title: 'Оплата не прошла',
            description: reason || 'Попробуйте ещё раз или используйте другую карту.',
            variant: 'destructive',
          })
        },
      })
    } catch (e) {
      toast({
        title: 'Ошибка',
        description: 'Не удалось подготовить платёж. Попробуйте позже.',
        variant: 'destructive',
      })
    }
  }, [currentShopId, queryClient])

  const isTrialing = subscription?.status === 'TRIALING'
  const isActive = subscription?.status === 'ACTIVE'
  const isExpired = subscription?.status === 'EXPIRED'
  const isFreeForever = subscription?.freeForever
  const daysLeft = subscription?.daysLeft || 0

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '—'
    return new Date(dateStr).toLocaleDateString('ru-RU', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    })
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Биллинг и подписка</h1>
        <p className="text-muted-foreground">
          Управление тарифным планом и оплатой
        </p>
      </div>

      {/* Current Subscription */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CreditCard className="h-5 w-5" />
            Текущая подписка
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="space-y-4">
              <Skeleton className="h-8 w-32" />
              <Skeleton className="h-4 w-48" />
            </div>
          ) : subscription ? (
            <div className="space-y-6">
              {/* Status */}
              <div className="flex items-center gap-4">
                {isFreeForever && (
                  <div className="flex items-center gap-2 text-2xl font-bold text-primary">
                    <Crown className="h-8 w-8" />
                    Бесплатный план
                  </div>
                )}
                {!isFreeForever && isTrialing && (
                  <motion.div
                    initial={{ scale: 0.9 }}
                    animate={{ scale: 1 }}
                    className="flex items-center gap-2 text-2xl font-bold"
                  >
                    <Clock className="h-8 w-8 text-blue-500" />
                    Пробный период
                  </motion.div>
                )}
                {!isFreeForever && isActive && (
                  <div className="flex items-center gap-2 text-2xl font-bold text-success">
                    <CheckCircle className="h-8 w-8" />
                    Активна
                  </div>
                )}
                {!isFreeForever && isExpired && (
                  <div className="flex items-center gap-2 text-2xl font-bold text-destructive">
                    <AlertTriangle className="h-8 w-8" />
                    Истекла
                  </div>
                )}
              </div>

              {/* Details */}
              <div className="grid gap-4 md:grid-cols-3">
                <div className="rounded-lg border p-4">
                  <p className="text-sm text-muted-foreground">План</p>
                  <p className="text-lg font-semibold">
                    {isFreeForever ? 'Бесплатный' :
                     subscription.planCode === 'FREE_TRIAL' ? 'Пробный' :
                     subscription.planCode === 'BASIC_MONTHLY' ? 'Базовый (месяц)' :
                     subscription.planCode === 'BASIC_YEARLY' ? 'Базовый (год)' :
                     subscription.planCode || 'Не определён'}
                  </p>
                </div>

                {!isFreeForever && (isTrialing || isActive) && (
                  <div className="rounded-lg border p-4">
                    <p className="text-sm text-muted-foreground">Осталось дней</p>
                    <p className="text-lg font-semibold">{daysLeft}</p>
                  </div>
                )}

                {!isFreeForever && (
                  <div className="rounded-lg border p-4">
                    <p className="text-sm text-muted-foreground">
                      {isTrialing ? 'Trial до' : 'Действует до'}
                    </p>
                    <p className="text-lg font-semibold">
                      {formatDate(isTrialing ? subscription.trialEndAt : subscription.currentPeriodEndAt)}
                    </p>
                  </div>
                )}

                {isFreeForever && (
                  <div className="rounded-lg border p-4">
                    <p className="text-sm text-muted-foreground">Срок действия</p>
                    <p className="text-lg font-semibold text-primary">Бессрочно</p>
                  </div>
                )}
              </div>

              {/* Expired Notice */}
              {!isFreeForever && isExpired && (
                <Alert variant="destructive">
                  <AlertTriangle className="h-4 w-4" />
                  <AlertTitle>Подписка истекла</AlertTitle>
                  <AlertDescription>
                    Бот приостановлен. Оплатите подписку, чтобы восстановить работу.
                  </AlertDescription>
                </Alert>
              )}
            </div>
          ) : (
            <p className="text-muted-foreground">Подписка не найдена</p>
          )}
        </CardContent>
      </Card>

      {/* Plans */}
      {!isFreeForever && (
        <div>
          <h2 className="text-xl font-semibold mb-4">Тарифные планы</h2>
          <div className="grid gap-4 md:grid-cols-2">
            {plans?.filter(p => p.code !== 'FREE_TRIAL').map((plan) => (
              <Card key={plan.code} className="relative">
                {plan.code === 'BASIC_MONTHLY' && (
                  <Badge className="absolute -top-2 -right-2">
                    <Sparkles className="mr-1 h-3 w-3" />
                    Популярный
                  </Badge>
                )}
                <CardHeader>
                  <CardTitle>{plan.name}</CardTitle>
                  <CardDescription>{plan.description}</CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="text-3xl font-bold">
                    {plan.priceAmount === 0 ? 'Бесплатно' : `${(plan.priceAmount / 100).toLocaleString()} ₽`}
                    {plan.priceAmount > 0 && (
                      <span className="text-sm font-normal text-muted-foreground">
                        /{plan.periodDays === 30 ? 'мес' : plan.periodDays >= 365 ? 'год' : `${plan.periodDays} дн.`}
                      </span>
                    )}
                  </div>

                  <Separator className="my-4" />

                  <ul className="space-y-2 text-sm">
                    <li className="flex items-center gap-2">
                      <CheckCircle className="h-4 w-4 text-success" />
                      Неограниченные клиенты
                    </li>
                    <li className="flex items-center gap-2">
                      <CheckCircle className="h-4 w-4 text-success" />
                      Штампы и награды
                    </li>
                    <li className="flex items-center gap-2">
                      <CheckCircle className="h-4 w-4 text-success" />
                      Накопительные скидки
                    </li>
                    <li className="flex items-center gap-2">
                      <CheckCircle className="h-4 w-4 text-success" />
                      Fast Checkout
                    </li>
                    <li className="flex items-center gap-2">
                      <CheckCircle className="h-4 w-4 text-success" />
                      Автосообщения
                    </li>
                    {plan.periodDays >= 365 && (
                      <li className="flex items-center gap-2">
                        <Zap className="h-4 w-4 text-primary" />
                        Экономия 17%
                      </li>
                    )}
                  </ul>
                </CardContent>
                <CardFooter>
                  {(() => {
                    const isCurrent = subscription?.planCode === plan.code && isActive
                    const hasYearly = subscription?.planCode === 'BASIC_YEARLY' && isActive
                    const isMonthlyWhenYearly = plan.code === 'BASIC_MONTHLY' && hasYearly
                    const blocked = isCurrent || isMonthlyWhenYearly

                    return (
                      <Button
                        className="w-full"
                        variant={blocked ? 'secondary' : 'default'}
                        disabled={blocked}
                        onClick={() => handlePayment(plan.code)}
                      >
                        {isCurrent ? (
                          'Текущий план'
                        ) : isMonthlyWhenYearly ? (
                          'Годовая подписка активна'
                        ) : (
                          <>
                            <CreditCard className="mr-2 h-4 w-4" />
                            Оплатить
                          </>
                        )}
                      </Button>
                    )
                  })()}
                </CardFooter>
              </Card>
            ))}
          </div>
        </div>
      )}

      {/* FAQ */}
      <Card>
        <CardHeader>
          <CardTitle>Часто задаваемые вопросы</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <h4 className="font-medium">Что происходит когда trial заканчивается?</h4>
            <p className="text-sm text-muted-foreground">
              Бот приостанавливается до оплаты подписки. Все данные клиентов сохраняются.
            </p>
          </div>
          <Separator />
          <div>
            <h4 className="font-medium">Как работает автопродление?</h4>
            <p className="text-sm text-muted-foreground">
              После оплаты подписка автоматически продлевается каждый месяц (или год).
              Отменить автопродление можно на{' '}
              <a
                href="https://my.cloudpayments.ru/unsubscribe"
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary underline"
              >
                my.cloudpayments.ru
              </a>.
            </p>
          </div>
          <Separator />
          <div>
            <h4 className="font-medium">Какие способы оплаты принимаются?</h4>
            <p className="text-sm text-muted-foreground">
              Visa, MasterCard, МИР, Apple Pay, Google Pay. Оплата защищена 3-D Secure.
            </p>
          </div>
          <Separator />
          <div>
            <h4 className="font-medium">Безопасно ли это?</h4>
            <p className="text-sm text-muted-foreground">
              Да. Оплата обрабатывается через CloudPayments — сертифицированную платёжную систему.
              Мы не храним данные вашей карты.
            </p>
          </div>
        </CardContent>
      </Card>

      {/* Security Badge */}
      <div className="flex flex-col items-center gap-2 py-4 text-sm text-muted-foreground">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4" />
          <span>Безопасная оплата через CloudPayments</span>
        </div>
        <p>
          Оплачивая, вы принимаете условия{' '}
          <Link to="/offer" className="text-primary underline underline-offset-2">
            оферты
          </Link>
        </p>
      </div>
    </div>
  )
}
