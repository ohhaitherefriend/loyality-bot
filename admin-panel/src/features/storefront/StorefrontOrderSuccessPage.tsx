import { Link, useLocation, useParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { formatCurrency } from '@/lib/utils'

export function StorefrontOrderSuccessPage() {
  const { shopId = '', orderId = '' } = useParams()
  const location = useLocation()
  const totalToPay = (location.state as { totalToPay?: number } | null)?.totalToPay

  return (
    <div className="storefront-success">
      <div className="text-5xl mb-4">✅</div>
      <h1 className="text-2xl font-semibold">Заказ создан</h1>
      <p className="mt-3 text-slate-600">
        Номер заказа: <strong>#{orderId}</strong>
      </p>
      {totalToPay != null ? (
        <p className="mt-2 text-lg font-semibold">{formatCurrency(totalToPay)}</p>
      ) : null}
      <p className="mt-4 text-sm text-slate-600">
        Заказ создан. Мы свяжемся с вами для подтверждения.
      </p>
      <Button asChild className="mt-8 w-full max-w-xs">
        <Link to={`/store/${shopId}`}>Вернуться в каталог</Link>
      </Button>
    </div>
  )
}
