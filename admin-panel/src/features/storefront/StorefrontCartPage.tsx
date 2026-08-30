import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Minus, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatCurrency, resolveImageUrl } from '@/lib/utils'
import { storefrontApi } from './storefrontApi'
import { useStorefrontCart } from './storefrontStore'
import { getTelegramUser, hideBackButton, showBackButton, triggerHapticFeedback } from './telegramWebApp'
import type { DeliveryType } from './storefrontTypes'

export function StorefrontCartPage() {
  const { shopId = '' } = useParams()
  const navigate = useNavigate()
  const cart = useStorefrontCart()
  const telegramUser = getTelegramUser()

  const [customerName, setCustomerName] = useState(telegramUser?.first_name || '')
  const [customerPhone, setCustomerPhone] = useState('')
  const [deliveryType, setDeliveryType] = useState<DeliveryType>('PICKUP')
  const [deliveryAddress, setDeliveryAddress] = useState('')
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const handler = () => navigate(`/store/${shopId}`)
    showBackButton(handler)
    return () => hideBackButton(handler)
  }, [navigate, shopId])

  const totalPrice = cart.totalPrice()

  const handleSubmit = async () => {
    setError(null)
    if (!cart.lines.length) {
      setError('Корзина пуста')
      return
    }
    if (!customerName.trim() || !customerPhone.trim()) {
      setError('Укажите имя и телефон')
      return
    }
    if (deliveryType === 'COURIER' && !deliveryAddress.trim()) {
      setError('Укажите адрес доставки')
      return
    }

    setSubmitting(true)
    try {
      const order = await storefrontApi.createOrder(shopId, {
        customerName: customerName.trim(),
        customerPhone: customerPhone.trim(),
        deliveryType,
        deliveryAddress: deliveryType === 'COURIER' ? deliveryAddress.trim() : undefined,
        comment: comment.trim() || undefined,
        items: cart.lines.map((line) => ({
          productId: line.productId,
          quantity: line.quantity,
        })),
      })
      cart.clear()
      triggerHapticFeedback('medium')
      navigate(`/store/${shopId}/success/${order.orderId}`, {
        state: { totalToPay: order.totalToPay },
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось оформить заказ')
    } finally {
      setSubmitting(false)
    }
  }

  if (!cart.lines.length) {
    return (
      <div className="storefront-page text-center">
        <p className="text-slate-600">Корзина пуста</p>
        <Button asChild className="mt-4">
          <Link to={`/store/${shopId}`}>В каталог</Link>
        </Button>
      </div>
    )
  }

  return (
    <div className="storefront-page">
      <h1 className="text-xl font-semibold mb-4">Корзина</h1>

      {cart.lines.map((line) => {
        const imageUrl = resolveImageUrl(line.mainImageUrl)
        return (
          <div key={line.productId} className="storefront-line">
            <div className="storefront-line-image">
              {imageUrl ? <img src={imageUrl} alt={line.name} /> : <span className="text-xs p-2">Фото скоро</span>}
            </div>
            <div className="flex-1">
              <div className="text-xs uppercase text-slate-500">{line.brand}</div>
              <div className="font-medium">{line.name}</div>
              <div className="mt-1 font-semibold">{formatCurrency(line.salePrice * line.quantity)}</div>
              <div className="mt-2 flex items-center gap-2">
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => cart.setQuantity(line.productId, line.quantity - 1)}
                >
                  <Minus className="h-4 w-4" />
                </Button>
                <span>{line.quantity}</span>
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => cart.setQuantity(line.productId, line.quantity + 1)}
                >
                  <Plus className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => cart.removeItem(line.productId)}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          </div>
        )
      })}

      <div className="mt-4 flex justify-between text-lg font-semibold">
        <span>Итого</span>
        <span>{formatCurrency(totalPrice)}</span>
      </div>

      <div className="mt-6 space-y-4">
        <div>
          <Label htmlFor="name">Имя</Label>
          <Input id="name" value={customerName} onChange={(e) => setCustomerName(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="phone">Телефон</Label>
          <Input
            id="phone"
            value={customerPhone}
            onChange={(e) => setCustomerPhone(e.target.value)}
            placeholder="+7 900 000 00 00"
          />
        </div>
        <div>
          <Label>Способ получения</Label>
          <div className="mt-2 flex gap-2">
            <Button
              type="button"
              variant={deliveryType === 'PICKUP' ? 'default' : 'outline'}
              onClick={() => setDeliveryType('PICKUP')}
            >
              Самовывоз
            </Button>
            <Button
              type="button"
              variant={deliveryType === 'COURIER' ? 'default' : 'outline'}
              onClick={() => setDeliveryType('COURIER')}
            >
              Доставка
            </Button>
          </div>
        </div>
        {deliveryType === 'COURIER' ? (
          <div>
            <Label htmlFor="address">Адрес</Label>
            <Input
              id="address"
              value={deliveryAddress}
              onChange={(e) => setDeliveryAddress(e.target.value)}
            />
          </div>
        ) : null}
        <div>
          <Label htmlFor="comment">Комментарий</Label>
          <Input id="comment" value={comment} onChange={(e) => setComment(e.target.value)} />
        </div>
      </div>

      {error ? <p className="mt-4 text-sm text-red-600">{error}</p> : null}

      <Button className="mt-6 w-full" size="lg" disabled={submitting} onClick={handleSubmit}>
        {submitting ? 'Оформляем...' : 'Оформить заказ'}
      </Button>
    </div>
  )
}
