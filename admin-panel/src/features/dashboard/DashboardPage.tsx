import { useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  Bot,
  Settings,
  Link2,
  BarChart3,
  CreditCard,
  ArrowRight,
  Clock,
  AlertTriangle,
  CheckCircle,
  Users,
  TrendingUp,
  Coffee,
} from 'lucide-react'

import { api } from '@/api/client'
import { useAuthStore } from '@/lib/auth-store'
import { useShopStore } from '@/lib/store'
import { cn } from '@/lib/utils'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'

export function DashboardPage() {
  const navigate = useNavigate()
  const { user, shops } = useAuthStore()
  const { shopId, botUsername } = useShopStore()
  
  // Get current shop
  const currentShopId = shopId || shops[0]?.shopId
  
  // Fetch subscription
  const { data: subscription, isLoading: subscriptionLoading } = useQuery({
    queryKey: ['subscription', currentShopId],
    queryFn: () => currentShopId ? api.getSubscription(currentShopId) : null,
    enabled: !!currentShopId,
  })
  
  // Fetch bot info
  const { data: botInfo, isLoading: botLoading } = useQuery({
    queryKey: ['botInfo', currentShopId],
    queryFn: async () => {
      if (!currentShopId) return null
      const bots = await api.listBots()
      return bots.find(b => b.shopId === currentShopId) || null
    },
    enabled: !!currentShopId,
  })
  
  // Redirect if no shop
  useEffect(() => {
    if (!currentShopId && shops.length === 0) {
      navigate('/onboarding')
    }
  }, [currentShopId, shops, navigate])
  
  const isTrialing = subscription?.status === 'TRIALING'
  const isExpired = subscription?.status === 'EXPIRED'
  const daysLeft = subscription?.daysLeft || 0
  
  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Dashboard</h1>
          <p className="text-muted-foreground">
            Добро пожаловать, {user?.name || user?.email}!
          </p>
        </div>
        
        {botUsername && (
          <Badge variant="outline" className="text-base py-1 px-3">
            <Bot className="mr-2 h-4 w-4" />
            @{botUsername}
          </Badge>
        )}
      </div>
      
      {/* Trial/Billing Banner */}
      {subscription && (isTrialing || isExpired) && (
        <motion.div
          initial={{ opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
        >
          <Alert variant={isExpired ? 'destructive' : 'default'} className="border-2">
            {isTrialing ? (
              <Clock className="h-4 w-4" />
            ) : (
              <AlertTriangle className="h-4 w-4" />
            )}
            <AlertTitle>
              {isTrialing 
                ? `Пробный период: ${daysLeft} дней осталось`
                : 'Trial период закончился'
              }
            </AlertTitle>
            <AlertDescription className="flex items-center justify-between">
              <span>
                {isTrialing
                  ? 'Все функции доступны. Оплата в разработке.'
                  : 'Оплата в разработке. Функционал НЕ ограничен.'
                }
              </span>
              <Button size="sm" variant="outline" asChild>
                <Link to="/billing">
                  Подробнее
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
            </AlertDescription>
          </Alert>
        </motion.div>
      )}
      
      {/* Quick Actions */}
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Card className="hover:shadow-md transition-shadow cursor-pointer" onClick={() => navigate('/status')}>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Статус бота</CardTitle>
            <Bot className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {botLoading ? (
              <Skeleton className="h-8 w-24" />
            ) : (
              <div className="flex items-center gap-2">
                {botInfo?.status === 'ACTIVE' ? (
                  <>
                    <CheckCircle className="h-5 w-5 text-success" />
                    <span className="text-2xl font-bold">Активен</span>
                  </>
                ) : (
                  <>
                    <AlertTriangle className="h-5 w-5 text-warning" />
                    <span className="text-2xl font-bold">{botInfo?.status || 'Неизвестно'}</span>
                  </>
                )}
              </div>
            )}
          </CardContent>
        </Card>
        
        <Card className="hover:shadow-md transition-shadow cursor-pointer" onClick={() => navigate('/settings')}>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Настройки</CardTitle>
            <Settings className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">Настроить</p>
            <p className="text-xs text-muted-foreground">Штампы, скидки, награды</p>
          </CardContent>
        </Card>
        
        <Card className="hover:shadow-md transition-shadow cursor-pointer" onClick={() => navigate('/links')}>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Ссылки и QR</CardTitle>
            <Link2 className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">Получить</p>
            <p className="text-xs text-muted-foreground">Для клиентов и кассиров</p>
          </CardContent>
        </Card>
        
        <Card className="hover:shadow-md transition-shadow cursor-pointer" onClick={() => navigate('/reports')}>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Отчёты</CardTitle>
            <BarChart3 className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">Статистика</p>
            <p className="text-xs text-muted-foreground">Клиенты и продажи</p>
          </CardContent>
        </Card>
      </div>
      
      {/* Subscription Status */}
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CreditCard className="h-5 w-5" />
              Подписка
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {subscriptionLoading ? (
              <Skeleton className="h-20 w-full" />
            ) : subscription ? (
              <>
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Статус</span>
                  <Badge
                    variant={
                      subscription.status === 'ACTIVE' ? 'default' :
                      subscription.status === 'TRIALING' ? 'secondary' : 'destructive'
                    }
                  >
                    {subscription.status === 'TRIALING' && 'Пробный период'}
                    {subscription.status === 'ACTIVE' && 'Активна'}
                    {subscription.status === 'EXPIRED' && 'Истекла'}
                  </Badge>
                </div>
                
                {subscription.status === 'TRIALING' && (
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Осталось дней</span>
                    <span className="font-bold">{daysLeft}</span>
                  </div>
                )}
                
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Enforcement</span>
                  <Badge variant="outline">
                    {subscription.billingEnforcementMode === 'OFF' ? 'Отключено' : subscription.billingEnforcementMode}
                  </Badge>
                </div>
                
                <Button variant="outline" className="w-full" asChild>
                  <Link to="/billing">
                    Управление подпиской
                    <ArrowRight className="ml-2 h-4 w-4" />
                  </Link>
                </Button>
              </>
            ) : (
              <p className="text-muted-foreground">Подписка не найдена</p>
            )}
          </CardContent>
        </Card>
        
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Coffee className="h-5 w-5" />
              Быстрые действия
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <Button variant="outline" className="w-full justify-start" asChild>
              <Link to="/settings">
                <Settings className="mr-2 h-4 w-4" />
                Настроить штампы и скидки
              </Link>
            </Button>
            <Button variant="outline" className="w-full justify-start" asChild>
              <Link to="/links">
                <Link2 className="mr-2 h-4 w-4" />
                Скачать QR-код для кассы
              </Link>
            </Button>
            <Button variant="outline" className="w-full justify-start" asChild>
              <Link to="/reports">
                <TrendingUp className="mr-2 h-4 w-4" />
                Посмотреть статистику
              </Link>
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
