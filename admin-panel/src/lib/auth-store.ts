import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { api, setAuthToken } from '@/api/client'
import type { UserDto, ShopDto, OnboardingDto } from '@/api/types'

interface AuthState {
  // User data
  user: UserDto | null
  shops: ShopDto[]
  needsOnboarding: boolean
  onboarding: OnboardingDto | null
  
  // Auth state
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
  
  // Actions
  login: (email: string, password: string) => Promise<boolean>
  register: (email: string, password: string, name?: string) => Promise<boolean>
  logout: () => void
  checkAuth: () => Promise<boolean>
  setShops: (shops: ShopDto[]) => void
  setOnboarding: (onboarding: OnboardingDto | null) => void
  clearError: () => void
}

// Restore token from sessionStorage on init (sessionStorage is tab-scoped and cleared on close)
const storedToken = sessionStorage.getItem('auth-token')
if (storedToken) {
  setAuthToken(storedToken)
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      user: null,
      shops: [],
      needsOnboarding: false,
      onboarding: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
      
      login: async (email: string, password: string) => {
        set({ isLoading: true, error: null })
        
        try {
          const response = await api.login({ email, password })
          
          if (!response.success || !response.token) {
            set({ 
              isLoading: false, 
              error: response.error || 'Ошибка входа'
            })
            return false
          }
          
          setAuthToken(response.token)
          sessionStorage.setItem('auth-token', response.token)
          
          set({
            user: response.user || null,
            shops: response.shops || [],
            needsOnboarding: response.needsOnboarding || false,
            isAuthenticated: true,
            isLoading: false,
            error: null,
          })
          
          return true
        } catch (err) {
          const message = err instanceof Error ? err.message : 'Ошибка входа'
          set({ isLoading: false, error: message })
          return false
        }
      },
      
      register: async (email: string, password: string, name?: string) => {
        set({ isLoading: true, error: null })
        
        try {
          const response = await api.register({ email, password, name })
          
          if (!response.success || !response.token) {
            set({ 
              isLoading: false, 
              error: response.error || 'Ошибка регистрации'
            })
            return false
          }
          
          setAuthToken(response.token)
          sessionStorage.setItem('auth-token', response.token)
          
          set({
            user: response.user || null,
            shops: response.shops || [],
            needsOnboarding: true,
            isAuthenticated: true,
            isLoading: false,
            error: null,
          })
          
          return true
        } catch (err) {
          const message = err instanceof Error ? err.message : 'Ошибка регистрации'
          set({ isLoading: false, error: message })
          return false
        }
      },
      
      logout: () => {
        setAuthToken(null)
        sessionStorage.removeItem('auth-token')
        
        set({
          user: null,
          shops: [],
          needsOnboarding: false,
          onboarding: null,
          isAuthenticated: false,
          error: null,
        })
      },
      
      checkAuth: async () => {
        const token = sessionStorage.getItem('auth-token')
        
        if (!token) {
          set({ isAuthenticated: false })
          return false
        }
        
        setAuthToken(token)
        set({ isLoading: true })
        
        try {
          const response = await api.me()
          
          set({
            user: response.user,
            shops: response.shops || [],
            needsOnboarding: response.needsOnboarding,
            onboarding: response.onboarding || null,
            isAuthenticated: true,
            isLoading: false,
          })
          
          return true
        } catch (err) {
          setAuthToken(null)
          sessionStorage.removeItem('auth-token')
          
          set({
            user: null,
            shops: [],
            isAuthenticated: false,
            isLoading: false,
          })
          
          return false
        }
      },
      
      setShops: (shops: ShopDto[]) => {
        set({ shops })
      },
      
      setOnboarding: (onboarding: OnboardingDto | null) => {
        set({ 
          onboarding,
          needsOnboarding: onboarding ? !onboarding.completed : false
        })
      },
      
      clearError: () => {
        set({ error: null })
      },
    }),
    {
      name: 'auth-store',
      storage: {
        getItem: (name) => {
          const value = sessionStorage.getItem(name)
          return value ? JSON.parse(value) : null
        },
        setItem: (name, value) => {
          sessionStorage.setItem(name, JSON.stringify(value))
        },
        removeItem: (name) => {
          sessionStorage.removeItem(name)
        },
      },
      partialize: (state) => ({
        user: state.user,
        shops: state.shops,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
)

// Listen for unauthorized events from API client
if (typeof window !== 'undefined') {
  window.addEventListener('auth:unauthorized', () => {
    useAuthStore.getState().logout()
  })
}
