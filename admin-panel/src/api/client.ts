import type {
  ConnectBotRequest,
  ConnectBotResponse,
  BotInfoResponse,
  BotListItem,
  ShopSettings,
  UpdateSettingsRequest,
  DeepLinkResponse,
  PlatformStats,
  UpdateWebhookRequest,
  ApiError,
  WeeklyReport,
  RegisterRequest,
  LoginRequest,
  AuthResponse,
  MeResponse,
  SubscriptionResponse,
  PlanDto,
  PaymentConfigResponse,
  OnboardingResponse,
  CreateShopRequest,
  ConnectBotOnboardingRequest,
  ApplyTemplateRequest,
  CompleteOnboardingRequest,
  OrderDetails,
  OrderPageResponse,
  OrderStatus,
  OrderStatusUpdateRequest,
  Product,
  ProductImage,
  ProductImportResponse,
  ProductListParams,
  ProductPageResponse,
  ProductUpdateRequest,
  CatalogImportParams,
  BulkImageSearchRequest,
  BulkImageSearchResponse,
  ImageSearchStatus,
} from './types'

// ========== Configuration ==========

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

// Token storage (in-memory only, never localStorage for security)
let authToken: string | null = null

export function setAuthToken(token: string | null) {
  authToken = token
}

export function getAuthToken(): string | null {
  return authToken
}

// ========== HTTP Client ==========

class ApiClient {
  private baseUrl: string

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`
    
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...options.headers,
    }

    // Add auth token if present
    if (authToken) {
      (headers as Record<string, string>)['Authorization'] = `Bearer ${authToken}`
    }

    const response = await fetch(url, {
      ...options,
      headers,
      credentials: 'include', // For cookie-based auth
    })

    // Handle 401 - Unauthorized
    if (response.status === 401) {
      authToken = null
      window.dispatchEvent(new CustomEvent('auth:unauthorized'))
      throw new ApiClientError('Unauthorized', 401)
    }

    // Handle error responses
    if (!response.ok) {
      let errorMessage = `HTTP ${response.status}`
      try {
        const errorData = await response.json()
        errorMessage = errorData.message || errorData.error || errorMessage
      } catch {
        // Ignore JSON parse errors
      }
      throw new ApiClientError(errorMessage, response.status)
    }

    // Handle empty responses
    const text = await response.text()
    if (!text) {
      return undefined as T
    }

    return JSON.parse(text) as T
  }

  private async uploadRequest<T>(endpoint: string, formData: FormData): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`
    const headers: HeadersInit = {}

    if (authToken) {
      (headers as Record<string, string>)['Authorization'] = `Bearer ${authToken}`
    }

    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: formData,
      credentials: 'include',
    })

    if (response.status === 401) {
      authToken = null
      window.dispatchEvent(new CustomEvent('auth:unauthorized'))
      throw new ApiClientError('Unauthorized', 401)
    }

    if (!response.ok) {
      let errorMessage = `HTTP ${response.status}`
      try {
        const errorData = await response.json()
        errorMessage = errorData.message || errorData.error || errorMessage
      } catch {
        // Ignore JSON parse errors
      }
      throw new ApiClientError(errorMessage, response.status)
    }

    const text = await response.text()
    if (!text) {
      return undefined as T
    }

    return JSON.parse(text) as T
  }

  // ========== Auth Endpoints ==========

  async register(request: RegisterRequest): Promise<AuthResponse> {
    return this.request<AuthResponse>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async login(request: LoginRequest): Promise<AuthResponse> {
    return this.request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async me(): Promise<MeResponse> {
    return this.request<MeResponse>('/api/auth/me')
  }

  // ========== Billing Endpoints ==========

  async getSubscription(shopId: string): Promise<SubscriptionResponse> {
    return this.request<SubscriptionResponse>(`/api/billing/subscription?shopId=${shopId}`)
  }

  async activateStub(shopId: string, planCode: string = 'BASIC_MONTHLY'): Promise<SubscriptionResponse> {
    return this.request<SubscriptionResponse>(`/api/billing/activate-stub?shopId=${shopId}&planCode=${planCode}`, {
      method: 'POST',
    })
  }

  async extendTrial(shopId: string, days: number = 7): Promise<SubscriptionResponse> {
    return this.request<SubscriptionResponse>(`/api/billing/extend-trial?shopId=${shopId}&days=${days}`, {
      method: 'POST',
    })
  }

  async getPlans(): Promise<PlanDto[]> {
    return this.request<PlanDto[]>('/api/billing/plans')
  }

  async getPaymentConfig(shopId: string, planCode: string = 'BASIC_MONTHLY'): Promise<PaymentConfigResponse> {
    return this.request<PaymentConfigResponse>(`/api/billing/payment-config?shopId=${shopId}&planCode=${planCode}`)
  }

  async confirmPayment(shopId: string, planCode: string = 'BASIC_MONTHLY'): Promise<SubscriptionResponse> {
    return this.request<SubscriptionResponse>(`/api/billing/confirm-payment?shopId=${shopId}&planCode=${planCode}`, {
      method: 'POST',
    })
  }

  // ========== Onboarding Endpoints ==========

  async startOnboarding(): Promise<OnboardingResponse> {
    return this.request<OnboardingResponse>('/api/onboarding/start', {
      method: 'POST',
    })
  }

  async getOnboardingState(): Promise<OnboardingResponse> {
    return this.request<OnboardingResponse>('/api/onboarding/state')
  }

  async createShopOnboarding(request: CreateShopRequest): Promise<OnboardingResponse> {
    return this.request<OnboardingResponse>('/api/onboarding/create-shop', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async connectBotOnboarding(request: ConnectBotOnboardingRequest): Promise<OnboardingResponse> {
    return this.request<OnboardingResponse>('/api/onboarding/connect-bot', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async applyTemplateOnboarding(request: ApplyTemplateRequest): Promise<OnboardingResponse> {
    return this.request<OnboardingResponse>('/api/onboarding/apply-template', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async completeOnboarding(request: CompleteOnboardingRequest): Promise<OnboardingResponse> {
    return this.request<OnboardingResponse>('/api/onboarding/complete', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  // ========== Bot Endpoints ==========

  async connectBot(request: ConnectBotRequest): Promise<ConnectBotResponse> {
    return this.request<ConnectBotResponse>('/api/bots/connect', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async getBotInfo(botId: number): Promise<BotInfoResponse> {
    return this.request<BotInfoResponse>(`/api/bots/${botId}`)
  }

  async listBots(): Promise<BotListItem[]> {
    return this.request<BotListItem[]>('/api/bots')
  }

  async disconnectBot(botId: number): Promise<void> {
    return this.request<void>(`/api/bots/${botId}`, {
      method: 'DELETE',
    })
  }

  async updateWebhook(botId: number, request: UpdateWebhookRequest): Promise<void> {
    return this.request<void>(`/api/bots/${botId}/webhook`, {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  // ========== Shop Settings Endpoints ==========

  async getShopSettings(shopId: string): Promise<ShopSettings> {
    return this.request<ShopSettings>(`/api/shops/${shopId}/settings`)
  }

  async updateShopSettings(
    shopId: string,
    request: UpdateSettingsRequest
  ): Promise<ShopSettings> {
    return this.request<ShopSettings>(`/api/shops/${shopId}/settings`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  // ========== Deep Links Endpoints ==========

  async getDeepLinks(shopId: string, locationId?: string): Promise<DeepLinkResponse> {
    const params = locationId ? `?locationId=${locationId}` : ''
    return this.request<DeepLinkResponse>(`/api/shops/${shopId}/deeplink${params}`)
  }

  // ========== Stats Endpoints ==========

  async getPlatformStats(): Promise<PlatformStats> {
    return this.request<PlatformStats>('/api/stats')
  }

  // ========== Reports Endpoints ==========

  async getWeeklyReport(shopId: string): Promise<WeeklyReport> {
    return this.request<WeeklyReport>(`/api/shops/${shopId}/reports/weekly`)
  }

  async getDailyReport(shopId: string): Promise<WeeklyReport> {
    return this.request<WeeklyReport>(`/api/shops/${shopId}/reports/daily`)
  }

  async getMonthlyReport(shopId: string): Promise<WeeklyReport> {
    return this.request<WeeklyReport>(`/api/shops/${shopId}/reports/monthly`)
  }

  // ========== Commerce Orders ==========

  async listOrders(
    shopId: string,
    params?: { status?: OrderStatus; page?: number; size?: number }
  ): Promise<OrderPageResponse> {
    const search = new URLSearchParams()
    if (params?.status) search.set('status', params.status)
    if (params?.page != null) search.set('page', String(params.page))
    if (params?.size != null) search.set('size', String(params.size))
    const query = search.toString()
    return this.request<OrderPageResponse>(
      `/api/shops/${shopId}/orders${query ? `?${query}` : ''}`
    )
  }

  async getOrder(shopId: string, orderId: number): Promise<OrderDetails> {
    return this.request<OrderDetails>(`/api/shops/${shopId}/orders/${orderId}`)
  }

  async updateOrderStatus(
    shopId: string,
    orderId: number,
    request: OrderStatusUpdateRequest
  ): Promise<OrderDetails> {
    return this.request<OrderDetails>(`/api/shops/${shopId}/orders/${orderId}/status`, {
      method: 'PATCH',
      body: JSON.stringify(request),
    })
  }

  // ========== Commerce Catalog ==========

  async listProducts(shopId: string, params?: ProductListParams): Promise<ProductPageResponse> {
    const search = new URLSearchParams()
    if (params?.page != null) search.set('page', String(params.page))
    if (params?.size != null) search.set('size', String(params.size))
    if (params?.query) search.set('query', params.query)
    if (params?.brand) search.set('brand', params.brand)
    if (params?.visible != null) search.set('visible', String(params.visible))
    if (params?.active != null) search.set('active', String(params.active))
    if (params?.missingImages != null) search.set('missingImages', String(params.missingImages))
    const query = search.toString()
    return this.request<ProductPageResponse>(
      `/api/shops/${shopId}/products${query ? `?${query}` : ''}`
    )
  }

  async getProduct(shopId: string, productId: number): Promise<Product> {
    return this.request<Product>(`/api/shops/${shopId}/products/${productId}`)
  }

  async updateProduct(
    shopId: string,
    productId: number,
    request: ProductUpdateRequest
  ): Promise<Product> {
    return this.request<Product>(`/api/shops/${shopId}/products/${productId}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  async listBrands(shopId: string): Promise<string[]> {
    return this.request<string[]>(`/api/shops/${shopId}/products/brands`)
  }

  async importCatalog(shopId: string, params: CatalogImportParams): Promise<ProductImportResponse> {
    const formData = new FormData()
    formData.append('file', params.file)
    if (params.defaultMarkupPercent != null) {
      formData.append('defaultMarkupPercent', String(params.defaultMarkupPercent))
    }
    formData.append('makeImportedVisible', String(params.makeImportedVisible ?? false))
    formData.append('overwriteManualFields', String(params.overwriteManualFields ?? false))
    return this.uploadRequest<ProductImportResponse>(`/api/shops/${shopId}/catalog/import`, formData)
  }

  async listProductImages(shopId: string, productId: number): Promise<ProductImage[]> {
    return this.request<ProductImage[]>(`/api/shops/${shopId}/products/${productId}/images`)
  }

  async uploadProductImage(
    shopId: string,
    productId: number,
    file: File
  ): Promise<ProductImage> {
    const formData = new FormData()
    formData.append('file', file)
    return this.uploadRequest<ProductImage>(
      `/api/shops/${shopId}/products/${productId}/images/upload`,
      formData
    )
  }

  async importProductImageFromUrl(
    shopId: string,
    productId: number,
    url: string
  ): Promise<ProductImage> {
    return this.request<ProductImage>(`/api/shops/${shopId}/products/${productId}/images/from-url`, {
      method: 'POST',
      body: JSON.stringify({ url }),
    })
  }

  async normalizeProductImage(
    shopId: string,
    productId: number,
    imageId: number
  ): Promise<ProductImage> {
    return this.request<ProductImage>(
      `/api/shops/${shopId}/products/${productId}/images/${imageId}/normalize`,
      { method: 'POST' }
    )
  }

  async approveProductImage(
    shopId: string,
    productId: number,
    imageId: number
  ): Promise<ProductImage> {
    return this.request<ProductImage>(
      `/api/shops/${shopId}/products/${productId}/images/${imageId}/approve`,
      { method: 'POST' }
    )
  }

  async rejectProductImage(
    shopId: string,
    productId: number,
    imageId: number,
    reason?: string
  ): Promise<ProductImage> {
    return this.request<ProductImage>(
      `/api/shops/${shopId}/products/${productId}/images/${imageId}/reject`,
      {
        method: 'POST',
        body: JSON.stringify({ reason: reason ?? null }),
      }
    )
  }

  async getImageSearchStatus(shopId: string): Promise<ImageSearchStatus> {
    return this.request<ImageSearchStatus>(`/api/shops/${shopId}/products/images/search-status`)
  }

  async searchImagesBulk(
    shopId: string,
    request: BulkImageSearchRequest
  ): Promise<BulkImageSearchResponse> {
    return this.request<BulkImageSearchResponse>(
      `/api/shops/${shopId}/products/images/search-bulk`,
      {
        method: 'POST',
        body: JSON.stringify(request),
      }
    )
  }
}

// ========== Error Class ==========

export class ApiClientError extends Error implements ApiError {
  status?: number
  code?: string

  constructor(message: string, status?: number, code?: string) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.code = code
  }
}

// ========== Singleton Instance ==========

export const api = new ApiClient(API_BASE_URL)

