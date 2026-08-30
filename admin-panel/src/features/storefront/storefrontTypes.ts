export type AvailabilityMode = 'IN_STOCK' | 'PREORDER' | 'OUT_OF_STOCK'
export type ImageStatus = 'MISSING' | 'APPROVED' | 'PENDING' | 'REJECTED' | 'FAILED' | 'SEARCHING' | 'NORMALIZING' | 'CANDIDATE'
export type DeliveryType = 'PICKUP' | 'COURIER' | 'CDEK' | 'OTHER'

export interface StorefrontSettings {
  shopId: string
  shopName: string
  bonusesEnabled: boolean
  bonusCashbackPercent?: number | null
  pickupHint: string
}

export interface StorefrontProduct {
  id: number
  brand: string
  name: string
  shortName: string
  description?: string | null
  salePrice: number
  oldPrice?: number | null
  currency: string
  availabilityMode: AvailabilityMode
  stockQuantity?: number | null
  mainImageUrl?: string | null
  imageStatus: ImageStatus
  categoryPath?: string | null
}

export interface StorefrontProductPage {
  content: StorefrontProduct[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface StorefrontOrderItemRequest {
  productId: number
  quantity: number
}

export interface StorefrontOrderRequest {
  customerName: string
  customerPhone: string
  deliveryType: DeliveryType
  deliveryAddress?: string
  comment?: string
  items: StorefrontOrderItemRequest[]
}

export interface StorefrontOrderResponse {
  orderId: number
  status: string
  itemsTotal: number
  totalToPay: number
}

export interface CartLine {
  productId: number
  quantity: number
  brand: string
  name: string
  salePrice: number
  mainImageUrl?: string | null
}
