import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { CartLine } from './storefrontTypes'

interface StorefrontCartState {
  lines: CartLine[]
  addItem: (line: Omit<CartLine, 'quantity'>, quantity?: number) => void
  setQuantity: (productId: number, quantity: number) => void
  removeItem: (productId: number) => void
  clear: () => void
  totalItems: () => number
  totalPrice: () => number
}

export const useStorefrontCart = create<StorefrontCartState>()(
  persist(
    (set, get) => ({
      lines: [],
      addItem: (line, quantity = 1) => {
        set((state) => {
          const existing = state.lines.find((l) => l.productId === line.productId)
          if (existing) {
            return {
              lines: state.lines.map((l) =>
                l.productId === line.productId
                  ? { ...l, quantity: l.quantity + quantity }
                  : l
              ),
            }
          }
          return { lines: [...state.lines, { ...line, quantity }] }
        })
      },
      setQuantity: (productId, quantity) => {
        if (quantity <= 0) {
          get().removeItem(productId)
          return
        }
        set((state) => ({
          lines: state.lines.map((l) =>
            l.productId === productId ? { ...l, quantity } : l
          ),
        }))
      },
      removeItem: (productId) => {
        set((state) => ({
          lines: state.lines.filter((l) => l.productId !== productId),
        }))
      },
      clear: () => set({ lines: [] }),
      totalItems: () => get().lines.reduce((sum, l) => sum + l.quantity, 0),
      totalPrice: () =>
        get().lines.reduce((sum, l) => sum + l.salePrice * l.quantity, 0),
    }),
    { name: 'storefront-cart' }
  )
)
