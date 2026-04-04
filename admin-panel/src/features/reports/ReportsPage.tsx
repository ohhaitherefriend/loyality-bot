import { useQuery } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  Users,
  UserPlus,
  TrendingUp,
  TrendingDown,
  DollarSign,
  ShoppingCart,
  Gift,
  Award,
  RefreshCw,
  AlertCircle,
  Calendar,
  Zap,
} from 'lucide-react'

import { api } from '@/api/client'
import { useShopStore } from '@/lib/store'
import { formatCurrency, formatNumber, formatDate, cn } from '@/lib/utils'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'

function ReportsSkeleton() {
  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        {[1, 2, 3, 4].map((i) => (
          <Card key={i}>
            <CardContent className="pt-6">
              <Skeleton className="h-8 w-24 mb-2" />
              <Skeleton className="h-4 w-32" />
            </CardContent>
          </Card>
        ))}
      </div>
      <Card>
        <CardContent className="space-y-4 pt-6">
          <Skeleton className="h-6 w-48" />
          {[1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="flex justify-between">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-4 w-20" />
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}

interface StatCardProps {
  title: string
  value: string | number
  subtitle?: string
  icon: typeof Users
  trend?: 'up' | 'down' | 'neutral'
  trendValue?: string
  delay?: number
}

function StatCard({ title, value, subtitle, icon: Icon, trend, trendValue, delay = 0 }: StatCardProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay }}
    >
      <Card>
        <CardContent className="pt-6">
          <div className="flex items-start justify-between">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
              <Icon className="h-5 w-5 text-primary" />
            </div>
            {trend && trendValue && (
              <Badge 
                variant={trend === 'up' ? 'success' : trend === 'down' ? 'destructive' : 'secondary'}
                className="gap-1"
              >
                {trend === 'up' && <TrendingUp className="h-3 w-3" />}
                {trend === 'down' && <TrendingDown className="h-3 w-3" />}
                {trendValue}
              </Badge>
            )}
          </div>
          <div className="mt-3">
            <p className="text-2xl font-bold">{value}</p>
            <p className="text-sm text-muted-foreground">{title}</p>
            {subtitle && (
              <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>
            )}
          </div>
        </CardContent>
      </Card>
    </motion.div>
  )
}

export function ReportsPage() {
  const { shopId } = useShopStore()

  const { data: report, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['weeklyReport', shopId],
    queryFn: () => api.getWeeklyReport(shopId!),
    enabled: !!shopId,
  })

  if (isLoading) {
    return (
      <div className="max-w-5xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Отчёты</h1>
          <p className="text-muted-foreground">Загрузка...</p>
        </div>
        <ReportsSkeleton />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="max-w-5xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Отчёты</h1>
        </div>
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Ошибка загрузки</AlertTitle>
          <AlertDescription>
            {error?.message || 'Не удалось загрузить отчёты'}
          </AlertDescription>
        </Alert>
        <Button onClick={() => refetch()} className="mt-4">
          <RefreshCw className="mr-2 h-4 w-4" />
          Повторить
        </Button>
      </div>
    )
  }

  if (!report) {
    return (
      <div className="max-w-5xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Отчёты</h1>
        </div>
        <Alert>
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Нет данных</AlertTitle>
          <AlertDescription>
            Отчёты появятся после первых транзакций
          </AlertDescription>
        </Alert>
      </div>
    )
  }

  const periodStart = report.period?.from ? formatDate(report.period.from) : '—'
  const periodEnd = report.period?.to ? formatDate(report.period.to) : '—'

  return (
    <div className="max-w-5xl mx-auto space-y-8">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Отчёты</h1>
          <div className="flex items-center gap-2 text-muted-foreground">
            <Calendar className="h-4 w-4" />
            <span>{periodStart} — {periodEnd}</span>
          </div>
        </div>
        <Button variant="outline" onClick={() => refetch()}>
          <RefreshCw className="mr-2 h-4 w-4" />
          Обновить
        </Button>
      </div>

      {/* Main Stats */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard
          title="Зарегистрировалось"
          value={formatNumber(report.customers.registered)}
          icon={UserPlus}
          trend="up"
          trendValue={`+${report.customers.registered}`}
          delay={0.1}
        />
        <StatCard
          title="Новых покупателей"
          value={formatNumber(report.customers.new)}
          icon={Users}
          trend="up"
          trendValue={`+${report.customers.new}`}
          delay={0.15}
        />
        <StatCard
          title="Вернувшихся"
          value={formatNumber(report.customers.returning)}
          icon={TrendingUp}
          delay={0.2}
        />
        <StatCard
          title="Потерянных"
          value={formatNumber(report.customers.lost)}
          icon={TrendingDown}
          trend={report.customers.lost > 5 ? 'down' : 'neutral'}
          delay={0.25}
        />
        <StatCard
          title="Транзакций"
          value={formatNumber(report.finances.transactions)}
          icon={ShoppingCart}
          delay={0.3}
        />
      </div>

      {/* Financial Stats */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.35 }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <DollarSign className="h-5 w-5" />
              Финансы
            </CardTitle>
            <CardDescription>
              Показатели за отчётный период
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
              <div className="space-y-1">
                <p className="text-sm text-muted-foreground">Выручка</p>
                <p className="text-2xl font-bold">
                  {formatCurrency(report.finances.totalRevenue)}
                </p>
              </div>
              <div className="space-y-1">
                <p className="text-sm text-muted-foreground">Средний чек</p>
                <p className="text-2xl font-bold">
                  {formatCurrency(report.finances.avgCheck)}
                </p>
              </div>
              <div className="space-y-1">
                <p className="text-sm text-muted-foreground">Fast Checkout</p>
                <p className="text-2xl font-bold flex items-center gap-2">
                  <Zap className="h-5 w-5 text-warning" />
                  {formatNumber(report.finances.fastCheckoutCount)}
                </p>
              </div>
              <div className="space-y-1">
                <p className="text-sm text-muted-foreground">% Fast Checkout</p>
                <p className="text-2xl font-bold">
                  {report.finances.fastCheckoutPercent}%
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      </motion.div>

      <div className="grid gap-6 lg:grid-cols-2">
        {/* Customer Statuses */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.4 }}
        >
          <Card className="h-full">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Users className="h-5 w-5" />
                Статусы клиентов
              </CardTitle>
              <CardDescription>
                Распределение по уровням активности
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <StatusBar
                  label="NEW"
                  count={report.statuses.new}
                  total={report.statuses.new + report.statuses.regular + report.statuses.vip + report.statuses.lost}
                  color="bg-blue-500"
                />
                <StatusBar
                  label="REGULAR"
                  count={report.statuses.regular}
                  total={report.statuses.new + report.statuses.regular + report.statuses.vip + report.statuses.lost}
                  color="bg-green-500"
                />
                <StatusBar
                  label="VIP"
                  count={report.statuses.vip}
                  total={report.statuses.new + report.statuses.regular + report.statuses.vip + report.statuses.lost}
                  color="bg-amber-500"
                />
                <StatusBar
                  label="LOST"
                  count={report.statuses.lost}
                  total={report.statuses.new + report.statuses.regular + report.statuses.vip + report.statuses.lost}
                  color="bg-red-500"
                />
              </div>
            </CardContent>
          </Card>
        </motion.div>

        {/* Top Customers */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.45 }}
        >
          <Card className="h-full">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Award className="h-5 w-5" />
                Топ клиентов
              </CardTitle>
              <CardDescription>
                По сумме покупок за период
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-3">
                {report.topCustomers.map((customer, index) => (
                  <div 
                    key={index}
                    className="flex items-center justify-between py-2 border-b last:border-0"
                  >
                    <div className="flex items-center gap-3">
                      <span className={cn(
                        "flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium",
                        index === 0 ? "bg-amber-100 text-amber-700" :
                        index === 1 ? "bg-gray-100 text-gray-700" :
                        index === 2 ? "bg-orange-100 text-orange-700" :
                        "bg-muted text-muted-foreground"
                      )}>
                        {index + 1}
                      </span>
                      <div>
                        <p className="font-medium">{customer.name}</p>
                        <p className="text-xs text-muted-foreground">
                          {customer.purchasesCount} покупок
                        </p>
                      </div>
                    </div>
                    <p className="font-semibold">
                      {formatCurrency(customer.totalSpend)}
                    </p>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </motion.div>
      </div>

      {/* Rewards & Achievements */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.5 }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Gift className="h-5 w-5" />
              Награды и достижения
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid gap-6 sm:grid-cols-2">
              <div className="flex items-center gap-4 p-4 rounded-lg bg-muted/50">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-primary/10">
                  <Gift className="h-6 w-6 text-primary" />
                </div>
                <div>
                  <p className="text-2xl font-bold">{report.rewardsRedeemed}</p>
                  <p className="text-sm text-muted-foreground">Погашено наград</p>
                </div>
              </div>
              <div className="flex items-center gap-4 p-4 rounded-lg bg-muted/50">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-warning/10">
                  <Award className="h-6 w-6 text-warning" />
                </div>
                <div>
                  <p className="text-2xl font-bold">{report.achievements}</p>
                  <p className="text-sm text-muted-foreground">Выдано достижений</p>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </motion.div>

      {/* Note about data */}
      <Alert>
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>Примечание</AlertTitle>
        <AlertDescription>
          Данные обновляются автоматически. Для получения полного отчёта за другой период 
          обратитесь в поддержку или используйте бота.
        </AlertDescription>
      </Alert>
    </div>
  )
}

interface StatusBarProps {
  label: string
  count: number
  total: number
  color: string
}

function StatusBar({ label, count, total, color }: StatusBarProps) {
  const percent = total > 0 ? Math.round((count / total) * 100) : 0
  
  return (
    <div className="space-y-2">
      <div className="flex justify-between text-sm">
        <span className="font-medium">{label}</span>
        <span className="text-muted-foreground">
          {formatNumber(count)} ({percent}%)
        </span>
      </div>
      <div className="h-2 rounded-full bg-muted overflow-hidden">
        <div 
          className={cn("h-full rounded-full transition-all", color)}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  )
}

