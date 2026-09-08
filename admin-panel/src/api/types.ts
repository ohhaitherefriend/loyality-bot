// ========== Enums ==========

export type MessengerPlatform = 'TELEGRAM' | 'MAX'

export type BusinessType = 'COFFEE' | 'RETAIL' | 'SERVICE' | 'HYBRID'

export type LoyaltyMode = 'STAMPS' | 'BONUS' | 'CUMULATIVE_DISCOUNT'

export type BotStatus = 'PENDING' | 'CONNECTING' | 'ACTIVE' | 'ERROR' | 'DISABLED'

export type FastCheckoutType = 'STAMP' | 'FIXED_POINTS'

export type SubscriptionStatus = 'TRIALING' | 'ACTIVE' | 'EXPIRED' | 'CANCELLED' | 'PAST_DUE'

export type OnboardingStep = 'START' | 'SHOP_CREATED' | 'BOT_CONNECTED' | 'SETTINGS_DONE' | 'COMPLETED'

export type OrderStatus =
  | 'DRAFT'
  | 'CREATED'
  | 'CONFIRMED'
  | 'PACKING'
  | 'READY_FOR_PICKUP'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'CANCELLED'

export type DeliveryType = 'PICKUP' | 'COURIER' | 'CDEK' | 'OTHER'

export type AvailabilityMode = 'IN_STOCK' | 'PREORDER' | 'OUT_OF_STOCK'

export type MailAuthMode = 'OAUTH2' | 'APP_PASSWORD'

export type ImageStatus =
  | 'MISSING'
  | 'CANDIDATE_FOUND'
  | 'DOWNLOADED'
  | 'NORMALIZED'
  | 'NEEDS_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'FAILED'

export type ImageType = 'MAIN' | 'CANDIDATE' | 'PLACEHOLDER'

export type ImageSourceType =
  | 'SUPPLIER'
  | 'BRAND_OFFICIAL'
  | 'MARKETPLACE_CANDIDATE'
  | 'MANUAL_UPLOAD'
  | 'MANUAL_URL'
  | 'AI_PLACEHOLDER'

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

// ========== Commerce Orders ==========

export type OrderSource = 'BOT' | 'MINI_APP' | 'ADMIN'

export interface OrderSummary {
  id: number
  shopId: string
  userId: number
  status: OrderStatus
  source?: OrderSource
  itemsTotal: number
  bonusSpent: number
  totalToPay: number
  bonusAccrued: number
  customerPhone?: string
  customerName?: string
  deliveryType: DeliveryType
  createdAt: string
  completedAt?: string
  cancelledAt?: string
}

export interface OrderItem {
  id: number
  productId: number
  skuSnapshot?: string
  barcodeSnapshot?: string
  brandSnapshot?: string
  nameSnapshot: string
  priceSnapshot: number
  availabilityModeSnapshot: AvailabilityMode
  quantity: number
  lineTotal: number
}

export interface OrderDetails extends OrderSummary {
  deliveryAddress?: string
  customerComment?: string
  updatedAt?: string
  items: OrderItem[]
}

export interface OrderPageResponse {
  content: OrderSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface OrderStatusUpdateRequest {
  status: OrderStatus
}

// ========== Commerce Catalog ==========

export interface Product {
  id: number
  shopId: string
  supplierGuid?: string
  sourceSheet?: string
  sourceRow?: number
  brand: string
  supplierArticle: string
  barcode?: string
  name: string
  description?: string
  categoryPath?: string
  supplierPrice?: number
  salePrice?: number
  oldPrice?: number
  currency?: string
  stockQuantity?: number | null
  availabilityMode: AvailabilityMode
  visible: boolean
  active: boolean
  mainImageUrl?: string
  previewImageUrl?: string
  imageStatus: ImageStatus
  priceListDate?: string
  lastImportedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface ProductPageResponse {
  content: Product[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ProductUpdateRequest {
  salePrice?: number | null
  oldPrice?: number | null
  visible?: boolean
  active?: boolean
  stockQuantity?: number | null
  availabilityMode?: AvailabilityMode
  description?: string | null
}

export interface ProductImportResponse {
  batchId: number
  filename: string
  priceListDate?: string
  totalRows: number
  importedCount: number
  updatedCount: number
  skippedCount: number
  status: string
  errorMessage?: string
}

export interface ProductImage {
  id: number
  productId: number
  shopId: string
  imageType: ImageType
  status: ImageStatus
  sourceType: ImageSourceType
  sourceUrl?: string
  sourcePageUrl?: string
  sourceDomain?: string
  originalUrl?: string
  normalizedUrl?: string
  confidence?: number
  matchedBy?: string
  approvedByAdmin?: boolean
  aiNormalized?: boolean
  rejectReason?: string
  visualQualityScore?: number
  qualityDecision?: string
  qualityWarnings?: string
  normalizationProvider?: string
  backgroundRemoved?: boolean
  scaleNormalized?: boolean
  angleNormalized?: boolean
  manualReviewReason?: string
  rankerReason?: string
  rankerWarnings?: string
  createdAt?: string
  updatedAt?: string
}

export interface ImageSearchStatus {
  imageSearchEnabled: boolean
  imageSearchProvider: string
  imageSearchConfigured: boolean
  ranker: string
  rankerConfigured: boolean
  backgroundRemovalProvider: string
  backgroundRemovalConfigured: boolean
  backgroundRemovalHealthy: boolean
  backgroundRemovalHealthUrl: string
  normalizationOutputSize: number
}

export interface BulkImageSearchRequest {
  productIds: number[]
  maxCandidatesPerProduct?: number
  downloadAndNormalize?: boolean
}

export interface BulkImageSearchResponse {
  processedProducts: number
  candidatesFound: number
  rankedMatches: number
  candidatesRejectedByQuality: number
  imagesDownloaded: number
  backgroundRemovalSucceeded: number
  backgroundRemovalFailed: number
  fallbackNormalized: number
  imagesNormalized: number
  needsReview: number
  failedCount: number
  errors: string[]
}

export interface ProductListParams {
  page?: number
  size?: number
  query?: string
  brand?: string
  visible?: boolean
  active?: boolean
  missingImages?: boolean
}

export interface CatalogImportParams {
  file: File
  defaultMarkupPercent?: number
  makeImportedVisible?: boolean
  overwriteManualFields?: boolean
}

// ========== Supplier email ingestion (Prompt 02) ==========

export interface MailboxConnection {
  id: number
  label: string
  host: string
  port: number
  username: string
  authMode?: MailAuthMode
  folder: string
  useTls: boolean
  enabled: boolean
  lastPollAt?: string
  lastPollSuccessAt?: string
  lastPollError?: string
}

export interface CreateMailboxRequest {
  label: string
  host: string
  port: number
  username: string
  secret: string
  authMode?: MailAuthMode
  folder?: string
  useTls?: boolean
  enabled?: boolean
}

export interface TestConnectionResponse {
  success: boolean
  message: string
}

export interface PollResponse {
  status: string
  ingestedCount: number
  skippedCount: number
  message?: string
}

export interface ManualImportUploadResponse {
  importFileId: number
  batchId: number
  status: ImportBatchStatus
  alreadyExisted: boolean
}

export interface Supplier {
  id: number
  name: string
  code?: string
  active: boolean
}

export interface CreateSupplierRequest {
  name: string
  code?: string
}

export type SnapshotMode = 'FULL' | 'DELTA'

export type PublicPriceStrategy = 'LOWEST_ACTIVE_OFFER'

export type PriceRoundingPolicy = 'WHOLE_UNIT_HALF_UP' | 'NO_ROUNDING'

export interface SupplierSource {
  id: number
  version: number
  label: string
  supplierId: number
  supplierName: string
  mailboxConnectionId?: number
  senderAllowlist?: string
  subjectPattern?: string
  filenamePattern?: string
  enabled: boolean
  snapshotMode: SnapshotMode
  snapshotScope: string
  commissionPercentOverride?: number
  publicPriceStrategy: PublicPriceStrategy
  roundingPolicy: PriceRoundingPolicy
  shadowMode: boolean
  autoApply: boolean
  aiAutoApproveMinScoreOverride?: number
  aiMinConfidenceOverride?: number
}

export interface CreateSupplierSourceRequest {
  supplierId: number
  label: string
  mailboxConnectionId?: number
  senderAllowlist?: string
  subjectPattern?: string
  filenamePattern?: string
}

/** Stage 1: PATCH merge-patch body. `undefined` = leave unchanged; `clear*` flags null out an override. */
export interface UpdateSupplierSourceRequest {
  expectedVersion?: number
  label?: string
  mailboxConnectionId?: number
  clearMailboxConnectionId?: boolean
  senderAllowlist?: string
  subjectPattern?: string
  filenamePattern?: string
  enabled?: boolean
  snapshotMode?: SnapshotMode
  snapshotScope?: string
  commissionPercentOverride?: number
  clearCommissionPercentOverride?: boolean
  roundingPolicy?: PriceRoundingPolicy
  publicPriceStrategy?: PublicPriceStrategy
  shadowMode?: boolean
  autoApply?: boolean
  confirmAutoApply?: boolean
  aiAutoApproveMinScoreOverride?: number
  clearAiAutoApproveMinScoreOverride?: boolean
  aiMinConfidenceOverride?: number
  clearAiMinConfidenceOverride?: boolean
}

// ========== Brand aliases (Stage 4 of the production-hardening pass) ==========

export interface BrandAlias {
  id: number
  canonicalBrand: string
  alias: string
  normalizedAlias: string
  createdBy?: string
  createdAt?: string
}

export interface CreateBrandAliasRequest {
  canonicalBrand: string
  alias: string
}

// ========== Operations UI (Prompt 07 automation control panel) ==========

export type ImportRowStatus =
  | 'PENDING'
  | 'EXACT_MATCH'
  | 'LEARNED_MATCH'
  | 'AI_MATCH'
  | 'AUTO_APPROVED'
  | 'NEEDS_REVIEW'
  | 'NEW_PRODUCT'
  | 'IGNORED'
  | 'INVALID'
  | 'APPROVED'
  | 'APPLIED'

export type ImportBatchStatus =
  | 'RECEIVED'
  | 'STORED'
  | 'PARSING'
  | 'NORMALIZING'
  | 'MATCHING'
  | 'VALIDATING'
  | 'AUTO_APPROVED'
  | 'NEEDS_ATTENTION'
  | 'APPROVED'
  | 'APPLYING'
  | 'APPLIED'
  | 'QUARANTINED'
  | 'FAILED'

export type RuleVersionStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED'

export type DecidedBy = 'SYSTEM' | 'HUMAN'

export type MatchDecisionType =
  | 'EXACT'
  | 'LEARNED'
  | 'AI_MATCH'
  | 'AI_NO_MATCH'
  | 'NEW_PRODUCT'
  | 'MANUAL'
  | 'NO_MATCH'

export type RowReviewAction = 'MATCH' | 'NO_MATCH' | 'CREATE_PRODUCT' | 'IGNORE'

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ImportDashboardResponse {
  automationRate: {
    autoDecidedRows: number
    humanDecidedRows: number
    ratePercent: number | null
  }
  batches: {
    total: number
    running: number
    needsAttention: number
    failed: number
    quarantined: number
    applied: number
  }
  exceptionQueueSize: number
  mailboxes: Array<{
    mailboxId: number
    label: string
    enabled: boolean
    lastPollAt?: string
    lastPollSuccessAt?: string
    lastPollError?: string
    healthy: boolean
  }>
  supplierExceptionRates: Array<{
    supplierId: number
    supplierName: string
    totalRows: number
    exceptionRows: number
    exceptionRatePercent: number
  }>
  recentActivity: {
    windowHours: number
    filesProcessed: number
    rowsProcessed: number
  }
  recentProductChanges: {
    windowHours: number
    added: number
    updated: number
    priceChanged: number
    removedFromStorefront: number
    reactivated: number
  }
}

export interface RowExceptionSummary {
  rowId: number
  version: number
  batchId: number
  supplierSourceId: number
  supplierSourceLabel: string
  supplierId: number
  supplierName: string
  sourceSheet?: string
  sourceRowNumber?: number
  status: ImportRowStatus
  rawNamePreview?: string
  brandPreview?: string
  supplierPricePreview?: number
  createdAt: string
}

export interface BatchExceptionSummary {
  batchId: number
  status: ImportBatchStatus
  supplierSourceId: number
  supplierSourceLabel: string
  supplierId: number
  supplierName: string
  originalFilename?: string
  totalRows?: number
  validRows?: number
  invalidRows?: number
  attemptNumber?: number
  errorMessage?: string
  createdAt: string
  finishedAt?: string
}

export interface BatchDetailResponse {
  batchId: number
  status: ImportBatchStatus
  supplierSourceId: number
  supplierSourceLabel: string
  supplierId: number
  supplierName: string
  originalFilename?: string
  fileSizeBytes?: number
  fileReceivedAt?: string
  ruleVersionId?: number
  ruleVersionNumber?: number
  ruleVersionStatus?: RuleVersionStatus
  totalRows?: number
  validRows?: number
  invalidRows?: number
  attemptNumber?: number
  errorMessage?: string
  rowStatusCounts: Record<string, number>
  offersAddedCount?: number
  offersUpdatedCount?: number
  offersPriceChangedCount?: number
  offersUnchangedCount?: number
  productsRemovedFromStorefrontCount?: number
  productsReactivatedCount?: number
  startedAt?: string
  finishedAt?: string
  appliedAt?: string
  createdAt: string
  updatedAt?: string
}

export interface RowListItem {
  rowId: number
  version: number
  sourceSheet?: string
  sourceRowNumber?: number
  status: ImportRowStatus
  rawNamePreview?: string
  brandPreview?: string
  supplierPricePreview?: number
  matchedProductId?: number
  matchedProductName?: string
  createdAt: string
}

export interface ScoredCandidate {
  productId: number
  productName: string
  totalScore: number
  componentScores: Record<string, number>
  matchedAttributes: string[]
  conflicts: string[]
  candidateAttributes?: Record<string, unknown>
}

export interface MatchDecisionAudit {
  id: number
  decisionType: MatchDecisionType
  decidedBy: DecidedBy
  chosenProductId?: number
  chosenProductName?: string
  confidenceScore?: number
  modelProvider?: string
  modelName?: string
  promptVersion?: string
  conflicts: string[]
  reason?: string
  reviewerUserId?: number
  reviewerEmail?: string
  decidedAt: string
}

export interface RowDetailResponse {
  rowId: number
  version: number
  batchId: number
  sourceSheet?: string
  sourceRowNumber?: number
  status: ImportRowStatus
  rawData: Record<string, string>
  normalizedData?: Record<string, unknown>
  candidates: ScoredCandidate[]
  matchedProductId?: number
  matchedProductName?: string
  decisions: MatchDecisionAudit[]
  createdAt: string
  updatedAt?: string
}

export interface RowReviewRequest {
  action: RowReviewAction
  expectedVersion?: number
  productId?: number
  note?: string
}

export interface RowReviewResult {
  rowId: number
  version: number
  status: ImportRowStatus
  matchedProductId?: number
}

export interface BulkRowReviewRequest {
  rowIds: number[]
  action: RowReviewAction
  note?: string
}

export interface BulkReviewResult {
  succeededRowIds: number[]
  failures: Record<number, string>
}

export interface VersionConflictError {
  message: string
  currentVersion?: number
}

// ========== API Error ==========

export interface ApiError {
  message: string
  status?: number
  code?: string
}

