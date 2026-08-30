import { getInitData } from './telegramWebApp'
import type {
  StorefrontOrderRequest,
  StorefrontOrderResponse,
  StorefrontProduct,
  StorefrontProductPage,
  StorefrontSettings,
} from './storefrontTypes'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

const SESSION_KEY = 'storefront-session-id'

function getSessionId(): string {
  let id = localStorage.getItem(SESSION_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(SESSION_KEY, id)
  }
  return id
}

async function request<T>(shopId: string, path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (!(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  const initData = getInitData()
  if (initData) {
    headers.set('X-Telegram-Init-Data', initData)
  }
  headers.set('X-Storefront-Session-Id', getSessionId())

  const response = await fetch(`${API_BASE}/api/storefront/${shopId}${path}`, {
    ...options,
    headers,
  })

  if (!response.ok) {
    let message = `Request failed: ${response.status}`
    try {
      const body = await response.json()
      if (body?.error) message = body.error
    } catch {
      // ignore
    }
    throw new Error(message)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return response.json() as Promise<T>
}

export const storefrontApi = {
  getSettings(shopId: string) {
    return request<StorefrontSettings>(shopId, '/settings')
  },

  listProducts(
    shopId: string,
    params: { page?: number; size?: number; query?: string; brand?: string; category?: string } = {}
  ) {
    const search = new URLSearchParams()
    if (params.page != null) search.set('page', String(params.page))
    if (params.size != null) search.set('size', String(params.size))
    if (params.query) search.set('query', params.query)
    if (params.brand) search.set('brand', params.brand)
    if (params.category) search.set('category', params.category)
    const qs = search.toString()
    return request<StorefrontProductPage>(shopId, `/products${qs ? `?${qs}` : ''}`)
  },

  getProduct(shopId: string, productId: number) {
    return request<StorefrontProduct>(shopId, `/products/${productId}`)
  },

  listBrands(shopId: string) {
    return request<{ brands: string[] }>(shopId, '/brands')
  },

  createOrder(shopId: string, body: StorefrontOrderRequest) {
    return request<StorefrontOrderResponse>(shopId, '/orders', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}
