import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  CreditCard,
  Clock,
  CheckCircle,
  AlertTriangle,
  Loader2,
  Sparkles,
  Calendar,
  Shield,
  Zap,
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

export function BillingPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { shops } = useAuthStore()
  const { shopId } = useShopStore()
  
  const currentShopId = shopId || shops[0]?.shopId
  
  // Fetch subscription
  const { data: subscription, isLoading } = useQuery({
    queryKey: ['subscription', currentShopId],
    queryFn: () => currentShopId ? api.getSubscription(currentShopId) : null,
    enabled: !!currentShopId,
  })
  
  // Fetch plans
  const { data: plans } = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.getPlans(),
  })
  
  // Activate stub mutation
  const activateMutation = useMutation({
    mutationFn: ({ shopId, planCode }: { shopId: string; planCode: string }) => 
      api.activateStub(shopId, planCode),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['subscription'] })
      toast({
        title: 'Подписка активирована!',
        description: 'Это заглушка — оплата в разработке',
      })
    },
    onError: () => {
      toast({
        title: 'Ошибка',
        description: 'Не удалось активировать подписку',
        variant: 'destructive',
      })
    },
  })
  
  // Extend trial mutation
  const extendMutation = useMutation({
    mutationFn: ({ shopId, days }: { shopId: string; days: number }) => 
      api.extendTrial(shopId, days),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['subscription'] })
      toast({
        title: 'Trial продлён!',
        description: 'Добавлено 7 дней',
      })
    },
  })
  
  // Redirect if no shop
  useEffect(() => {
    if (!currentShopId && shops.length === 0) {
      navigate('/onboarding')
    }
  }, [currentShopId, shops, navigate])
  
  const isTrialing = subscription?.status === 'TRIALING'
  const isActive = subscription?.status === 'ACTIVE'
  const isExpired = subscription?.status === 'EXPIRED'
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
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Биллинг и подписка</h1>
        <p className="text-muted-foreground">
          Управление тарифным планом и оплатой
        </p>
      </div>
      
      {/* Development Notice */}
      <Alert>
        <Shield className="h-4 w-4" />
        <AlertTitle>Оплата в разработке</AlertTitle>
        <AlertDescription>
          Интеграция с платёжными системами находится в разработке. 
          Весь функционал доступен без ограничений.
          Вы можете активировать тестовую подписку для проверки UI.
        </AlertDescription>
      </Alert>
      
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
                {isTrialing && (
                  <motion.div
                    initial={{ scale: 0.9 }}
                    animate={{ scale: 1 }}
                    className="flex items-center gap-2 text-2xl font-bold"
                  >
                    <Clock className="h-8 w-8 text-blue-500" />
                    Пробный период
                  </motion.div>
                )}
                {isActive && (
                  <div className="flex items-center gap-2 text-2xl font-bold text-success">
                    <CheckCircle className="h-8 w-8" />
                    Активна
                  </div>
                )}
                {isExpired && (
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
                    {subscription.planCode === 'FREE_TRIAL' ? 'Пробный' : 
                     subscription.planCode === 'BASIC_MONTHLY' ? 'Базовый (месяц)' :
                     subscription.planCode || 'Не определён'}
                  </p>
                </div>
                
                {isTrialing && (
                  <div className="rounded-lg border p-4">
                    <p className="text-sm text-muted-foreground">Осталось дней</p>
                    <p className="text-lg font-semibold">{daysLeft}</p>
                  </div>
                )}
                
                <div className="rounded-lg border p-4">
                  <p className="text-sm text-muted-foreground">
                    {isTrialing ? 'Trial до' : 'Действует до'}
                  </p>
                  <p className="text-lg font-semibold">
                    {formatDate(subscription.trialEndAt || subscription.currentPeriodEndAt)}
                  </p>
                </div>
                
                <div className="rounded-lg border p-4">
                  <p className="text-sm text-muted-foreground">Enforcement</p>
                  <Badge variant="outline">
                    {subscription.billingEnforcementMode === 'OFF' ? 'Отключено' : subscription.billingEnforcementMode}
                  </Badge>
                </div>
              </div>
              
              {/* Expired Notice */}
              {isExpired && (
                <Alert variant="destructive">
                  <AlertTriangle className="h-4 w-4" />
                  <AlertTitle>Подписка истекла</AlertTitle>
                  <AlertDescription>
                    Функционал НЕ ограничен — оплата находится в разработке.
                    Активируйте тестовую подписку для проверки UI.
                  </AlertDescription>
                </Alert>
              )}
              
              {/* Actions */}
              <div className="flex gap-3">
                {(isTrialing || isExpired) && currentShopId && (
                  <Button
                    onClick={() => extendMutation.mutate({ shopId: currentShopId, days: 7 })}
                    disabled={extendMutation.isPending}
                    variant="outline"
                  >
                    {extendMutation.isPending ? (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    ) : (
                      <Calendar className="mr-2 h-4 w-4" />
                    )}
                    Продлить trial на 7 дней
                  </Button>
                )}
              </div>
            </div>
          ) : (
            <p className="text-muted-foreground">Подписка не найдена</p>
          )}
        </CardContent>
      </Card>
      
      {/* Plans */}
      <div>
        <h2 className="text-xl font-semibold mb-4">Доступные планы</h2>
        <div className="grid gap-4 md:grid-cols-3">
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
                      /{plan.periodDays === 30 ? 'мес' : 'год'}
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
                </ul>
              </CardContent>
              <CardFooter>
                <Button 
                  className="w-full"
                  variant={subscription?.planCode === plan.code ? 'secondary' : 'default'}
                  disabled={subscription?.planCode === plan.code || activateMutation.isPending}
                  onClick={() => currentShopId && activateMutation.mutate({ 
                    shopId: currentShopId, 
                    planCode: plan.code 
                  })}
                >
                  {activateMutation.isPending ? (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  ) : subscription?.planCode === plan.code ? (
                    'Текущий план'
                  ) : plan.isStub ? (
                    <>
                      <Zap className="mr-2 h-4 w-4" />
                      Активировать (заглушка)
                    </>
                  ) : (
                    'Выбрать'
                  )}
                </Button>
              </CardFooter>
            </Card>
          ))}
        </div>
      </div>
      
      {/* FAQ */}
      <Card>
        <CardHeader>
          <CardTitle>Часто задаваемые вопросы</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <h4 className="font-medium">Что происходит когда trial заканчивается?</h4>
            <p className="text-sm text-muted-foreground">
              Ничего! Сейчас billing enforcement отключен. Весь функционал остаётся доступным.
            </p>
          </div>
          <Separator />
          <div>
            <h4 className="font-medium">Когда появится оплата?</h4>
            <p className="text-sm text-muted-foreground">
              Интеграция с CloudPayments/ЮKassa планируется в следующих релизах.
            </p>
          </div>
          <Separator />
          <div>
            <h4 className="font-medium">Что такое "заглушка"?</h4>
            <p className="text-sm text-muted-foreground">
              Это тестовый режим для проверки UI биллинга без реальной оплаты.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
