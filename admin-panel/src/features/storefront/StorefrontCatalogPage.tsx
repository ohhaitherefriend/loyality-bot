import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCurrency, resolveImageUrl } from '@/lib/utils'
import { storefrontApi } from './storefrontApi'
import { useStorefrontCart } from './storefrontStore'
import { triggerHapticFeedback } from './telegramWebApp'
import type { StorefrontProduct } from './storefrontTypes'

function availabilityLabel(mode: StorefrontProduct['availabilityMode']) {
  switch (mode) {
    case 'IN_STOCK':
      return 'В наличии'
    case 'PREORDER':
      return 'Под заказ'
    default:
      return 'Недоступен'
  }
}

export function StorefrontCatalogPage() {
  const { shopId = '' } = useParams()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [brand, setBrand] = useState('')
  const [page, setPage] = useState(0)
  const cart = useStorefrontCart()

  const settingsQuery = useQuery({
    queryKey: ['storefront-settings', shopId],
    queryFn: () => storefrontApi.getSettings(shopId),
    enabled: !!shopId,
  })

  const brandsQuery = useQuery({
    queryKey: ['storefront-brands', shopId],
    queryFn: () => storefrontApi.listBrands(shopId),
    enabled: !!shopId,
  })

  const productsQuery = useQuery({
    queryKey: ['storefront-products', shopId, page, query, brand],
    queryFn: () => storefrontApi.listProducts(shopId, { page, size: 20, query, brand }),
    enabled: !!shopId,
  })

  const totalItems = cart.totalItems()
  const totalPrice = cart.totalPrice()

  const handleAdd = (product: StorefrontProduct) => {
    cart.addItem({
      productId: product.id,
      brand: product.brand,
      name: product.name,
      salePrice: Number(product.salePrice),
      mainImageUrl: product.mainImageUrl,
    })
    triggerHapticFeedback('light')
  }

  return (
    <>
      <div className="storefront-header">
        <div className="text-lg font-semibold">
          {settingsQuery.data?.shopName || 'Магазин'}
        </div>
        <div className="relative mt-3">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input
            value={query}
            onChange={(e) => {
              setPage(0)
              setQuery(e.target.value)
            }}
            placeholder="Поиск по названию или бренду"
            className="pl-9"
          />
        </div>
        {brandsQuery.data?.brands?.length ? (
          <div className="storefront-chip-row mt-3">
            <button
              type="button"
              className={`storefront-chip ${brand === '' ? 'active' : ''}`}
              onClick={() => {
                setBrand('')
                setPage(0)
              }}
            >
              Все
            </button>
            {brandsQuery.data.brands.map((item) => (
              <button
                key={item}
                type="button"
                className={`storefront-chip ${brand === item ? 'active' : ''}`}
                onClick={() => {
                  setBrand(item)
                  setPage(0)
                }}
              >
                {item}
              </button>
            ))}
          </div>
        ) : null}
      </div>

      <div className="storefront-grid">
        {productsQuery.isLoading
          ? Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-64 rounded-2xl" />
            ))
          : productsQuery.data?.content.map((product) => {
              const imageUrl = resolveImageUrl(product.mainImageUrl)
              return (
                <div key={product.id} className="storefront-card">
                  <Link to={`/store/${shopId}/products/${product.id}`}>
                    <div className="storefront-image">
                      {imageUrl ? (
                        <img src={imageUrl} alt={product.name} />
                      ) : (
                        <span>Фото скоро</span>
                      )}
                    </div>
                  </Link>
                  <div className="storefront-card-body">
                    <div className="storefront-brand">{product.brand}</div>
                    <Link to={`/store/${shopId}/products/${product.id}`} className="storefront-name">
                      {product.shortName || product.name}
                    </Link>
                    <div>
                      <div className="storefront-price">{formatCurrency(Number(product.salePrice))}</div>
                      {product.oldPrice ? (
                        <div className="storefront-old-price">
                          {formatCurrency(Number(product.oldPrice))}
                        </div>
                      ) : null}
                    </div>
                    <div className="text-xs text-slate-500">{availabilityLabel(product.availabilityMode)}</div>
                    <Button size="sm" className="mt-auto w-full" onClick={() => handleAdd(product)}>
                      В корзину
                    </Button>
                  </div>
                </div>
              )
            })}
      </div>

      {productsQuery.data && productsQuery.data.totalPages > 1 ? (
        <div className="flex justify-center gap-2 px-4 pb-24">
          <Button
            variant="outline"
            disabled={page <= 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            Назад
          </Button>
          <Button
            variant="outline"
            disabled={page + 1 >= productsQuery.data.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Далее
          </Button>
        </div>
      ) : null}

      {totalItems > 0 ? (
        <button
          type="button"
          className="storefront-cart-bar"
          onClick={() => navigate(`/store/${shopId}/cart`)}
        >
          <span>Корзина · {totalItems}</span>
          <span>{formatCurrency(totalPrice)}</span>
        </button>
      ) : null}
    </>
  )
}
