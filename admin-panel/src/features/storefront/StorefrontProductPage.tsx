import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Minus, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCurrency, resolveImageUrl } from '@/lib/utils'
import { storefrontApi } from './storefrontApi'
import { useStorefrontCart } from './storefrontStore'
import { hideBackButton, showBackButton, triggerHapticFeedback } from './telegramWebApp'

export function StorefrontProductPage() {
  const { shopId = '', productId = '' } = useParams()
  const navigate = useNavigate()
  const cart = useStorefrontCart()
  const [quantity, setQuantity] = useState(1)

  const productQuery = useQuery({
    queryKey: ['storefront-product', shopId, productId],
    queryFn: () => storefrontApi.getProduct(shopId, Number(productId)),
    enabled: !!shopId && !!productId,
  })

  useEffect(() => {
    const handler = () => navigate(`/store/${shopId}`)
    showBackButton(handler)
    return () => hideBackButton(handler)
  }, [navigate, shopId])

  if (productQuery.isLoading) {
    return <div className="storefront-page"><Skeleton className="h-80 w-full rounded-2xl" /></div>
  }

  if (!productQuery.data) {
    return (
      <div className="storefront-page">
        <p>Товар не найден</p>
        <Button asChild className="mt-4">
          <Link to={`/store/${shopId}`}>В каталог</Link>
        </Button>
      </div>
    )
  }

  const product = productQuery.data
  const imageUrl = resolveImageUrl(product.mainImageUrl)

  const handleAdd = () => {
    cart.addItem(
      {
        productId: product.id,
        brand: product.brand,
        name: product.name,
        salePrice: Number(product.salePrice),
        mainImageUrl: product.mainImageUrl,
      },
      quantity
    )
    triggerHapticFeedback('medium')
    navigate(`/store/${shopId}/cart`)
  }

  return (
    <div className="storefront-page">
      <div className="storefront-image rounded-2xl overflow-hidden mb-4" style={{ aspectRatio: '1' }}>
        {imageUrl ? <img src={imageUrl} alt={product.name} /> : <span>Фото скоро</span>}
      </div>
      <div className="text-sm uppercase tracking-wide text-slate-500">{product.brand}</div>
      <h1 className="text-xl font-semibold mt-1">{product.name}</h1>
      <div className="mt-3 text-2xl font-bold">{formatCurrency(Number(product.salePrice))}</div>
      {product.oldPrice ? (
        <div className="text-slate-500 line-through">{formatCurrency(Number(product.oldPrice))}</div>
      ) : null}
      {product.description ? (
        <p className="mt-4 text-sm text-slate-600 whitespace-pre-wrap">{product.description}</p>
      ) : null}

      <div className="mt-6 flex items-center gap-3">
        <Button variant="outline" size="icon" onClick={() => setQuantity((q) => Math.max(1, q - 1))}>
          <Minus className="h-4 w-4" />
        </Button>
        <span className="min-w-8 text-center font-medium">{quantity}</span>
        <Button variant="outline" size="icon" onClick={() => setQuantity((q) => q + 1)}>
          <Plus className="h-4 w-4" />
        </Button>
      </div>

      <Button className="mt-6 w-full" size="lg" onClick={handleAdd}>
        Добавить в корзину
      </Button>
    </div>
  )
}
