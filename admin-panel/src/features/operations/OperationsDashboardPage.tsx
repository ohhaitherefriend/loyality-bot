import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Gauge,
  Mail,
  Package,
  RefreshCw,
  XCircle,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import { useShopStore } from '@/lib/store'
import { formatRelativeTime } from '@/lib/utils'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'

const WINDOW_OPTIONS = [
  { value: 24, label: 'за 24 часа' },
  { value: 72, label: 'за 3 дня' },
  { value: 168, label: 'за 7 дней' },
]

function StatBlock({ label, value, tone }: { label: string; value: number | string; tone?: 'destructive' | 'warning' | 'success' }) {
  return (
    <div className="rounded-lg border p-3 text-center">
      <div
        className={
          tone === 'destructive'
            ? 'text-2xl font-bold text-destructive'
            : tone === 'warning'
              ? 'text-2xl font-bold text-amber-600'
              : tone === 'success'
                ? 'text-2xl font-bold text-emerald-600'
                : 'text-2xl font-bold'
        }
      >
        {value}
      </div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  )
}

function DashboardSkeleton() {
  return (
    <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
      {[1, 2, 3, 4, 5, 6].map((i) => (
        <Card key={i}>
          <CardContent className="space-y-3 pt-6">
            <Skeleton className="h-5 w-32" />
            <Skeleton className="h-16 w-full" />
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

export function OperationsDashboardPage() {
  const { shopId } = useShopStore()
  const [windowHours, setWindowHours] = useState(24)

  const dashboardQuery = useQuery({
    queryKey: ['importDashboard', shopId, windowHours],
    queryFn: () => api.getImportDashboard(shopId!, windowHours),
    enabled: !!shopId,
    refetchInterval: 30_000,
  })

  if (!shopId) {
    return (
      <Alert>
        <AlertTitle>Магазин не выбран</AlertTitle>
      </Alert>
    )
  }

  if (dashboardQuery.isLoading) {
    return <DashboardSkeleton />
  }

  if (dashboardQuery.isError || !dashboardQuery.data) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>Не удалось загрузить дашборд</AlertTitle>
        <AlertDescription>
          {dashboardQuery.error instanceof ApiClientError ? dashboardQuery.error.message : 'Попробуйте позже'}
        </AlertDescription>
      </Alert>
    )
  }

  const d = dashboardQuery.data

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Автоматизация импорта</h1>
          <p className="text-muted-foreground">
            Сводка по обработке прайс-листов поставщиков без необходимости открывать каждую партию
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Select value={String(windowHours)} onValueChange={(v) => setWindowHours(Number(v))}>
            <SelectTrigger className="w-[140px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {WINDOW_OPTIONS.map((o) => (
                <SelectItem key={o.value} value={String(o.value)}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button variant="outline" size="icon" onClick={() => dashboardQuery.refetch()}>
            <RefreshCw className="h-4 w-4" />
          </Button>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {/* Automation rate */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-lg">
              <Gauge className="h-5 w-5" /> Уровень автоматизации
            </CardTitle>
            <CardDescription>Доля строк, обработанных без участия человека</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="text-4xl font-bold">
              {d.automationRate.ratePercent != null ? `${d.automationRate.ratePercent.toFixed(1)}%` : '—'}
            </div>
            <div className="mt-2 text-sm text-muted-foreground">
              Авто: {d.automationRate.autoDecidedRows} · Вручную: {d.automationRate.humanDecidedRows}
            </div>
          </CardContent>
        </Card>

        {/* Exception queue */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-lg">
              <AlertTriangle className="h-5 w-5" /> Очередь исключений
            </CardTitle>
            <CardDescription>Строки и партии, требующие решения оператора</CardDescription>
          </CardHeader>
          <CardContent className="flex items-center justify-between">
            <div className="text-4xl font-bold">{d.exceptionQueueSize}</div>
            <Button asChild variant={d.exceptionQueueSize > 0 ? 'default' : 'outline'}>
              <Link to="/operations/exceptions">Открыть очередь</Link>
            </Button>
          </CardContent>
        </Card>

        {/* Recent activity */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-lg">
              <Activity className="h-5 w-5" /> Активность
            </CardTitle>
            <CardDescription>За окно {d.recentActivity.windowHours} ч.</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-2">
            <StatBlock label="Файлов обработано" value={d.recentActivity.filesProcessed} />
            <StatBlock label="Строк обработано" value={d.recentActivity.rowsProcessed} />
          </CardContent>
        </Card>

        {/* Batches */}
        <Card className="md:col-span-2 lg:col-span-2">
          <CardHeader className="pb-3">
            <CardTitle className="text-lg">Партии импорта</CardTitle>
            <CardDescription>Всего партий: {d.batches.total}</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-3 gap-2 sm:grid-cols-5">
            <StatBlock label="В работе" value={d.batches.running} />
            <StatBlock label="Требуют внимания" value={d.batches.needsAttention} tone="warning" />
            <StatBlock label="Ошибка" value={d.batches.failed} tone="destructive" />
            <StatBlock label="В карантине" value={d.batches.quarantined} tone="destructive" />
            <StatBlock label="Применено" value={d.batches.applied} tone="success" />
          </CardContent>
        </Card>

        {/* Product changes */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-lg">
              <Package className="h-5 w-5" /> Изменения товаров
            </CardTitle>
            <CardDescription>За окно {d.recentProductChanges.windowHours} ч.</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-2">
            <StatBlock label="Добавлено" value={d.recentProductChanges.added} tone="success" />
            <StatBlock label="Обновлено" value={d.recentProductChanges.updated} />
            <StatBlock label="Изм. цена" value={d.recentProductChanges.priceChanged} />
            <StatBlock label="Снято с продажи" value={d.recentProductChanges.removedFromStorefront} tone="destructive" />
            <StatBlock label="Возвращено" value={d.recentProductChanges.reactivated} tone="success" />
          </CardContent>
        </Card>
      </div>

      {/* Mailbox health */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-lg">
            <Mail className="h-5 w-5" /> Здоровье почтовых ящиков
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {d.mailboxes.length === 0 && (
            <p className="text-sm text-muted-foreground">Почтовые ящики не настроены.</p>
          )}
          {d.mailboxes.map((m) => (
            <div key={m.mailboxId} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
              <div className="flex items-center gap-2">
                {m.healthy ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-600" />
                ) : (
                  <XCircle className="h-4 w-4 text-destructive" />
                )}
                <span className="font-medium">{m.label}</span>
                {!m.enabled && <Badge variant="secondary">Отключён</Badge>}
              </div>
              <div className="flex items-center gap-3 text-sm text-muted-foreground">
                {m.lastPollAt && (
                  <span className="flex items-center gap-1">
                    <Clock className="h-3.5 w-3.5" /> {formatRelativeTime(m.lastPollAt)}
                  </span>
                )}
                {m.lastPollError && <span className="text-destructive">{m.lastPollError}</span>}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* Supplier exception rates */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-lg">Доля исключений по поставщикам</CardTitle>
          <CardDescription>Поставщики с высокой долей строк, требующих ручного решения</CardDescription>
        </CardHeader>
        <CardContent>
          {d.supplierExceptionRates.length === 0 && (
            <p className="text-sm text-muted-foreground">Пока нет данных.</p>
          )}
          {d.supplierExceptionRates.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="px-2 py-2">Поставщик</th>
                    <th className="px-2 py-2">Всего строк</th>
                    <th className="px-2 py-2">Исключений</th>
                    <th className="px-2 py-2">Доля</th>
                  </tr>
                </thead>
                <tbody>
                  {d.supplierExceptionRates.map((s) => (
                    <tr key={s.supplierId} className="border-b last:border-b-0">
                      <td className="px-2 py-2 font-medium">{s.supplierName}</td>
                      <td className="px-2 py-2">{s.totalRows}</td>
                      <td className="px-2 py-2">{s.exceptionRows}</td>
                      <td className="px-2 py-2">
                        <Badge variant={s.exceptionRatePercent > 20 ? 'destructive' : 'secondary'}>
                          {s.exceptionRatePercent.toFixed(1)}%
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
