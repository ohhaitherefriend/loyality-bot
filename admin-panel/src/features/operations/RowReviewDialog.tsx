import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Check, Loader2, Ban, PackagePlus, EyeOff } from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { RowReviewAction, ScoredCandidate } from '@/api/types'
import { formatDateTime } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'

import {
  DECIDED_BY_LABELS,
  DECISION_TYPE_LABELS,
  ROW_STATUS_LABELS,
  rowStatusVariant,
} from './statusLabels'

interface RowReviewDialogProps {
  shopId: string
  rowId: number | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

function KeyValueTable({ data }: { data: Record<string, unknown> }) {
  const entries = Object.entries(data)
  if (entries.length === 0) {
    return <p className="text-sm text-muted-foreground">Нет данных</p>
  }
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <tbody>
          {entries.map(([key, value]) => (
            <tr key={key} className="border-b last:border-b-0">
              <td className="w-1/3 px-3 py-1.5 text-muted-foreground">{key}</td>
              <td className="px-3 py-1.5 break-all">
                {value == null || value === ''
                  ? '—'
                  : typeof value === 'object'
                    ? JSON.stringify(value)
                    : String(value)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function CandidateCard({
  candidate,
  onSelect,
  selected,
}: {
  candidate: ScoredCandidate
  onSelect: () => void
  selected: boolean
}) {
  return (
    <div
      className={`rounded-lg border p-3 space-y-1.5 ${selected ? 'border-primary bg-primary/5' : ''}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">{candidate.productName}</span>
        <Badge variant={selected ? 'default' : 'secondary'}>
          score {candidate.totalScore.toFixed(2)}
        </Badge>
      </div>
      <div className="text-xs text-muted-foreground">ID товара: {candidate.productId}</div>
      {candidate.matchedAttributes.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {candidate.matchedAttributes.map((a) => (
            <Badge key={a} variant="success" className="text-[10px]">
              {a}
            </Badge>
          ))}
        </div>
      )}
      {candidate.conflicts.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {candidate.conflicts.map((c) => (
            <Badge key={c} variant="destructive" className="text-[10px]">
              {c}
            </Badge>
          ))}
        </div>
      )}
      <Button size="sm" variant={selected ? 'default' : 'outline'} onClick={onSelect}>
        <Check className="mr-1 h-3.5 w-3.5" />
        {selected ? 'Выбрано' : 'Выбрать'}
      </Button>
    </div>
  )
}

export function RowReviewDialog({ shopId, rowId, open, onOpenChange }: RowReviewDialogProps) {
  const queryClient = useQueryClient()
  const [selectedProductId, setSelectedProductId] = useState<number | null>(null)
  const [manualProductId, setManualProductId] = useState('')
  const [note, setNote] = useState('')

  useEffect(() => {
    if (open) {
      setSelectedProductId(null)
      setManualProductId('')
      setNote('')
    }
  }, [open, rowId])

  const rowQuery = useQuery({
    queryKey: ['rowDetail', shopId, rowId],
    queryFn: () => api.getRowDetail(shopId, rowId!),
    enabled: open && rowId != null,
  })

  const invalidateRelated = () => {
    queryClient.invalidateQueries({ queryKey: ['rowDetail', shopId, rowId] })
    queryClient.invalidateQueries({ queryKey: ['rowExceptions', shopId] })
    queryClient.invalidateQueries({ queryKey: ['batchRows', shopId] })
    queryClient.invalidateQueries({ queryKey: ['batchDetail', shopId] })
    queryClient.invalidateQueries({ queryKey: ['importDashboard', shopId] })
  }

  const reviewMutation = useMutation({
    mutationFn: (params: { action: RowReviewAction; productId?: number }) =>
      api.reviewRow(shopId, rowId!, {
        action: params.action,
        expectedVersion: rowQuery.data?.version,
        productId: params.productId,
        note: note || undefined,
      }),
    onSuccess: (result) => {
      toast({ title: 'Решение сохранено', description: `Новый статус: ${ROW_STATUS_LABELS[result.status]}` })
      invalidateRelated()
      onOpenChange(false)
    },
    onError: (error: unknown) => {
      if (error instanceof ApiClientError && error.status === 409) {
        toast({
          variant: 'destructive',
          title: 'Строка изменилась',
          description: 'Кто-то уже принял решение по этой строке. Данные обновлены, проверьте заново.',
        })
        queryClient.invalidateQueries({ queryKey: ['rowDetail', shopId, rowId] })
        return
      }
      toast({
        variant: 'destructive',
        title: 'Не удалось сохранить решение',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  const row = rowQuery.data
  const effectiveProductId = selectedProductId ?? (manualProductId ? Number(manualProductId) : undefined)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            Строка импорта {row ? `#${row.rowId}` : ''}
            {row && <Badge variant={rowStatusVariant(row.status)}>{ROW_STATUS_LABELS[row.status]}</Badge>}
          </DialogTitle>
          {row && (
            <DialogDescription>
              Партия #{row.batchId} · лист «{row.sourceSheet || '—'}» · строка {row.sourceRowNumber ?? '—'} ·
              версия {row.version}
            </DialogDescription>
          )}
        </DialogHeader>

        {rowQuery.isLoading && (
          <div className="space-y-2">
            <Skeleton className="h-6 w-full" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        )}

        {row && (
          <div className="space-y-5">
            {row.matchedProductId && (
              <div className="rounded-lg border bg-muted/30 p-3 text-sm">
                Сопоставлен товар: <span className="font-medium">{row.matchedProductName}</span> (ID{' '}
                {row.matchedProductId})
              </div>
            )}

            <section>
              <h4 className="mb-2 text-sm font-semibold">Исходные данные</h4>
              <KeyValueTable data={row.rawData} />
            </section>

            {row.normalizedData && (
              <section>
                <h4 className="mb-2 text-sm font-semibold">Нормализованные данные</h4>
                <KeyValueTable data={row.normalizedData as Record<string, unknown>} />
              </section>
            )}

            {row.candidates.length > 0 && (
              <section>
                <h4 className="mb-2 text-sm font-semibold">Кандидаты на совпадение</h4>
                <div className="grid gap-2 sm:grid-cols-2">
                  {row.candidates.map((c) => (
                    <CandidateCard
                      key={c.productId}
                      candidate={c}
                      selected={selectedProductId === c.productId}
                      onSelect={() =>
                        setSelectedProductId((prev) => (prev === c.productId ? null : c.productId))
                      }
                    />
                  ))}
                </div>
              </section>
            )}

            {row.decisions.length > 0 && (
              <section>
                <h4 className="mb-2 text-sm font-semibold">История решений</h4>
                <div className="space-y-2">
                  {row.decisions.map((d) => (
                    <div key={d.id} className="rounded-lg border p-3 text-sm space-y-1">
                      <div className="flex items-center justify-between">
                        <span className="font-medium">{DECISION_TYPE_LABELS[d.decisionType]}</span>
                        <span className="text-xs text-muted-foreground">{formatDateTime(d.decidedAt)}</span>
                      </div>
                      <div className="text-xs text-muted-foreground">
                        {DECIDED_BY_LABELS[d.decidedBy]}
                        {d.reviewerEmail ? ` · ${d.reviewerEmail}` : ''}
                        {d.confidenceScore != null ? ` · score ${d.confidenceScore}` : ''}
                        {d.modelProvider ? ` · ${d.modelProvider}/${d.modelName}` : ''}
                        {d.promptVersion ? ` · prompt ${d.promptVersion}` : ''}
                      </div>
                      {d.chosenProductName && <div>Товар: {d.chosenProductName}</div>}
                      {d.conflicts.length > 0 && (
                        <div className="flex flex-wrap gap-1">
                          {d.conflicts.map((c) => (
                            <Badge key={c} variant="destructive" className="text-[10px]">
                              {c}
                            </Badge>
                          ))}
                        </div>
                      )}
                      {d.reason && <div className="text-muted-foreground">{d.reason}</div>}
                    </div>
                  ))}
                </div>
              </section>
            )}

            <Separator />

            <section className="space-y-3">
              <h4 className="text-sm font-semibold">Решение оператора</h4>
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <Label htmlFor="manualProductId">ID товара (если не из списка кандидатов)</Label>
                  <Input
                    id="manualProductId"
                    type="number"
                    value={manualProductId}
                    onChange={(e) => {
                      setManualProductId(e.target.value)
                      setSelectedProductId(null)
                    }}
                    placeholder="напр. 123"
                  />
                </div>
                <div>
                  <Label htmlFor="note">Комментарий (опционально)</Label>
                  <Textarea id="note" value={note} onChange={(e) => setNote(e.target.value)} rows={1} />
                </div>
              </div>
              {reviewMutation.isPending && (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Loader2 className="h-4 w-4 animate-spin" /> Сохранение...
                </div>
              )}
            </section>
          </div>
        )}

        <DialogFooter className="flex-wrap gap-2 sm:justify-between">
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              disabled={reviewMutation.isPending}
              onClick={() => reviewMutation.mutate({ action: 'IGNORE' })}
            >
              <EyeOff className="mr-1 h-4 w-4" /> Пропустить
            </Button>
            <Button
              variant="outline"
              disabled={reviewMutation.isPending}
              onClick={() => reviewMutation.mutate({ action: 'NO_MATCH' })}
            >
              <Ban className="mr-1 h-4 w-4" /> Нет совпадения
            </Button>
            <Button
              variant="outline"
              disabled={reviewMutation.isPending}
              onClick={() => reviewMutation.mutate({ action: 'CREATE_PRODUCT' })}
            >
              <PackagePlus className="mr-1 h-4 w-4" /> Создать товар
            </Button>
          </div>
          <Button
            disabled={reviewMutation.isPending || !effectiveProductId}
            onClick={() => reviewMutation.mutate({ action: 'MATCH', productId: effectiveProductId })}
          >
            {reviewMutation.isPending ? (
              <Loader2 className="mr-1 h-4 w-4 animate-spin" />
            ) : (
              <Check className="mr-1 h-4 w-4" />
            )}
            Подтвердить совпадение
          </Button>
        </DialogFooter>

        {rowQuery.isError && (
          <div className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4" /> Не удалось загрузить строку
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
