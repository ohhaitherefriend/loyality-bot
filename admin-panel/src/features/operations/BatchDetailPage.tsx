import { useState, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, ChevronLeft, ChevronRight, Loader2, PlayCircle, RefreshCw } from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { ImportRowStatus } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { formatDateTime } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'

import { RowReviewDialog } from './RowReviewDialog'
import {
  BATCH_STATUS_LABELS,
  batchStatusVariant,
  ROW_STATUS_LABELS,
  RULE_VERSION_STATUS_LABELS,
  rowStatusVariant,
} from './statusLabels'

const PAGE_SIZE = 50

const ROW_STATUS_FILTER_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'all', label: 'Все статусы' },
  { value: 'NEEDS_REVIEW', label: ROW_STATUS_LABELS.NEEDS_REVIEW },
  { value: 'INVALID', label: ROW_STATUS_LABELS.INVALID },
  { value: 'PENDING', label: ROW_STATUS_LABELS.PENDING },
  { value: 'APPLIED', label: ROW_STATUS_LABELS.APPLIED },
]

function InfoRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b py-1.5 last:border-b-0">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  )
}

export function BatchDetailPage() {
  const { batchId: batchIdParam } = useParams()
  const batchId = Number(batchIdParam)
  const navigate = useNavigate()
  const { shopId } = useShopStore()
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [reviewRowId, setReviewRowId] = useState<number | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)

  const batchQuery = useQuery({
    queryKey: ['batchDetail', shopId, batchId],
    queryFn: () => api.getBatchDetail(shopId!, batchId),
    enabled: !!shopId && !!batchId,
  })

  const rowsQuery = useQuery({
    queryKey: ['batchRows', shopId, batchId, page, statusFilter],
    queryFn: () =>
      api.listBatchRows(shopId!, batchId, {
        page,
        size: PAGE_SIZE,
        status: statusFilter === 'all' ? undefined : [statusFilter as ImportRowStatus],
      }),
    enabled: !!shopId && !!batchId,
  })

  const resumeMutation = useMutation({
    mutationFn: () => api.resumeBatch(shopId!, batchId),
    onSuccess: (result) => {
      toast({ title: 'Партия перезапущена', description: `Новый статус: ${BATCH_STATUS_LABELS[result.status]}` })
      queryClient.invalidateQueries({ queryKey: ['batchDetail', shopId, batchId] })
      queryClient.invalidateQueries({ queryKey: ['batchExceptions', shopId] })
      queryClient.invalidateQueries({ queryKey: ['importDashboard', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        variant: 'destructive',
        title: 'Не удалось перезапустить партию',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  const approveMutation = useMutation({
    mutationFn: () => api.approveBatch(shopId!, batchId),
    onSuccess: (result) => {
      toast({ title: 'Партия подтверждена', description: `Новый статус: ${BATCH_STATUS_LABELS[result.status]}` })
      queryClient.invalidateQueries({ queryKey: ['batchDetail', shopId, batchId] })
      queryClient.invalidateQueries({ queryKey: ['batchExceptions', shopId] })
      queryClient.invalidateQueries({ queryKey: ['importDashboard', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        variant: 'destructive',
        title: 'Не удалось подтвердить партию',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  if (!shopId) {
    return (
      <Alert>
        <AlertTitle>Магазин не выбран</AlertTitle>
      </Alert>
    )
  }

  if (batchQuery.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-40 w-full" />
      </div>
    )
  }

  if (batchQuery.isError || !batchQuery.data) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Партия не найдена</AlertTitle>
        <AlertDescription>
          {batchQuery.error instanceof ApiClientError ? batchQuery.error.message : 'Попробуйте позже'}
        </AlertDescription>
      </Alert>
    )
  }

  const batch = batchQuery.data
  const rows = rowsQuery.data?.content ?? []
  const canResume = batch.status === 'QUARANTINED' || batch.status === 'FAILED';
  const canApprove = batch.status === 'NEEDS_ATTENTION';

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button variant="ghost" size="sm" onClick={() => navigate('/operations/exceptions')}>
          <ArrowLeft className="mr-1 h-4 w-4" /> К очереди исключений
        </Button>
        <div className="flex items-center gap-2">
          {canApprove && (
            <Button disabled={approveMutation.isPending} onClick={() => approveMutation.mutate()}>
              {approveMutation.isPending ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <CheckCircle2 className="mr-1 h-4 w-4" />
              )}
              Подтвердить партию
            </Button>
          )}
          {canResume && (
            <Button disabled={resumeMutation.isPending} onClick={() => resumeMutation.mutate()}>
              {resumeMutation.isPending ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <PlayCircle className="mr-1 h-4 w-4" />
              )}
              Перезапустить партию
            </Button>
          )}
          <Button variant="outline" size="icon" onClick={() => { batchQuery.refetch(); rowsQuery.refetch() }}>
            <RefreshCw className="h-4 w-4" />
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-2xl font-bold tracking-tight">Партия #{batch.batchId}</h1>
        <Badge variant={batchStatusVariant(batch.status)}>{BATCH_STATUS_LABELS[batch.status]}</Badge>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">Источник</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            <InfoRow label="Поставщик" value={batch.supplierName} />
            <InfoRow label="Источник" value={batch.supplierSourceLabel} />
            <InfoRow label="Файл" value={batch.originalFilename || '—'} />
            <InfoRow label="Получен" value={batch.fileReceivedAt ? formatDateTime(batch.fileReceivedAt) : '—'} />
            {batch.ruleVersionId && (
              <InfoRow
                label="Версия правил"
                value={`#${batch.ruleVersionNumber} (${batch.ruleVersionStatus ? RULE_VERSION_STATUS_LABELS[batch.ruleVersionStatus] : '—'})`}
              />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">Строки</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            <InfoRow label="Всего" value={batch.totalRows ?? '—'} />
            <InfoRow label="Валидных" value={batch.validRows ?? '—'} />
            <InfoRow label="Невалидных" value={batch.invalidRows ?? '—'} />
            <InfoRow label="Попытка" value={batch.attemptNumber ?? 1} />
            {batch.errorMessage && (
              <div className="mt-2 rounded bg-destructive/10 p-2 text-destructive">{batch.errorMessage}</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">Результат применения</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            <InfoRow label="Добавлено" value={batch.offersAddedCount ?? '—'} />
            <InfoRow label="Обновлено" value={batch.offersUpdatedCount ?? '—'} />
            <InfoRow label="Изм. цена" value={batch.offersPriceChangedCount ?? '—'} />
            <InfoRow label="Снято с продажи" value={batch.productsRemovedFromStorefrontCount ?? '—'} />
            <InfoRow label="Возвращено" value={batch.productsReactivatedCount ?? '—'} />
            {!!batch.offersProtectedFromDeactivationCount && (
              <InfoRow
                label="Защищено от скрытия (ошибка строки)"
                value={
                  <span className="text-amber-600 font-medium">
                    {batch.offersProtectedFromDeactivationCount}
                  </span>
                }
              />
            )}
            <InfoRow label="Применена" value={batch.appliedAt ? formatDateTime(batch.appliedAt) : '—'} />
          </CardContent>
        </Card>
      </div>

      {Object.keys(batch.rowStatusCounts).length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">Статусы строк</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2">
            {Object.entries(batch.rowStatusCounts).map(([status, count]) => (
              <Badge key={status} variant={rowStatusVariant(status as ImportRowStatus)}>
                {ROW_STATUS_LABELS[status as ImportRowStatus] ?? status}: {count}
              </Badge>
            ))}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-3">
          <div>
            <CardTitle className="text-lg">Строки партии</CardTitle>
            {rowsQuery.data && <CardDescription>Всего: {rowsQuery.data.totalElements}</CardDescription>}
          </div>
          <Select
            value={statusFilter}
            onValueChange={(v) => {
              setStatusFilter(v)
              setPage(0)
            }}
          >
            <SelectTrigger className="w-[200px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {ROW_STATUS_FILTER_OPTIONS.map((o) => (
                <SelectItem key={o.value} value={o.value}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </CardHeader>
        <CardContent>
          {rowsQuery.isLoading && (
            <div className="space-y-2">
              {[1, 2, 3, 4].map((i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          )}

          {!rowsQuery.isLoading && rows.length === 0 && (
            <p className="py-8 text-center text-sm text-muted-foreground">Строк с таким статусом нет.</p>
          )}

          {rows.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[800px] text-sm">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="px-2 py-2">Строка</th>
                    <th className="px-2 py-2">Статус</th>
                    <th className="px-2 py-2">Название</th>
                    <th className="px-2 py-2">Бренд</th>
                    <th className="px-2 py-2">Цена</th>
                    <th className="px-2 py-2">Товар</th>
                    <th className="px-2 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.rowId} className="border-b last:border-b-0 hover:bg-muted/30">
                      <td className="px-2 py-2">
                        #{r.rowId} {r.sourceRowNumber != null ? `(стр. ${r.sourceRowNumber})` : ''}
                      </td>
                      <td className="px-2 py-2">
                        <Badge variant={rowStatusVariant(r.status)}>{ROW_STATUS_LABELS[r.status]}</Badge>
                      </td>
                      <td className="max-w-[220px] truncate px-2 py-2" title={r.rawNamePreview}>
                        {r.rawNamePreview || '—'}
                      </td>
                      <td className="px-2 py-2">{r.brandPreview || '—'}</td>
                      <td className="px-2 py-2 whitespace-nowrap">{r.supplierPricePreview ?? '—'}</td>
                      <td className="px-2 py-2">{r.matchedProductName || '—'}</td>
                      <td className="px-2 py-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => {
                            setReviewRowId(r.rowId)
                            setReviewOpen(true)
                          }}
                        >
                          Детали
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {rowsQuery.data && rowsQuery.data.totalPages > 1 && (
            <div className="mt-4 flex items-center justify-between">
              <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>
                <ChevronLeft className="mr-1 h-4 w-4" /> Назад
              </Button>
              <span className="text-sm text-muted-foreground">
                {page + 1} / {rowsQuery.data.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page >= rowsQuery.data.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                Вперёд <ChevronRight className="ml-1 h-4 w-4" />
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <RowReviewDialog shopId={shopId} rowId={reviewRowId} open={reviewOpen} onOpenChange={setReviewOpen} />
    </div>
  )
}