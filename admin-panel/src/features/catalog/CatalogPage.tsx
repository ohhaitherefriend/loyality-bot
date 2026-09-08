import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  ImageIcon,
  Loader2,
  Pencil,
  RefreshCw,
  Search,
  Upload,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { Product } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { useAuthStore } from '@/lib/auth-store'
import { formatCurrency, resolveProductImageUrl } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Skeleton } from '@/components/ui/skeleton'

import {
  AVAILABILITY_LABELS,
  IMAGE_STATUS_LABELS,
  ProductEditDialog,
  imageStatusVariant,
} from './ProductEditDialog'

const PAGE_SIZE = 20

type TriState = 'all' | 'true' | 'false'

function triStateToBoolean(value: TriState): boolean | undefined {
  if (value === 'all') return undefined
  return value === 'true'
}

function toMoney(value: number | string | null | undefined): number {
  if (value == null) return 0
  return typeof value === 'number' ? value : Number(value)
}

function BoolBadge({ value, trueLabel, falseLabel }: { value: boolean; trueLabel: string; falseLabel: string }) {
  return (
    <Badge variant={value ? 'success' : 'secondary'}>
      {value ? trueLabel : falseLabel}
    </Badge>
  )
}

export function CatalogPage() {
  const { shopId: storedShopId } = useShopStore()
  const { shops } = useAuthStore()
  const shopId = storedShopId || shops[0]?.shopId || null
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [query, setQuery] = useState('')
  const [queryInput, setQueryInput] = useState('')
  const [brand, setBrand] = useState<string>('all')
  const [visibleFilter, setVisibleFilter] = useState<TriState>('all')
  const [activeFilter, setActiveFilter] = useState<TriState>('all')
  const [missingImagesFilter, setMissingImagesFilter] = useState<TriState>('all')

  const [importFile, setImportFile] = useState<File | null>(null)
  const [defaultMarkupPercent, setDefaultMarkupPercent] = useState('35')
  const [makeImportedVisible, setMakeImportedVisible] = useState(false)
  const [overwriteManualFields, setOverwriteManualFields] = useState(false)

  const [editProduct, setEditProduct] = useState<Product | null>(null)
  const [editOpen, setEditOpen] = useState(false)

  const { data: brands = [] } = useQuery({
    queryKey: ['productBrands', shopId],
    queryFn: () => api.listBrands(shopId!),
    enabled: !!shopId,
  })

  const { data: searchStatus } = useQuery({
    queryKey: ['imageSearchStatus', shopId],
    queryFn: () => api.getImageSearchStatus(shopId!),
    enabled: !!shopId,
  })

  const {
    data: productsPage,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['products', shopId, page, query, brand, visibleFilter, activeFilter, missingImagesFilter],
    queryFn: () =>
      api.listProducts(shopId!, {
        page,
        size: PAGE_SIZE,
        query: query || undefined,
        brand: brand === 'all' ? undefined : brand,
        visible: triStateToBoolean(visibleFilter),
        active: triStateToBoolean(activeFilter),
        missingImages: triStateToBoolean(missingImagesFilter),
      }),
    enabled: !!shopId,
  })

  const bulkSearchMutation = useMutation({
    mutationFn: (productIds: number[]) =>
      api.searchImagesBulk(shopId!, {
        productIds,
        maxCandidatesPerProduct: 5,
        downloadAndNormalize: true,
      }),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['products', shopId] })
      toast({
        title: 'Поиск изображений завершён',
        description: `Нормализовано: ${result.imagesNormalized}, на проверке: ${result.needsReview}, rembg ok: ${result.backgroundRemovalSucceeded}, fallback: ${result.fallbackNormalized}`,
      })
    },
    onError: (err) => {
      toast({
        variant: 'destructive',
        title: 'Ошибка поиска изображений',
        description: err instanceof ApiClientError ? err.message : 'Попробуйте позже',
      })
    },
  })

  const importMutation = useMutation({
    mutationFn: () =>
      api.importCatalog(shopId!, {
        file: importFile!,
        defaultMarkupPercent: Number(defaultMarkupPercent),
        makeImportedVisible,
        overwriteManualFields,
      }),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['products', shopId] })
      queryClient.invalidateQueries({ queryKey: ['productBrands', shopId] })
      setImportFile(null)
      toast({
        title: 'Импорт завершён',
        description: `Импортировано: ${result.importedCount}, обновлено: ${result.updatedCount}, пропущено: ${result.skippedCount}`,
      })
    },
    onError: (err) => {
      toast({
        variant: 'destructive',
        title: 'Ошибка импорта',
        description: err instanceof ApiClientError ? err.message : 'Попробуйте позже',
      })
    },
  })

  const applySearch = () => {
    setQuery(queryInput.trim())
    setPage(0)
  }

  const openEdit = (product: Product) => {
    setEditProduct(product)
    setEditOpen(true)
  }

  return (
    <div className="mx-auto max-w-[1400px]">
      <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Каталог</h1>
          <p className="text-muted-foreground">Импорт прайса, публикация товаров и изображения</p>
        </div>
        <Button variant="outline" size="icon" onClick={() => refetch()}>
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>

      {searchStatus && (
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-lg">Image pipeline</CardTitle>
            <CardDescription>
              Brave: {searchStatus.imageSearchConfigured ? 'настроен' : 'не настроен'} · Ranker: {searchStatus.ranker} · rembg:{' '}
              {searchStatus.backgroundRemovalHealthy
                ? 'запущен'
                : searchStatus.backgroundRemovalConfigured
                  ? 'недоступен, будет fallback-нормализация'
                  : 'выключен'}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2 text-sm text-muted-foreground">
            <span>Search: {searchStatus.imageSearchProvider}</span>
            <span>Output: {searchStatus.normalizationOutputSize}px</span>
          </CardContent>
        </Card>
      )}

      <motion.div initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} className="mb-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Импорт XLSX</CardTitle>
            <CardDescription>
              Поддерживаются стандартный прайс (листы «Косметика и уход» / «Парфюмерия») и простой формат: Артикул, Наименование, Цена. По умолчанию товары скрыты.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="importFile">Файл прайса</Label>
              <Input
                id="importFile"
                type="file"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={(e) => setImportFile(e.target.files?.[0] ?? null)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="markup">Наценка, %</Label>
              <Input
                id="markup"
                type="number"
                min={0}
                value={defaultMarkupPercent}
                onChange={(e) => setDefaultMarkupPercent(e.target.value)}
              />
            </div>
            <div className="flex flex-col justify-end gap-3">
              <div className="flex items-center justify-between rounded-lg border p-3">
                <Label htmlFor="makeVisible" className="text-sm">
                  Сделать видимыми
                </Label>
                <Switch
                  id="makeVisible"
                  checked={makeImportedVisible}
                  onCheckedChange={setMakeImportedVisible}
                />
              </div>
              <div className="flex items-center justify-between rounded-lg border p-3">
                <Label htmlFor="overwrite" className="text-sm">
                  Перезаписать цены
                </Label>
                <Switch
                  id="overwrite"
                  checked={overwriteManualFields}
                  onCheckedChange={setOverwriteManualFields}
                />
              </div>
            </div>
            <div className="md:col-span-2 lg:col-span-4">
              <Button
                disabled={!importFile || importMutation.isPending}
                onClick={() => importMutation.mutate()}
              >
                {importMutation.isPending ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Импорт...
                  </>
                ) : (
                  <>
                    <Upload className="mr-2 h-4 w-4" />
                    Импортировать
                  </>
                )}
              </Button>
            </div>
          </CardContent>
        </Card>
      </motion.div>

      <Card className="mb-6">
        <CardHeader className="pb-3">
          <CardTitle className="text-lg">Фильтры</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-2 lg:grid-cols-6">
          <div className="flex gap-2 md:col-span-2">
            <Input
              placeholder="Поиск по названию, бренду, артикулу..."
              value={queryInput}
              onChange={(e) => setQueryInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && applySearch()}
            />
            <Button variant="secondary" onClick={applySearch}>
              Найти
            </Button>
          </div>
          <Select value={brand} onValueChange={(v) => { setBrand(v); setPage(0) }}>
            <SelectTrigger>
              <SelectValue placeholder="Бренд" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">Все бренды</SelectItem>
              {brands.map((b) => (
                <SelectItem key={b} value={b}>{b}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <TriStateSelect label="Видимость" value={visibleFilter} onChange={(v) => { setVisibleFilter(v); setPage(0) }} />
          <TriStateSelect label="Активность" value={activeFilter} onChange={(v) => { setActiveFilter(v); setPage(0) }} />
          <TriStateSelect
            label="Без фото"
            value={missingImagesFilter}
            onChange={(v) => { setMissingImagesFilter(v); setPage(0) }}
            trueLabel="Без фото"
            falseLabel="С фото"
          />
        </CardContent>
      </Card>

      {isError && (
        <Alert variant="destructive" className="mb-6">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Не удалось загрузить каталог</AlertTitle>
          <AlertDescription>
            {error instanceof ApiClientError ? error.message : 'Попробуйте позже'}
          </AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <CardTitle className="text-lg">Товары</CardTitle>
              {productsPage && (
                <CardDescription>Всего: {productsPage.totalElements}</CardDescription>
              )}
            </div>
            {productsPage && productsPage.content.length > 0 && (
              <Button
                variant="secondary"
                size="sm"
                disabled={bulkSearchMutation.isPending || !searchStatus?.imageSearchConfigured}
                onClick={() =>
                  bulkSearchMutation.mutate(productsPage.content.map((p) => p.id))
                }
              >
                {bulkSearchMutation.isPending ? (
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                ) : (
                  <Search className="mr-2 h-4 w-4" />
                )}
                Поиск изображений (страница)
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {isLoading && (
            <div className="space-y-2">
              {[1, 2, 3, 4, 5].map((i) => (
                <Skeleton key={i} className="h-14 w-full" />
              ))}
            </div>
          )}

          {!isLoading && productsPage?.content.length === 0 && (
            <p className="py-8 text-center text-sm text-muted-foreground">
              Товары не найдены. Импортируйте прайс-лист.
            </p>
          )}

          {!isLoading && productsPage && productsPage.content.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[1200px] text-sm">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="px-2 py-2">Фото</th>
                    <th className="px-2 py-2">Статус фото</th>
                    <th className="px-2 py-2">Бренд</th>
                    <th className="px-2 py-2">Название</th>
                    <th className="px-2 py-2">Артикул</th>
                    <th className="px-2 py-2">Штрихкод</th>
                    <th className="px-2 py-2">Закупка</th>
                    <th className="px-2 py-2">Продажа</th>
                    <th className="px-2 py-2">Старая</th>
                    <th className="px-2 py-2">Наличие</th>
                    <th className="px-2 py-2">Остаток</th>
                    <th className="px-2 py-2">Видим</th>
                    <th className="px-2 py-2">Активен</th>
                    <th className="px-2 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {productsPage.content.map((product) => (
                    <ProductRow key={product.id} product={product} onEdit={() => openEdit(product)} />
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {productsPage && productsPage.totalPages > 1 && (
            <div className="mt-4 flex items-center justify-between">
              <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>
                <ChevronLeft className="mr-1 h-4 w-4" />
                Назад
              </Button>
              <span className="text-sm text-muted-foreground">
                {page + 1} / {productsPage.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page >= productsPage.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                Вперёд
                <ChevronRight className="ml-1 h-4 w-4" />
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {shopId && (
        <ProductEditDialog
          open={editOpen}
          onOpenChange={setEditOpen}
          shopId={shopId}
          product={editProduct}
        />
      )}
    </div>
  )
}

function ProductRow({ product, onEdit }: { product: Product; onEdit: () => void }) {
  const imageSrc = resolveProductImageUrl(product)

  return (
    <tr className="border-b hover:bg-muted/30">
      <td className="px-2 py-2">
        <div className="flex h-10 w-10 items-center justify-center overflow-hidden rounded border bg-muted/30">
          {imageSrc ? (
            <img src={imageSrc} alt="" className="h-full w-full object-cover" />
          ) : (
            <ImageIcon className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      </td>
      <td className="px-2 py-2">
        <Badge variant={imageStatusVariant(product.imageStatus)}>
          {IMAGE_STATUS_LABELS[product.imageStatus]}
        </Badge>
      </td>
      <td className="px-2 py-2">{product.brand}</td>
      <td className="max-w-[180px] truncate px-2 py-2" title={product.name}>{product.name}</td>
      <td className="px-2 py-2">{product.supplierArticle}</td>
      <td className="px-2 py-2">{product.barcode || '—'}</td>
      <td className="px-2 py-2 whitespace-nowrap">{formatCurrency(toMoney(product.supplierPrice))}</td>
      <td className="px-2 py-2 whitespace-nowrap font-medium">{formatCurrency(toMoney(product.salePrice))}</td>
      <td className="px-2 py-2 whitespace-nowrap">
        {product.oldPrice != null ? formatCurrency(toMoney(product.oldPrice)) : '—'}
      </td>
      <td className="px-2 py-2">{AVAILABILITY_LABELS[product.availabilityMode]}</td>
      <td className="px-2 py-2">{product.stockQuantity ?? '—'}</td>
      <td className="px-2 py-2">
        <BoolBadge value={product.visible} trueLabel="Да" falseLabel="Нет" />
      </td>
      <td className="px-2 py-2">
        <BoolBadge value={product.active} trueLabel="Да" falseLabel="Нет" />
      </td>
      <td className="px-2 py-2">
        <Button variant="ghost" size="icon" onClick={onEdit}>
          <Pencil className="h-4 w-4" />
        </Button>
      </td>
    </tr>
  )
}

function TriStateSelect({
  value,
  onChange,
  trueLabel = 'Да',
  falseLabel = 'Нет',
}: {
  label?: string
  value: TriState
  onChange: (value: TriState) => void
  trueLabel?: string
  falseLabel?: string
}) {
  return (
    <Select value={value} onValueChange={(v) => onChange(v as TriState)}>
      <SelectTrigger>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">Все</SelectItem>
        <SelectItem value="true">{trueLabel}</SelectItem>
        <SelectItem value="false">{falseLabel}</SelectItem>
      </SelectContent>
    </Select>
  )
}
