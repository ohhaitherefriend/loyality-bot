import { z } from 'zod'

// ========== Bot Connection Schema ==========

export const connectBotSchema = z.object({
  botToken: z
    .string()
    .min(1, 'Токен бота обязателен'),
  businessType: z.enum(['COFFEE', 'RETAIL', 'SERVICE', 'HYBRID'], {
    required_error: 'Выберите тип бизнеса',
  }),
  businessName: z
    .string()
    .min(2, 'Минимум 2 символа')
    .max(100, 'Максимум 100 символов')
    .optional()
    .or(z.literal('')),
  ownerEmail: z
    .string()
    .email('Неверный email')
    .optional()
    .or(z.literal('')),
  platform: z.enum(['TELEGRAM', 'MAX']).default('TELEGRAM'),
})

export type ConnectBotFormData = z.infer<typeof connectBotSchema>

// ========== Shop Settings Schemas ==========

export const fastCheckoutSettingsSchema = z.object({
  fastCheckoutEnabled: z.boolean(),
  fastCheckoutType: z.enum(['STAMP', 'FIXED_POINTS']),
  fastCheckoutValue: z
    .number()
    .min(1, 'Минимум 1')
    .max(100, 'Максимум 100'),
  fastCheckoutCooldownMinutes: z
    .number()
    .min(0, 'Не может быть отрицательным')
    .max(1440, 'Максимум 24 часа'),
  fastCheckoutDailyLimitPerCustomer: z
    .number()
    .min(1, 'Минимум 1')
    .max(100, 'Максимум 100'),
})

export const stampsSettingsSchema = z.object({
  stampsEnabled: z.boolean(),
  stampsPerFastPurchase: z
    .number()
    .min(1, 'Минимум 1')
    .max(10, 'Максимум 10'),
  stampsRequiredForReward: z
    .number()
    .min(2, 'Минимум 2 штампа')
    .max(50, 'Максимум 50'),
  rewardTitle: z
    .string()
    .min(2, 'Минимум 2 символа')
    .max(100, 'Максимум 100 символов'),
  rewardDescription: z
    .string()
    .max(500, 'Максимум 500 символов')
    .optional()
    .or(z.literal('')),
  redeemRequiresCashierConfirm: z.boolean(),
  redeemCodeTtlMinutes: z
    .number()
    .min(1, 'Минимум 1 минута')
    .max(60, 'Максимум 60 минут'),
})

export const discountTiersSettingsSchema = z.object({
  discountTiersEnabled: z.boolean(),
  discountTier1Amount: z
    .number()
    .min(100, 'Минимум 100')
    .max(1000000, 'Максимум 1 000 000'),
  discountTier1Percent: z
    .number()
    .min(1, 'Минимум 1%')
    .max(50, 'Максимум 50%'),
  discountTier2Amount: z
    .number()
    .min(100, 'Минимум 100')
    .max(1000000, 'Максимум 1 000 000'),
  discountTier2Percent: z
    .number()
    .min(1, 'Минимум 1%')
    .max(50, 'Максимум 50%'),
  discountTier3Amount: z
    .number()
    .min(100, 'Минимум 100')
    .max(1000000, 'Максимум 1 000 000'),
  discountTier3Percent: z
    .number()
    .min(1, 'Минимум 1%')
    .max(50, 'Максимум 50%'),
  discountValidityDays: z
    .number()
    .min(0, 'Минимум 0 (бессрочная)')
    .max(365, 'Максимум 365 дней'),
})

export const permanentDiscountTierSchema = z.object({
  amount: z.number().min(100, 'Минимум 100').max(10000000, 'Максимум 10 000 000'),
  percent: z.number().min(1, 'Минимум 1%').max(50, 'Максимум 50%'),
})

export const permanentDiscountSettingsSchema = z.object({
  permanentDiscountEnabled: z.boolean(),
  permanentDiscountTiersList: z.array(permanentDiscountTierSchema).default([]),
})

export const generalSettingsSchema = z.object({
  shopName: z
    .string()
    .min(2, 'Минимум 2 символа')
    .max(100, 'Максимум 100 символов'),
  telegramChannelUrl: z
    .string()
    .url('Неверный URL')
    .optional()
    .or(z.literal('')),
  defaultLocationId: z
    .string()
    .max(50, 'Максимум 50 символов')
    .optional()
    .or(z.literal('')),
  autoMessagesEnabled: z.boolean(),
  autoMessagesDailyLimitPerCustomer: z
    .number()
    .min(0, 'Не может быть отрицательным')
    .max(20, 'Максимум 20'),
})

export const customMessagesSchema = z.object({
  welcomeMessage: z
    .string()
    .max(1000, 'Максимум 1000 символов')
    .optional()
    .or(z.literal('')),
  purchaseCodeMessage: z
    .string()
    .max(1000, 'Максимум 1000 символов')
    .optional()
    .or(z.literal('')),
  stampEarnedMessage: z
    .string()
    .max(1000, 'Максимум 1000 символов')
    .optional()
    .or(z.literal('')),
  rewardEarnedMessage: z
    .string()
    .max(1000, 'Максимум 1000 символов')
    .optional()
    .or(z.literal('')),
})

// Combined settings schema
export const shopSettingsSchema = z
  .object({})
  .merge(generalSettingsSchema)
  .merge(fastCheckoutSettingsSchema)
  .merge(stampsSettingsSchema)
  .merge(discountTiersSettingsSchema)
  .merge(permanentDiscountSettingsSchema)
  .merge(customMessagesSchema)

export type ShopSettingsFormData = z.infer<typeof shopSettingsSchema>
export type FastCheckoutFormData = z.infer<typeof fastCheckoutSettingsSchema>
export type StampsFormData = z.infer<typeof stampsSettingsSchema>
export type DiscountTiersFormData = z.infer<typeof discountTiersSettingsSchema>
export type PermanentDiscountFormData = z.infer<typeof permanentDiscountSettingsSchema>
export type GeneralFormData = z.infer<typeof generalSettingsSchema>

