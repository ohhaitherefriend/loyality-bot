import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  CheckCircle2,
  XCircle,
  Clock,
  Activity,
  RefreshCw,
  AlertTriangle,
  Loader2,
  Wifi,
  WifiOff,
  MessageSquare,
  Bot,
} from 'lucide-react'

import { api } from '@/api/client'
import { useShopStore } from '@/lib/store'
import { formatDateTime, formatRelativeTime, formatNumber, cn } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { Separator } from '@/components/ui/separator'

const statusConfig: Record<string, { label: string; color: string; icon: typeof CheckCircle2 }> = {
  ACTIVE: { label: 'Активен', color: 'success', icon: CheckCircle2 },
  PENDING: { label: 'Ожидание', color: 'warning', icon: Clock },
  CONNECTING: { label: 'Подключение', color: 'warning', icon: RefreshCw },
  ERROR: { label: 'Ошибка', color: 'destructive', icon: XCircle },
  DISABLED: { label: 'Отключён', color: 'secondary', icon: WifiOff },
}

function StatusSkeleton() {
  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-4">
        {[1, 2, 3, 4].map((i) => (
          <Card key={i}>
            <CardContent className="pt-6">
              <Skeleton className="h-8 w-16 mb-2" />
              <Skeleton className="h-4 w-24" />
            </CardContent>
          </Card>
        ))}
      </div>
      <Card>
        <CardContent className="space-y-4 pt-6">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
        </CardContent>
      </Card>
    </div>
  )
}

export function StatusPage() {
  const { botInstanceId, botUsername, setShop, shopId } = useShopStore()
  const queryClient = useQueryClient()

  const { data: botsList } = useQuery({
    queryKey: ['botsList'],
    queryFn: () => api.listBots(),
  })

  const effectiveBotInstanceId = (botInstanceId && botInstanceId !== 0) 
    ? botInstanceId 
    : botsList?.[0]?.id

  const currentBot = botsList?.find(b => b.id === effectiveBotInstanceId)

  // Обновляем store, если нашли бота через fallback
  if (effectiveBotInstanceId && (!botInstanceId || botInstanceId === 0) && botsList?.[0]) {
    setShop({
      shopId: botsList[0].shopId,
      botInstanceId: botsList[0].id,
      botUsername: botsList[0].botUsername,
      businessName: botsList[0].businessName,
      platform: botsList[0].platform,
    })
  }

  const { data: botInfo, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['botInfo', effectiveBotInstanceId],
    queryFn: () => api.getBotInfo(effectiveBotInstanceId!),
    enabled: !!effectiveBotInstanceId,
    refetchInterval: 30000, // Refresh every 30s
  })

  const reconnectMutation = useMutation({
    mutationFn: () => api.updateWebhook(effectiveBotInstanceId!, { 
      baseUrl: window.location.origin 
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['botInfo', effectiveBotInstanceId] })
      toast({
        title: 'Webhook обновлён',
        description: `Подключение к ${currentBot?.platform === 'MAX' ? 'Max' : 'Telegram'} восстановлено`,
      })
    },
    onError: () => {
      toast({
        title: 'Ошибка',
        description: 'Не удалось обновить webhook',
        variant: 'destructive',
      })
    },
  })

  if (isLoading) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Статус подключения</h1>
          <p className="text-muted-foreground">Загрузка...</p>
        </div>
        <StatusSkeleton />
      </div>
    )
  }

  if (isError || !botInfo) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Статус подключения</h1>
        </div>
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Ошибка загрузки</AlertTitle>
          <AlertDescription>
            Не удалось получить информацию о боте: {error?.message || 'Неизвестная ошибка'}
          </AlertDescription>
        </Alert>
        <Button onClick={() => refetch()} className="mt-4">
          <RefreshCw className="mr-2 h-4 w-4" />
          Повторить
        </Button>
      </div>
    )
  }

  const statusInfo = statusConfig[botInfo.status] || statusConfig.PENDING
  const StatusIcon = statusInfo.icon
  const isHealthy = botInfo.status === 'ACTIVE' && botInfo.isActive

  return (
    <div className="max-w-4xl mx-auto space-y-8">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Статус подключения</h1>
          <p className="text-muted-foreground">
            Мониторинг состояния бота @{botUsername}
          </p>
        </div>
        <Button
          variant="outline"
          onClick={() => refetch()}
          disabled={isLoading}
        >
          <RefreshCw className={cn("mr-2 h-4 w-4", isLoading && "animate-spin")} />
          Обновить
        </Button>
      </div>

      {/* Status Banner */}
      <motion.div
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <Card className={cn(
          'border-2',
          isHealthy ? 'border-success/50 bg-success/5' : 'border-destructive/50 bg-destructive/5'
        )}>
          <CardContent className="flex items-center gap-4 pt-6">
            <div className={cn(
              'flex h-16 w-16 items-center justify-center rounded-full',
              isHealthy ? 'bg-success/20 text-success' : 'bg-destructive/20 text-destructive'
            )}>
              {isHealthy ? (
                <Wifi className="h-8 w-8" />
              ) : (
                <WifiOff className="h-8 w-8" />
              )}
            </div>
            <div className="flex-1">
              <div className="flex items-center gap-2">
                <h2 className="text-xl font-semibold">
                  {isHealthy ? 'Бот работает' : 'Требуется внимание'}
                </h2>
                <Badge variant={statusInfo.color as 'default' | 'secondary' | 'destructive'}>
                  <StatusIcon className="mr-1 h-3 w-3" />
                  {statusInfo.label}
                </Badge>
              </div>
              <p className="text-muted-foreground">
                {isHealthy 
                  ? 'Все системы функционируют нормально'
                  : botInfo.lastError || 'Проверьте настройки подключения'}
              </p>
            </div>
            {!isHealthy && (
              <Button
                onClick={() => reconnectMutation.mutate()}
                disabled={reconnectMutation.isPending}
              >
                {reconnectMutation.isPending ? (
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                ) : (
                  <RefreshCw className="mr-2 h-4 w-4" />
                )}
                Переподключить
              </Button>
            )}
          </CardContent>
        </Card>
      </motion.div>

      {/* Stats Grid */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
                  <MessageSquare className="h-5 w-5 text-primary" />
                </div>
                <div>
                  <p className="text-2xl font-bold">
                    {formatNumber(botInfo.updatesProcessed)}
                  </p>
                  <p className="text-sm text-muted-foreground">
                    Обработано сообщений
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </motion.div>

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.15 }}
        >
          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-warning/10">
                  <Clock className="h-5 w-5 text-warning" />
                </div>
                <div>
                  <p className="text-2xl font-bold">
                    {botInfo.pendingUpdates}
                  </p>
                  <p className="text-sm text-muted-foreground">
                    В очереди
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </motion.div>

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-success/10">
                  <Activity className="h-5 w-5 text-success" />
                </div>
                <div>
                  <p className="text-2xl font-bold">
                    {botInfo.lastWebhookAt 
                      ? formatRelativeTime(botInfo.lastWebhookAt)
                      : '—'}
                  </p>
                  <p className="text-sm text-muted-foreground">
                    Последняя активность
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </motion.div>

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.25 }}
        >
          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent">
                  <Bot className="h-5 w-5" />
                </div>
                <div>
                  <p className="text-2xl font-bold">@{botInfo.botUsername}</p>
                  <p className="text-sm text-muted-foreground">
                    {botInfo.businessName || (currentBot?.platform === 'MAX' ? 'Max Bot' : 'Telegram Bot')}
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </motion.div>
      </div>

      {/* Details */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.3 }}
      >
        <Card>
          <CardHeader>
            <CardTitle>Детали подключения</CardTitle>
            <CardDescription>
              Техническая информация о webhook и боте
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1">
                <p className="text-sm font-medium text-muted-foreground">Shop ID</p>
                <p className="font-mono text-sm">{botInfo.shopId}</p>
              </div>
              <div className="space-y-1">
                <p className="text-sm font-medium text-muted-foreground">Bot Instance ID</p>
                <p className="font-mono text-sm">{botInfo.id}</p>
              </div>
            </div>

            <Separator />

            <div className="space-y-1">
              <p className="text-sm font-medium text-muted-foreground">Webhook URL</p>
              <p className="font-mono text-xs break-all bg-muted px-3 py-2 rounded-md">
                {botInfo.webhookUrl || 'Не установлен'}
              </p>
            </div>

            {botInfo.lastWebhookAt && (
              <>
                <Separator />
                <div className="space-y-1">
                  <p className="text-sm font-medium text-muted-foreground">
                    Последний webhook
                  </p>
                  <p className="text-sm">
                    {formatDateTime(botInfo.lastWebhookAt)}
                  </p>
                </div>
              </>
            )}

            {botInfo.lastError && (
              <>
                <Separator />
                <Alert variant="destructive">
                  <AlertTriangle className="h-4 w-4" />
                  <AlertTitle>Последняя ошибка</AlertTitle>
                  <AlertDescription>{botInfo.lastError}</AlertDescription>
                </Alert>
              </>
            )}
          </CardContent>
        </Card>
      </motion.div>

      {/* Actions */}
      <Card>
        <CardHeader>
          <CardTitle>Действия</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-3">
          <Button
            variant="outline"
            onClick={() => reconnectMutation.mutate()}
            disabled={reconnectMutation.isPending}
          >
            {reconnectMutation.isPending ? (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="mr-2 h-4 w-4" />
            )}
            Обновить Webhook
          </Button>
          <Button
            variant="outline"
            asChild
          >
            <a 
              href={currentBot?.platform === 'MAX' ? `https://max.ru/${botInfo.botUsername}` : `https://t.me/${botInfo.botUsername}`} 
              target="_blank" 
              rel="noopener noreferrer"
            >
              <Bot className="mr-2 h-4 w-4" />
              Открыть бота
            </a>
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}

