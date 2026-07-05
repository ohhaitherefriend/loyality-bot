// ========== Enums ==========

export type MessengerPlatform = 'TELEGRAM' | 'MAX'

export type BusinessType = 'COFFEE' | 'RETAIL' | 'SERVICE' | 'HYBRID'

export type LoyaltyMode = 'STAMPS' | 'BONUS' | 'CUMULATIVE_DISCOUNT'

export type BotStatus = 'PENDING' | 'CONNECTING' | 'ACTIVE' | 'ERROR' | 'DISABLED'

export type FastCheckoutType = 'STAMP' | 'FIXED_POINTS'

export type SubscriptionStatus = 'TRIALING' | 'ACTIVE' | 'EXPIRED' | 'CANCELLED' | 'PAST_DUE'

export type OnboardingStep = 'START' | 'SHOP_CREATED' | 'BOT_CONNECTED' | 'SETTINGS_DONE' | 'COMPLETED'

// ========== Auth DTOs ==========

export interface RegisterRequest {
  email: string
  password: string
  name?: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface AuthResponse {
  success: boolean
  token?: string
  user?: UserDto
  shops?: ShopDto[]
  needsOnboarding?: boolean
  error?: string
}

export interface MeResponse {
  user: UserDto
  shops: ShopDto[]
  needsOnboarding: boolean
  onboarding?: OnboardingDto
}

export interface UserDto {
  id: number
  email: string
  name?: string
}

export interface ShopDto {
  id: number
  shopId: string
  name: string
  timezone?: string
}

export interface OnboardingDto {
  id: number
  step: OnboardingStep
  shopId?: string
  completed: boolean
}

// ========== Billing DTOs ==========

export interface SubscriptionResponse {
  shopId?: string
  status: string
  planCode?: string
  daysLeft: number
  trialStartAt?: string
  trialEndAt?: string
  currentPeriodStartAt?: string
  currentPeriodEndAt?: string
  freeForever: boolean
  accessGranted: boolean
}

export interface PaymentConfigResponse {
  publicId: string
  amount: number
  currency: string
  invoiceId: string
  description: string
  accountId: string
  recurrentInterval: string
  recurrentPeriod: number
}

export interface PlanDto {
  code: string
  name: string
  description?: string
  priceAmount: number
  currency: string
  periodDays: number
  isStub: boolean
}

// ========== Onboarding DTOs ==========

export interface OnboardingResponse {
  success: boolean
  step?: string
  shopId?: string
  shopName?: string
  botUsername?: string
  buyDeepLink?: string
  adminDeepLink?: string
  botInstanceId?: number
  completed?: boolean
  error?: string
}

export interface CreateShopRequest {
  name?: string
  timezone?: string
  templateType?: BusinessType
  loyaltyMode?: LoyaltyMode
  stampsRequiredForReward?: number
  rewardTitle?: string
  bonusPercent?: number
}

export interface ConnectBotOnboardingRequest {
  shopId: string
  botToken: string
  platform?: MessengerPlatform
}

export interface ApplyTemplateRequest {
  shopId: string
  templateType?: string
}

export interface CompleteOnboardingRequest {
  shopId: string
}

// ========== Request DTOs ==========

export interface ConnectBotRequest {
  botToken: string
  businessType: BusinessType
  businessName?: string
  ownerEmail?: string
  platform?: MessengerPlatform
}

export interface UpdateSettingsRequest {
  shopName?: string
  fastCheckoutEnabled?: boolean
  fastCheckoutType?: FastCheckoutType
  fastCheckoutValue?: number
  fastCheckoutCooldownMinutes?: number
  fastCheckoutDailyLimitPerCustomer?: number
  stampsEnabled?: boolean
  stampsPerFastPurchase?: number
  stampsRequiredForReward?: number
  rewardTitle?: string
  rewardDescription?: string
  redeemRequiresCashierConfirm?: boolean
  redeemCodeTtlMinutes?: number
  discountTiersEnabled?: boolean
  discountTier1Amount?: number
  discountTier1Percent?: number
  discountTier2Amount?: number
  discountTier2Percent?: number
  discountTier3Amount?: number
  discountTier3Percent?: number
  discountValidityDays?: number
  // Постоянная скидка
  permanentDiscountEnabled?: boolean
  permanentDiscountTiers?: string
  // Балльная система
  bonusPointsEnabled?: boolean
  bonusCashbackPercent?: number
  bonusMaxSpendPercent?: number
  telegramChannelUrl?: string
  defaultLocationId?: string
  autoMessagesEnabled?: boolean
  autoMessagesDailyLimitPerCustomer?: number
  regularThresholdPurchases?: number
  vipThresholdPurchases?: number
  lostDaysSinceLastPurchase?: number
  // Кастомные сообщения
  welcomeMessage?: string
  purchaseCodeMessage?: string
  stampEarnedMessage?: string
  rewardEarnedMessage?: string
}

export interface UpdateWebhookRequest {
  baseUrl: string
}

// ========== Response DTOs ==========

export interface ConnectBotResponse {
  success: boolean
  shopId?: string
  botUsername?: string
  buyDeepLink?: string
  adminDeepLink?: string
  botInstanceId?: number
  error?: string
}

export interface BotInfoResponse {
  id: number
  shopId: string
  botUsername: string
  businessName?: string
  status: BotStatus
  isActive: boolean
  webhookUrl?: string
  updatesProcessed: number
  lastWebhookAt?: string
  lastError?: string
  pendingUpdates: number
  buyDeepLink: string
  adminDeepLink: string
}

export interface BotListItem {
  id: number
  shopId: string
  botUsername: string
  businessName?: string
  status: BotStatus
  updatesProcessed: number
  platform?: MessengerPlatform
}

export interface ShopSettings {
  id: number
  shopId: string
  shopName: string
  defaultLocationId?: string
  telegramChannelUrl?: string
  
  // Накопительная скидка
  discountTiersEnabled: boolean
  discountTier1Amount: number
  discountTier1Percent: number
  discountTier2Amount: number
  discountTier2Percent: number
  discountTier3Amount: number
  discountTier3Percent: number
  discountValidityDays: number
  
  // Постоянная скидка
  permanentDiscountEnabled: boolean
  permanentDiscountTiers?: string
  
  // Fast Checkout
  fastCheckoutEnabled: boolean
  fastCheckoutType: FastCheckoutType
  fastCheckoutValue: number
  fastCheckoutCooldownMinutes: number
  fastCheckoutDailyLimitPerCustomer: number
  
  // Штампы
  stampsEnabled: boolean
  stampsPerFastPurchase: number
  stampsRequiredForReward: number
  rewardTitle: string
  rewardDescription?: string
  redeemRequiresCashierConfirm: boolean
  redeemCodeTtlMinutes: number
  
  // Статусы клиентов
  regularThresholdPurchases: number
  vipThresholdPurchases: number
  vipThresholdTotalSpend?: number
  lostDaysSinceLastPurchase: number
  
  // Балльная система
  bonusPointsEnabled: boolean
  bonusCashbackPercent: number
  bonusMaxSpendPercent: number
  
  // Авто-сообщения
  autoMessagesEnabled: boolean
  autoMessagesDailyLimitPerCustomer: number
  
  // Кастомные сообщения
  welcomeMessage?: string
  purchaseCodeMessage?: string
  stampEarnedMessage?: string
  rewardEarnedMessage?: string
  
  createdAt?: string
  updatedAt?: string
}

export interface DeepLinkResponse {
  buyDeepLink: string
  adminDeepLink: string
  shopId: string
  locationId?: string
}

export interface PlatformStats {
  activeBots: number
  totalUsers: number
  totalTransactions: number
  totalRevenue: number
}

// ========== Report Types (extended for UI) ==========

export interface WeeklyReport {
  period: {
    from: string
    to: string
  }
  customers: {
    new: number
    registered: number
    returning: number
    lost: number
  }
  finances: {
    transactions: number
    totalRevenue: number
    avgCheck: number
    fastCheckoutCount: number
    fastCheckoutPercent: number
  }
  statuses: {
    new: number
    regular: number
    vip: number
    lost: number
  }
  topCustomers: Array<{
    name: string
    totalSpend: number
    purchasesCount: number
  }>
  achievements: number
  rewardsRedeemed: number
}

// ========== Permanent Discount ==========

export interface PermanentDiscountTier {
  amount: number
  percent: number
}

// ========== API Error ==========

export interface ApiError {
  message: string
  status?: number
  code?: string
}

