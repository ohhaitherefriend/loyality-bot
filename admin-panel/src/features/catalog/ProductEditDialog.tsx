import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Check,
  ImageIcon,
  Loader2,
  Sparkles,
  Upload,
  X,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { AvailabilityMode, ImageStatus, Product, ProductImage } from '@/api/types'
import { cn, resolveImageUrl, resolveProductImageUrl } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Switch } from '@/components/ui/switch'

const AVAILABILITY_LABELS: Record<AvailabilityMode, string> = {
  IN_STOCK: 'В наличии',
  PREORDER: 'Под заказ',
  OUT_OF_STOCK: 'Нет в наличии',
}

const IMAGE_STATUS_LABELS: Record<ImageStatus, string> = {
  MISSING: 'Нет фото',
  CANDIDATE_FOUND: 'Кандидат найден',
  DOWNLOADED: 'Загружено',
  NORMALIZED: 'Нормализовано',
  NEEDS_REVIEW: 'На проверке',
  APPROVED: 'Одобрено',
  REJECTED: 'Отклонено',
  FAILED: 'Ошибка',
}

function toMoney(value: number | string | null | undefined): number | null {
  if (value == null || value === '') return null
  return typeof value === 'number' ? value : Number(value)
}

function imageStatusVariant(status: ImageStatus): 'default' | 'secondary' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
    case 'FAILED':
      return 'destructive'
    case 'NEEDS_REVIEW':
    case 'NORMALIZED':
      return 'warning'
    default:
      return 'secondary'
  }
}

interface ProductEditDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  shopId: string
  product: Product | null
}

export function ProductEditDialog({ open, onOpenChange, shopId, product }: ProductEditDialogProps) {
  const queryClient = useQueryClient()
  const [salePrice, setSalePrice] = useState('')
  const [oldPrice, setOldPrice] = useState('')
  const [visible, setVisible] = useState(false)
  const [active, setActive] = useState(true)
  const [stockQuantity, setStockQuantity] = useState('')
  const [availabilityMode, setAvailabilityMode] = useState<AvailabilityMode>('PREORDER')
  const [description, setDescription] = useState('')
  const [imageUrl, setImageUrl] = useState('')
  const [rejectReason, setRejectReason] = useState('')
  const [selectedImageId, setSelectedImageId] = useState<number | null>(null)

  useEffect(() => {
    if (!product) return
    setSalePrice(product.salePrice != null ? String(product.salePrice) : '')
    setOldPrice(product.oldPrice != null ? String(product.oldPrice) : '')
    setVisible(product.visible)
    setActive(product.active)
    setStockQuantity(product.stockQuantity != null ? String(product.stockQuantity) : '')
    setAvailabilityMode(product.availabilityMode)
    setDescription(product.description ?? '')
    setImageUrl('')
    setRejectReason('')
    setSelectedImageId(null)
  }, [product])

  const { data: images = [], isLoading: imagesLoading } = useQuery({
    queryKey: ['productImages', shopId, product?.id],
    queryFn: () => api.listProductImages(shopId, product!.id),
    enabled: open && !!product,
  })

  useEffect(() => {
    if (images.length > 0 && selectedImageId == null) {
      setSelectedImageId(images[0].id)
    }
  }, [images, selectedImageId])

  const invalidateProductQueries = () => {
    queryClient.invalidateQueries({ queryKey: ['products', shopId] })
    if (product) {
      queryClient.invalidateQueries({ queryKey: ['product', shopId, product.id] })
      queryClient.invalidateQueries({ queryKey: ['productImages', shopId, product.id] })
    }
  }

  const saveMutation = useMutation({
    mutationFn: () =>
      api.updateProduct(shopId, product!.id, {
        salePrice: toMoney(salePrice),
        oldPrice: toMoney(oldPrice),
        visible,
        active,
        stockQuantity: stockQuantity.trim() === '' ? null : Number(stockQuantity),
        availabilityMode,
        description: description.trim() || null,
      }),
    onSuccess: () => {
      invalidateProductQueries()
      toast({ title: 'Товар сохранён' })
      onOpenChange(false)
    },
    onError: (error) => {
      toast({
        variant: 'destructive',
        title: 'Ошибка сохранения',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  const uploadMutation = useMutation({
    mutationFn: (file: File) => api.uploadProductImage(shopId, product!.id, file),
    onSuccess: (image) => {
      invalidateProductQueries()
      setSelectedImageId(image.id)
      toast({ title: 'Изображение загружено' })
    },
    onError: (error) => {
      toast({
        variant: 'destructive',
        title: 'Ошибка загрузки',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  const urlMutation = useMutation({
    mutationFn: () => api.importProductImageFromUrl(shopId, product!.id, imageUrl.trim()),
    onSuccess: (image) => {
      invalidateProductQueries()
      setImageUrl('')
      setSelectedImageId(image.id)
      toast({ title: 'Изображение добавлено по URL' })
    },
    onError: (error) => {
      toast({
        variant: 'destructive',
        title: 'Ошибка импорта URL',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  const imageActionMutation = useMutation({
    mutationFn: (action: 'normalize' | 'approve' | 'reject') => {
      const imageId = selectedImageId!
      if (action === 'normalize') {
        return api.normalizeProductImage(shopId, product!.id, imageId)
      }
      if (action === 'approve') {
        return api.approveProductImage(shopId, product!.id, imageId)
      }
      return api.rejectProductImage(shopId, product!.id, imageId, rejectReason.trim() || undefined)
    },
    onSuccess: () => {
      invalidateProductQueries()
      toast({ title: 'Изображение обновлено' })
    },
    onError: (error) => {
      toast({
        variant: 'destructive',
        title: 'Ошибка',
        description: error instanceof ApiClientError ? error.message : 'Попробуйте позже',
      })
    },
  })

  if (!product) return null

  const mainImageSrc = resolveProductImageUrl(product)
  const selectedImage = images.find((img) => img.id === selectedImageId)
  const previewSrc = selectedImage
    ? resolveImageUrl(selectedImage.normalizedUrl || selectedImage.originalUrl)
    : mainImageSrc
  const isImageBusy = uploadMutation.isPending || urlMutation.isPending || imageActionMutation.isPending

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{product.brand} — {product.name}</DialogTitle>
          <DialogDescription>
            Артикул {product.supplierArticle}
            {product.barcode ? ` · ${product.barcode}` : ''}
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-6 md:grid-cols-2">
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="salePrice">Цена продажи</Label>
              <Input
                id="salePrice"
                type="number"
                min={0}
                value={salePrice}
                onChange={(e) => setSalePrice(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="oldPrice">Старая цена</Label>
              <Input
                id="oldPrice"
                type="number"
                min={0}
                value={oldPrice}
                onChange={(e) => setOldPrice(e.target.value)}
              />
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <Label htmlFor="visible">Видим в каталоге</Label>
              <Switch id="visible" checked={visible} onCheckedChange={setVisible} />
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <Label htmlFor="active">Активен</Label>
              <Switch id="active" checked={active} onCheckedChange={setActive} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="stockQuantity">Остаток</Label>
              <Input
                id="stockQuantity"
                type="number"
                min={0}
                placeholder="Пусто = не задан"
                value={stockQuantity}
                onChange={(e) => setStockQuantity(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>Режим наличия</Label>
              <Select value={availabilityMode} onValueChange={(v) => setAvailabilityMode(v as AvailabilityMode)}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(Object.keys(AVAILABILITY_LABELS) as AvailabilityMode[]).map((mode) => (
                    <SelectItem key={mode} value={mode}>
                      {AVAILABILITY_LABELS[mode]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="description">Описание</Label>
              <textarea
                id="description"
                rows={4}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              />
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <div className="mb-2 flex items-center justify-between">
                <Label>Изображение</Label>
                <Badge variant={imageStatusVariant(product.imageStatus)}>
                  {IMAGE_STATUS_LABELS[product.imageStatus]}
                </Badge>
              </div>
              <div className="flex aspect-square items-center justify-center overflow-hidden rounded-lg border bg-muted/30">
                {previewSrc ? (
                  <img src={previewSrc} alt={product.name} className="max-h-full max-w-full object-contain" />
                ) : (
                  <div className="flex flex-col items-center text-muted-foreground">
                    <ImageIcon className="mb-2 h-10 w-10 opacity-40" />
                    <span className="text-sm">Нет изображения</span>
                  </div>
                )}
              </div>
            </div>

            <Separator />

            <div className="space-y-2">
              <Label>Загрузить файл</Label>
              <Input
                type="file"
                accept="image/*"
                disabled={isImageBusy}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  if (file) uploadMutation.mutate(file)
                  e.target.value = ''
                }}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="imageUrl">URL изображения</Label>
              <div className="flex gap-2">
                <Input
                  id="imageUrl"
                  placeholder="https://..."
                  value={imageUrl}
                  onChange={(e) => setImageUrl(e.target.value)}
                  disabled={isImageBusy}
                />
                <Button
                  type="button"
                  variant="outline"
                  disabled={!imageUrl.trim() || isImageBusy}
                  onClick={() => urlMutation.mutate()}
                >
                  {urlMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                </Button>
              </div>
            </div>

            {imagesLoading && <p className="text-sm text-muted-foreground">Загрузка изображений...</p>}

            {images.length > 0 && (
              <div className="space-y-3">
                <Label>Версии изображений</Label>
                <div className="flex flex-wrap gap-2">
                  {images.map((image) => (
                    <ImageThumb
                      key={image.id}
                      image={image}
                      selected={selectedImageId === image.id}
                      onClick={() => setSelectedImageId(image.id)}
                    />
                  ))}
                </div>

                {selectedImage && (
                  <div className="rounded-lg border p-3 space-y-3">
                    <div className="flex items-center justify-between text-sm">
                      <span>ID {selectedImage.id}</span>
                      <Badge variant={imageStatusVariant(selectedImage.status)}>
                        {IMAGE_STATUS_LABELS[selectedImage.status]}
                      </Badge>
                    </div>
                    {selectedImage.sourceDomain && (
                      <p className="text-xs text-muted-foreground">Источник: {selectedImage.sourceDomain}</p>
                    )}
                    {selectedImage.rankerReason && (
                      <p className="text-xs">Ranker: {selectedImage.rankerReason}</p>
                    )}
                    {selectedImage.visualQualityScore != null && (
                      <p className="text-xs">Quality score: {selectedImage.visualQualityScore}</p>
                    )}
                    {selectedImage.normalizationProvider && (
                      <p className="text-xs">
                        Normalization: {selectedImage.normalizationProvider}
                        {selectedImage.backgroundRemoved ? ', bg removed' : ', fallback bg'}
                      </p>
                    )}
                    <div className="flex flex-wrap gap-2">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={isImageBusy}
                        onClick={() => imageActionMutation.mutate('normalize')}
                      >
                        <Sparkles className="mr-1 h-3 w-3" />
                        Нормализовать
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={isImageBusy}
                        onClick={() => imageActionMutation.mutate('approve')}
                      >
                        <Check className="mr-1 h-3 w-3" />
                        Одобрить
                      </Button>
                    </div>
                    <div className="space-y-2">
                      <Input
                        placeholder="Причина отклонения (необязательно)"
                        value={rejectReason}
                        onChange={(e) => setRejectReason(e.target.value)}
                        disabled={isImageBusy}
                      />
                      <Button
                        type="button"
                        size="sm"
                        variant="destructive"
                        disabled={isImageBusy}
                        onClick={() => imageActionMutation.mutate('reject')}
                      >
                        <X className="mr-1 h-3 w-3" />
                        Отклонить
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Отмена
          </Button>
          <Button type="button" disabled={saveMutation.isPending} onClick={() => saveMutation.mutate()}>
            {saveMutation.isPending ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Сохранение...
              </>
            ) : (
              'Сохранить'
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ImageThumb({
  image,
  selected,
  onClick,
}: {
  image: ProductImage
  selected: boolean
  onClick: () => void
}) {
  const src = resolveImageUrl(image.normalizedUrl || image.originalUrl)
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'h-16 w-16 overflow-hidden rounded-md border bg-muted/30',
        selected && 'ring-2 ring-primary'
      )}
    >
      {src ? (
        <img src={src} alt="" className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full w-full items-center justify-center">
          <ImageIcon className="h-5 w-5 text-muted-foreground" />
        </div>
      )}
    </button>
  )
}

export { IMAGE_STATUS_LABELS, AVAILABILITY_LABELS, imageStatusVariant }
