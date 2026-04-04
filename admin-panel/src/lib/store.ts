import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { MessengerPlatform } from '@/api/types'

interface ShopState {
  shopId: string | null
  botInstanceId: number | null
  botUsername: string | null
  businessName: string | null
  platform: MessengerPlatform | null
  
  setShop: (data: {
    shopId: string
    botInstanceId: number
    botUsername: string
    businessName?: string
    platform?: MessengerPlatform
  }) => void
  
  clearShop: () => void
}

// Note: We only store shop identifiers, never sensitive data like bot tokens
export const useShopStore = create<ShopState>()(
  persist(
    (set) => ({
      shopId: null,
      botInstanceId: null,
      botUsername: null,
      businessName: null,
      platform: null,
      
      setShop: (data) => set({
        shopId: data.shopId,
        botInstanceId: data.botInstanceId,
        botUsername: data.botUsername,
        businessName: data.businessName || null,
        platform: data.platform || 'TELEGRAM',
      }),
      
      clearShop: () => set({
        shopId: null,
        botInstanceId: null,
        botUsername: null,
        businessName: null,
        platform: null,
      }),
    }),
    {
      name: 'loyalty-shop-store',
      partialize: (state) => ({
        shopId: state.shopId,
        botInstanceId: state.botInstanceId,
        botUsername: state.botUsername,
        businessName: state.businessName,
        platform: state.platform,
      }),
    }
  )
)

