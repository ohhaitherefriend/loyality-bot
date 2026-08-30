import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Package,
  RefreshCw,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { DeliveryType, OrderDetails, OrderSource, OrderStatus, OrderSummary } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { cn, formatCurrency, formatDateTime } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'

const PAGE_SIZE = 20

const ORDER_STATUSES: OrderStatus[] = [
  'DRAFT',
  'CREATED',
  'CONFIRMED',
  'PACKING',
  'READY_FOR_PICKUP',
  'SHIPPED',
  'COMPLETED',
  'CANCELLED',
]

const ADMIN_STATUS_OPTIONS: OrderStatus[] = [
  'CONFIRMED',
  'PACKING',
  'READY_FOR_PICKUP',
  'SHIPPED',
  'COMPLETED',
  'CANCELLED',
]

const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  DRAFT: 'Черновик',
  CREATED: 'Создан',
  CONFIRMED: 'Подтверждён',
  PACKING: 'Сборка',
  READY_FOR_PICKUP: 'Готов к выдаче',
  SHIPPED: 'Отправлен',
  COMPLETED: 'Завершён',
  CANCELLED: 'Отменён',
}

const DELIVERY_TYPE_LABELS: Record<DeliveryType, string> = {
  PICKUP: 'Самовывоз',
  COURIER: 'Курьер',
  CDEK: 'СДЭК',
  OTHER: 'Другое',
}

function toMoney(value: number | string | null | undefined): number {
  if (value == null) return 0
  return typeof value === 'number' ? value : Number(value)
}

function getStatusBadgeVariant(
  status: OrderStatus
): 'default' | 'secondary' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'CANCELLED':
      return 'destructive'
    case 'PACKING':
    case 'READY_FOR_PICKUP':
      return 'warning'
    case 'CREATED':
    case 'DRAFT':
      return 'secondary'
    default:
      return 'default'
  }
}

function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return (
    <Badge variant={getStatusBadgeVariant(status)}>
      {ORDER_STATUS_LABELS[status]}
    </Badge>
  )
}

function OrdersListSkeleton() {
  return (
    <div className="space-y-2">
      {[1, 2, 3, 4, 5].map((i) => (
        <Skeleton key={i} className="h-16 w-full" />
      ))}
    </div>
  )
}

function OrderDetailPanel({
  order,
  isLoading,
  isUpdating,
  onStatusChange,
}: {
  order?: OrderDetails
  isLoading: boolean
  isUpdating: boolean
  onStatusChange: (status: OrderStatus) => void
}) {
  const [nextStatus, setNextStatus] = useState<OrderStatus | ''>('')

  if (isLoading) {
    return (
      <Card className="h-full">
        <CardHeader>
          <Skeleton className="h-6 w-40" />
          <Skeleton className="h-4 w-56" />
        </CardHeader>
        <CardContent className="space-y-4">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-32 w-full" />
        </CardContent>
      </Card>
    )
  }

  if (!order) {
    return (
      <Card className="h-full">
        <CardContent className="flex h-full min-h-[320px] flex-col items-center justify-center text-center text-muted-foreground">
          <Package className="mb-3 h-10 w-10 opacity-40" />
          <p>Выберите заказ из списка</p>
        </CardContent>
      </Card>
    )
  }

  const isTerminal = order.status === 'COMPLETED' || order.status === 'CANCELLED'
  const availableStatuses = ADMIN_STATUS_OPTIONS.filter((s) => s !== order.status)

  return (
    <Card className="h-full">
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle>Заказ #{order.id}</CardTitle>
            <CardDescription>{formatDateTime(order.createdAt)}</CardDescription>
            <div className="mt-2">
              <OrderSourceBadge source={order.source} />
            </div>
          </div>
          <OrderStatusBadge status={order.status} />
        </div>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <p className="text-muted-foreground">Клиент</p>
            <p className="font-medium">{order.customerName || '—'}</p>
          </div>
          <div>
            <p className="text-muted-foreground">Телефон</p>
            <p className="font-medium">{order.customerPhone || '—'}</p>
          </div>
          <div>
            <p className="text-muted-foreground">Доставка</p>
            <p className="font-medium">{DELIVERY_TYPE_LABELS[order.deliveryType]}</p>
          </div>
          <div>
            <p className="text-muted-foreground">Адрес</p>
            <p className="font-medium whitespace-pre-wrap">{order.deliveryAddress || '—'}</p>
          </div>
        </div>

        {order.customerComment && (
          <div className="rounded-lg border bg-muted/30 p-3 text-sm">
            <p className="mb-1 text-muted-foreground">Комментарий</p>
            <p className="whitespace-pre-wrap">{order.customerComment}</p>
          </div>
        )}

        <div>
          <p className="mb-3 text-sm font-medium">Товары</p>
          <div className="space-y-2">
            {order.items.map((item) => (
              <div
                key={item.id}
                className="flex items-start justify-between gap-3 rounded-lg border p-3 text-sm"
              >
                <div className="min-w-0">
                  <p className="font-medium">{item.nameSnapshot}</p>
                  {item.brandSnapshot && (
                    <p className="text-muted-foreground">{item.brandSnapshot}</p>
                  )}
                  <p className="text-muted-foreground">
                    {item.quantity} × {formatCurrency(toMoney(item.priceSnapshot))}
                  </p>
                </div>
                <p className="shrink-0 font-medium">
                  {formatCurrency(toMoney(item.lineTotal))}
                </p>
              </div>
            ))}
          </div>
        </div>

        <Separator />

        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-muted-foreground">Сумма товаров</span>
            <span>{formatCurrency(toMoney(order.itemsTotal))}</span>
          </div>
          {toMoney(order.bonusSpent) > 0 && (
            <div className="flex justify-between">
              <span className="text-muted-foreground">Списано бонусов</span>
              <span>−{formatCurrency(toMoney(order.bonusSpent))}</span>
            </div>
          )}
          <div className="flex justify-between text-base font-semibold">
            <span>К оплате</span>
            <span>{formatCurrency(toMoney(order.totalToPay))}</span>
          </div>
          {order.status === 'COMPLETED' && toMoney(order.bonusAccrued) > 0 && (
            <div className="flex justify-between text-success">
              <span>Начислено бонусов</span>
              <span>{formatCurrency(toMoney(order.bonusAccrued))}</span>
            </div>
          )}
        </div>

        {!isTerminal && (
          <div className="rounded-lg border p-4 space-y-3">
            <p className="text-sm font-medium">Изменить статус</p>
            <div className="flex flex-col gap-2 sm:flex-row">
              <Select
                value={nextStatus}
                onValueChange={(value) => setNextStatus(value as OrderStatus)}
                disabled={isUpdating}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Выберите статус" />
                </SelectTrigger>
                <SelectContent>
                  {availableStatuses.map((status) => (
                    <SelectItem key={status} value={status}>
                      {ORDER_STATUS_LABELS[status]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                disabled={!nextStatus || isUpdating}
                onClick={() => {
                  if (nextStatus) {
                    onStatusChange(nextStatus)
                    setNextStatus('')
                  }
                }}
              >
                {isUpdating ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Сохранение...
                  </>
                ) : (
                  'Применить'
                )}
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function OrderSourceBadge({ source }: { source?: OrderSource }) {
  if (!source) return null
  const label = source === 'MINI_APP' ? 'Mini App' : source === 'BOT' ? 'Бот' : 'Админ'
  return <Badge variant="outline">{label}</Badge>
}

function OrderRow({
  order,
  selected,
  onSelect,
}: {
  order: OrderSummary
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'w-full rounded-lg border p-4 text-left transition-colors hover:bg-accent/50',
        selected && 'border-primary bg-primary/5'
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="font-medium">#{order.id}</p>
          <p className="text-sm text-muted-foreground">{formatDateTime(order.createdAt)}</p>
          <div className="mt-1">
            <OrderSourceBadge source={order.source} />
          </div>
        </div>
        <OrderStatusBadge status={order.status} />
      </div>
      <div className="mt-3 grid gap-1 text-sm sm:grid-cols-2">
        <p>
          <span className="text-muted-foreground">Клиент: </span>
          {order.customerName || '—'}
        </p>
        <p>
          <span className="text-muted-foreground">Телефон: </span>
          {order.customerPhone || '—'}
        </p>
        <p>
          <span className="text-muted-foreground">Доставка: </span>
          {DELIVERY_TYPE_LABELS[order.deliveryType]}
        </p>
        <p className="font-medium">{formatCurrency(toMoney(order.totalToPay))}</p>
      </div>
    </button>
  )
}

export function OrdersPage() {
  const { shopId } = useShopStore()
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<OrderStatus | 'ALL'>('ALL')
  const [page, setPage] = useState(0)
  const [selectedOrderId, setSelectedOrderId] = useState<number | null>(null)

  const {
    data: ordersPage,
    isLoading: isListLoading,
    isError: isListError,
    error: listError,
    refetch: refetchList,
  } = useQuery({
    queryKey: ['orders', shopId, statusFilter, page],
    queryFn: () =>
      api.listOrders(shopId!, {
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        page,
        size: PAGE_SIZE,
      }),
    enabled: !!shopId,
  })

  const {
    data: orderDetails,
    isLoading: isDetailLoading,
  } = useQuery({
    queryKey: ['order', shopId, selectedOrderId],
    queryFn: () => api.getOrder(shopId!, selectedOrderId!),
    enabled: !!shopId && selectedOrderId != null,
  })

  const statusMutation = useMutation({
    mutationFn: (status: OrderStatus) =>
      api.updateOrderStatus(shopId!, selectedOrderId!, { status }),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: ['orders', shopId] })
      queryClient.setQueryData(['order', shopId, updated.id], updated)
      toast({
        title: 'Статус обновлён',
        description: ORDER_STATUS_LABELS[updated.status],
      })
    },
    onError: (error) => {
      const message =
        error instanceof ApiClientError ? error.message : 'Не удалось обновить статус'
      toast({
        variant: 'destructive',
        title: 'Ошибка',
        description: message,
      })
    },
  })

  const handleStatusFilterChange = (value: string) => {
    setStatusFilter(value as OrderStatus | 'ALL')
    setPage(0)
    setSelectedOrderId(null)
  }

  return (
    <div className="mx-auto max-w-6xl">
      <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Заказы</h1>
          <p className="text-muted-foreground">Управление заказами из Telegram-магазина</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Select value={statusFilter} onValueChange={handleStatusFilterChange}>
            <SelectTrigger className="w-[220px]">
              <SelectValue placeholder="Статус" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">Все статусы</SelectItem>
              {ORDER_STATUSES.map((status) => (
                <SelectItem key={status} value={status}>
                  {ORDER_STATUS_LABELS[status]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button variant="outline" size="icon" onClick={() => refetchList()}>
            <RefreshCw className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {isListError && (
        <Alert variant="destructive" className="mb-6">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Не удалось загрузить заказы</AlertTitle>
          <AlertDescription>
            {listError instanceof ApiClientError ? listError.message : 'Попробуйте позже'}
          </AlertDescription>
        </Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-5">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          className="lg:col-span-2 space-y-4"
        >
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-lg">Список заказов</CardTitle>
              {ordersPage && (
                <CardDescription>
                  Всего: {ordersPage.totalElements}
                </CardDescription>
              )}
            </CardHeader>
            <CardContent className="space-y-3">
              {isListLoading && <OrdersListSkeleton />}

              {!isListLoading && ordersPage?.content.length === 0 && (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  Заказов пока нет
                </p>
              )}

              {!isListLoading &&
                ordersPage?.content.map((order) => (
                  <OrderRow
                    key={order.id}
                    order={order}
                    selected={selectedOrderId === order.id}
                    onSelect={() => setSelectedOrderId(order.id)}
                  />
                ))}

              {ordersPage && ordersPage.totalPages > 1 && (
                <div className="flex items-center justify-between pt-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page <= 0}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    <ChevronLeft className="mr-1 h-4 w-4" />
                    Назад
                  </Button>
                  <span className="text-sm text-muted-foreground">
                    {page + 1} / {ordersPage.totalPages}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page >= ordersPage.totalPages - 1}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Вперёд
                    <ChevronRight className="ml-1 h-4 w-4" />
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>
        </motion.div>

        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.05 }}
          className="lg:col-span-3"
        >
          <OrderDetailPanel
            order={orderDetails}
            isLoading={!!selectedOrderId && isDetailLoading}
            isUpdating={statusMutation.isPending}
            onStatusChange={(status) => statusMutation.mutate(status)}
          />
        </motion.div>
      </div>
    </div>
  )
}
