import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle,
  Ban,
  ChevronLeft,
  ChevronRight,
  EyeOff,
  Loader2,
  PlayCircle,
  RefreshCw,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { BulkRowReviewRequest } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { formatDateTime } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Alert, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

import { RowReviewDialog } from './RowReviewDialog'
import { BATCH_STATUS_LABELS, batchStatusVariant, ROW_STATUS_LABELS, rowStatusVariant } from './statusLabels'

const PAGE_SIZE = 20

function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number
  totalPages: number
  onChange: (page: number) => void
}) {
  if (totalPages <= 1) return null
  return (
    <div className="mt-4 flex items-center justify-between">
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        <ChevronLeft className="mr-1 h-4 w-4" /> Назад
      </Button>
      <span className="text-sm text-muted-foreground">
        {page + 1} / {totalPages}
      </span>
      <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>
        Вперёд <ChevronRight className="ml-1 h-4 w-4" />
      </Button>
    </div>
  )
}

function RowExceptionsTab({ shopId }: { shopId: string }) {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [supplierId, setSupplierId] = useState<string>('all')
  const [selectedRowIds, setSelectedRowIds] = useState<number[]>([])
  const [reviewRowId, setReviewRowId] = useState<number | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)

  const suppliersQuery = useQuery({
    queryKey: ['suppliers', shopId],
    queryFn: () => api.listSuppliers(shopId),
  })

  const rowsQuery = useQuery({
    queryKey: ['rowExceptions', shopId, page, supplierId],
    queryFn: () =>
      api.listRowExceptions(shopId, {
        page,
        size: PAGE_SIZE,
        supplierId: supplierId === 'all' ? undefined : Number(supplierId),
      }),
  })

  const invalidateAll = () => {
    queryClient.invalidateQueries({ queryKey: ['rowExceptions', shopId] })
    queryClient.invalidateQueries({ queryKey: ['importDashboard', shopId] })
  }

  const bulkMutation = useMutation({
    mutationFn: (request: BulkRowReviewRequest) => api.bulkReviewRows(shopId, request),
    onSuccess: (result) => {
      const failedCount = Object.keys(result.failures).length
      toast({
        title: 'Массовое действие выполнено',
        description: `Успешно: ${result.succeededRowIds.length}${failedCount ? `, не удалось: ${failedCount}` : ''}`,
        variant: failedCount ? 'destructive' : 'default',
      })
      setSelectedRowIds([])
      invalidateAll()
    },
    onError: (error: unknown) => {
      toast({
        variant: 'destructive',
        title: 'Не удалось выполнить массовое действие',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  const toggleRow = (rowId: number) => {
    setSelectedRowIds((prev) => (prev.includes(rowId) ? prev.filter((id) => id !== rowId) : [...prev, rowId]))
  }

  const rows = rowsQuery.data?.content ?? []
  const allSelected = rows.length > 0 && rows.every((r) => selectedRowIds.includes(r.rowId))

  const toggleAll = () => {
    if (allSelected) {
      setSelectedRowIds((prev) => prev.filter((id) => !rows.some((r) => r.rowId === id)))
    } else {
      setSelectedRowIds((prev) => [...new Set([...prev, ...rows.map((r) => r.rowId)])])
    }
  }

  const openReview = (rowId: number) => {
    setReviewRowId(rowId)
    setReviewOpen(true)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Select
          value={supplierId}
          onValueChange={(v) => {
            setSupplierId(v)
            setPage(0)
          }}
        >
          <SelectTrigger className="w-[220px]">
            <SelectValue placeholder="Все поставщики" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">Все поставщики</SelectItem>
            {(suppliersQuery.data ?? []).map((s) => (
              <SelectItem key={s.id} value={String(s.id)}>
                {s.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button variant="outline" size="icon" onClick={() => rowsQuery.refetch()}>
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>

      {selectedRowIds.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/40 p-3">
          <span className="text-sm font-medium">Выбрано строк: {selectedRowIds.length}</span>
          <Button
            size="sm"
            variant="outline"
            disabled={bulkMutation.isPending}
            onClick={() => bulkMutation.mutate({ rowIds: selectedRowIds, action: 'IGNORE' })}
          >
            <EyeOff className="mr-1 h-3.5 w-3.5" /> Пропустить
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={bulkMutation.isPending}
            onClick={() => bulkMutation.mutate({ rowIds: selectedRowIds, action: 'NO_MATCH' })}
          >
            <Ban className="mr-1 h-3.5 w-3.5" /> Нет совпадения
          </Button>
          {bulkMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
        </div>
      )}

      {rowsQuery.isLoading && (
        <div className="space-y-2">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-12 w-full" />
          ))}
        </div>
      )}

      {rowsQuery.isError && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Не удалось загрузить строки</AlertTitle>
        </Alert>
      )}

      {!rowsQuery.isLoading && rows.length === 0 && (
        <p className="py-8 text-center text-sm text-muted-foreground">Очередь исключений по строкам пуста.</p>
      )}

      {rows.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full min-w-[900px] text-sm">
            <thead>
              <tr className="border-b text-left text-muted-foreground">
                <th className="w-8 px-2 py-2">
                  <input type="checkbox" checked={allSelected} onChange={toggleAll} className="h-4 w-4" />
                </th>
                <th className="px-2 py-2">Строка</th>
                <th className="px-2 py-2">Статус</th>
                <th className="px-2 py-2">Поставщик</th>
                <th className="px-2 py-2">Партия</th>
                <th className="px-2 py-2">Название</th>
                <th className="px-2 py-2">Бренд</th>
                <th className="px-2 py-2">Цена</th>
                <th className="px-2 py-2">Создана</th>
                <th className="px-2 py-2" />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.rowId} className="border-b last:border-b-0 hover:bg-muted/30">
                  <td className="px-2 py-2">
                    <input
                      type="checkbox"
                      checked={selectedRowIds.includes(r.rowId)}
                      onChange={() => toggleRow(r.rowId)}
                      className="h-4 w-4"
                    />
                  </td>
                  <td className="px-2 py-2">
                    #{r.rowId} {r.sourceRowNumber != null ? `(стр. ${r.sourceRowNumber})` : ''}
                  </td>
                  <td className="px-2 py-2">
                    <Badge variant={rowStatusVariant(r.status)}>{ROW_STATUS_LABELS[r.status]}</Badge>
                  </td>
                  <td className="px-2 py-2">{r.supplierName}</td>
                  <td className="px-2 py-2">
                    <Link to={`/operations/batches/${r.batchId}`} className="text-primary hover:underline">
                      #{r.batchId}
                    </Link>
                  </td>
                  <td className="max-w-[200px] truncate px-2 py-2" title={r.rawNamePreview}>
                    {r.rawNamePreview || '—'}
                  </td>
                  <td className="px-2 py-2">{r.brandPreview || '—'}</td>
                  <td className="px-2 py-2 whitespace-nowrap">{r.supplierPricePreview ?? '—'}</td>
                  <td className="px-2 py-2 whitespace-nowrap">{formatDateTime(r.createdAt)}</td>
                  <td className="px-2 py-2">
                    <Button size="sm" variant="secondary" onClick={() => openReview(r.rowId)}>
                      Разобрать
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {rowsQuery.data && <Pagination page={page} totalPages={rowsQuery.data.totalPages} onChange={setPage} />}

      {shopId && (
        <RowReviewDialog shopId={shopId} rowId={reviewRowId} open={reviewOpen} onOpenChange={setReviewOpen} />
      )}
    </div>
  )
}

function BatchExceptionsTab({ shopId }: { shopId: string }) {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)

  const batchesQuery = useQuery({
    queryKey: ['batchExceptions', shopId, page],
    queryFn: () => api.listBatchExceptions(shopId, { page, size: PAGE_SIZE }),
  })

  const resumeMutation = useMutation({
    mutationFn: (batchId: number) => api.resumeBatch(shopId, batchId),
    onSuccess: (result) => {
      toast({ title: 'Партия перезапущена', description: `Новый статус: ${BATCH_STATUS_LABELS[result.status]}` })
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

  const batches = batchesQuery.data?.content ?? []

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Button variant="outline" size="icon" onClick={() => batchesQuery.refetch()}>
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>

      {batchesQuery.isLoading && (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-16 w-full" />
          ))}
        </div>
      )}

      {!batchesQuery.isLoading && batches.length === 0 && (
        <p className="py-8 text-center text-sm text-muted-foreground">Нет партий, требующих внимания.</p>
      )}

      <div className="space-y-2">
        {batches.map((b) => (
          <div key={b.batchId} className="rounded-lg border p-3 space-y-1.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <span className="font-medium">Партия #{b.batchId}</span>
                <Badge variant={batchStatusVariant(b.status)}>{BATCH_STATUS_LABELS[b.status]}</Badge>
                <span className="text-sm text-muted-foreground">{b.supplierName}</span>
              </div>
              <div className="flex items-center gap-2">
                <Button asChild size="sm" variant="outline">
                  <Link to={`/operations/batches/${b.batchId}`}>Открыть</Link>
                </Button>
                {(b.status === 'QUARANTINED' || b.status === 'FAILED') && (
                  <Button
                    size="sm"
                    disabled={resumeMutation.isPending}
                    onClick={() => resumeMutation.mutate(b.batchId)}
                  >
                    {resumeMutation.isPending ? (
                      <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <PlayCircle className="mr-1 h-3.5 w-3.5" />
                    )}
                    Перезапустить
                  </Button>
                )}
              </div>
            </div>
            <div className="text-sm text-muted-foreground">
              {b.originalFilename || 'без файла'} · строк: {b.totalRows ?? '—'} (валидных: {b.validRows ?? '—'},
              невалидных: {b.invalidRows ?? '—'}) · попытка {b.attemptNumber ?? 1} · создана{' '}
              {formatDateTime(b.createdAt)}
            </div>
            {b.errorMessage && <div className="text-sm text-destructive">{b.errorMessage}</div>}
          </div>
        ))}
      </div>

      {batchesQuery.data && (
        <Pagination page={page} totalPages={batchesQuery.data.totalPages} onChange={setPage} />
      )}
    </div>
  )
}

export function ExceptionsQueuePage() {
  const { shopId } = useShopStore()

  if (!shopId) {
    return (
      <Alert>
        <AlertTitle>Магазин не выбран</AlertTitle>
      </Alert>
    )
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Очередь исключений</h1>
        <p className="text-muted-foreground">
          Строки и партии, которые автоматика не смогла обработать самостоятельно
        </p>
      </div>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-lg">Что требует решения</CardTitle>
          <CardDescription>Строки без уверенного совпадения и партии в карантине/с ошибкой</CardDescription>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue="rows">
            <TabsList>
              <TabsTrigger value="rows">Строки</TabsTrigger>
              <TabsTrigger value="batches">Партии</TabsTrigger>
            </TabsList>
            <TabsContent value="rows" className="pt-4">
              <RowExceptionsTab shopId={shopId} />
            </TabsContent>
            <TabsContent value="batches" className="pt-4">
              <BatchExceptionsTab shopId={shopId} />
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>
    </div>
  )
}
