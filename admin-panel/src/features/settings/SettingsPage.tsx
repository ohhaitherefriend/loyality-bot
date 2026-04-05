import { useEffect } from 'react'
import { useForm, Controller, useFieldArray } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import {
  Save,
  Loader2,
  Zap,
  Stamp,
  Percent,
  Settings,
  AlertCircle,
  Info,
  MessageSquare,
  Plus,
  Trash2,
  Diamond,
} from 'lucide-react'

import { api } from '@/api/client'
import type { UpdateSettingsRequest, PermanentDiscountTier } from '@/api/types'
import { shopSettingsSchema, type ShopSettingsFormData } from '@/lib/validators'
import { useShopStore } from '@/lib/store'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'

function parseDiscountTiers(settings: import('@/api/types').ShopSettings): PermanentDiscountTier[] {
  if (settings.permanentDiscountTiers) {
    try {
      const parsed = JSON.parse(settings.permanentDiscountTiers)
      if (Array.isArray(parsed) && parsed.length > 0) return parsed
    } catch { /* fall through */ }
  }
  return [
    { amount: settings.discountTier1Amount, percent: settings.discountTier1Percent },
    { amount: settings.discountTier2Amount, percent: settings.discountTier2Percent },
    { amount: settings.discountTier3Amount, percent: settings.discountTier3Percent },
  ]
}

function SettingsSkeleton() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-10 w-full" />
      <div className="space-y-4">
        {[1, 2, 3].map((i) => (
          <Card key={i}>
            <CardHeader>
              <Skeleton className="h-6 w-48" />
              <Skeleton className="h-4 w-72" />
            </CardHeader>
            <CardContent className="space-y-4">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}

export function SettingsPage() {
  const { shopId } = useShopStore()
  const queryClient = useQueryClient()

  const { data: settings, isLoading, isError, error } = useQuery({
    queryKey: ['shopSettings', shopId],
    queryFn: () => api.getShopSettings(shopId!),
    enabled: !!shopId,
  })

  const form = useForm<ShopSettingsFormData>({
    resolver: zodResolver(shopSettingsSchema),
    defaultValues: {
      shopName: '',
      fastCheckoutEnabled: false,
      fastCheckoutType: 'STAMP',
      fastCheckoutValue: 1,
      fastCheckoutCooldownMinutes: 5,
      fastCheckoutDailyLimitPerCustomer: 10,
      stampsEnabled: false,
      stampsPerFastPurchase: 1,
      stampsRequiredForReward: 10,
      rewardTitle: 'Бесплатный напиток',
      rewardDescription: '',
      redeemRequiresCashierConfirm: true,
      redeemCodeTtlMinutes: 10,
      discountTiersEnabled: false,
      discountTier1Amount: 20000,
      discountTier1Percent: 5,
      discountTier2Amount: 25000,
      discountTier2Percent: 7,
      discountTier3Amount: 30000,
      discountTier3Percent: 10,
      discountValidityDays: 30,
      permanentDiscountEnabled: false,
      permanentDiscountTiersList: [],
      bonusPointsEnabled: false,
      bonusCashbackPercent: 5,
      bonusMaxSpendPercent: 100,
      telegramChannelUrl: '',
      defaultLocationId: '',
      autoMessagesEnabled: true,
      autoMessagesDailyLimitPerCustomer: 3,
      welcomeMessage: '',
      purchaseCodeMessage: '',
      stampEarnedMessage: '',
      rewardEarnedMessage: '',
    },
  })

  // Update form when settings load
  useEffect(() => {
    if (settings) {
      form.reset({
        shopName: settings.shopName || '',
        fastCheckoutEnabled: settings.fastCheckoutEnabled,
        fastCheckoutType: settings.fastCheckoutType,
        fastCheckoutValue: settings.fastCheckoutValue,
        fastCheckoutCooldownMinutes: settings.fastCheckoutCooldownMinutes,
        fastCheckoutDailyLimitPerCustomer: settings.fastCheckoutDailyLimitPerCustomer,
        stampsEnabled: settings.stampsEnabled,
        stampsPerFastPurchase: settings.stampsPerFastPurchase,
        stampsRequiredForReward: settings.stampsRequiredForReward,
        rewardTitle: settings.rewardTitle || '',
        rewardDescription: settings.rewardDescription || '',
        redeemRequiresCashierConfirm: settings.redeemRequiresCashierConfirm,
        redeemCodeTtlMinutes: settings.redeemCodeTtlMinutes,
        discountTiersEnabled: settings.discountTiersEnabled,
        discountTier1Amount: settings.discountTier1Amount,
        discountTier1Percent: settings.discountTier1Percent,
        discountTier2Amount: settings.discountTier2Amount,
        discountTier2Percent: settings.discountTier2Percent,
        discountTier3Amount: settings.discountTier3Amount,
        discountTier3Percent: settings.discountTier3Percent,
        discountValidityDays: settings.discountValidityDays,
        permanentDiscountEnabled: false,
        permanentDiscountTiersList: parseDiscountTiers(settings),
        bonusPointsEnabled: settings.bonusPointsEnabled ?? false,
        bonusCashbackPercent: settings.bonusCashbackPercent ?? 5,
        bonusMaxSpendPercent: settings.bonusMaxSpendPercent ?? 100,
        telegramChannelUrl: settings.telegramChannelUrl || '',
        defaultLocationId: settings.defaultLocationId || '',
        autoMessagesEnabled: settings.autoMessagesEnabled,
        autoMessagesDailyLimitPerCustomer: settings.autoMessagesDailyLimitPerCustomer,
        welcomeMessage: settings.welcomeMessage || '',
        purchaseCodeMessage: settings.purchaseCodeMessage || '',
        stampEarnedMessage: settings.stampEarnedMessage || '',
        rewardEarnedMessage: settings.rewardEarnedMessage || '',
      })
    }
  }, [settings, form])

  const updateMutation = useMutation({
    mutationFn: (data: UpdateSettingsRequest) =>
      api.updateShopSettings(shopId!, data),
    onSuccess: (updatedSettings) => {
      queryClient.setQueryData(['shopSettings', shopId], updatedSettings)
      toast({
        title: 'Сохранено!',
        description: 'Настройки успешно обновлены',
        variant: 'default',
      })
    },
    onError: () => {
      toast({
        title: 'Ошибка',
        description: 'Не удалось сохранить настройки',
        variant: 'destructive',
      })
    },
  })

  const onSubmit = (data: ShopSettingsFormData) => {
    const { permanentDiscountTiersList, ...rest } = data
    const payload: UpdateSettingsRequest = {
      ...rest,
      permanentDiscountTiers: permanentDiscountTiersList.length > 0
        ? JSON.stringify(permanentDiscountTiersList)
        : undefined,
    }
    updateMutation.mutate(payload)
  }

  const watchFastCheckout = form.watch('fastCheckoutEnabled')
  const watchStamps = form.watch('stampsEnabled')
  const watchDiscounts = form.watch('discountTiersEnabled')
  const watchBonus = form.watch('bonusPointsEnabled')
  const isDirty = form.formState.isDirty

  const { fields: permanentTierFields, append: appendTier, remove: removeTier } = useFieldArray({
    control: form.control,
    name: 'permanentDiscountTiersList',
  })

  // Warn on page leave if dirty
  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (isDirty) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [isDirty])

  if (isLoading) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Настройки магазина</h1>
          <p className="text-muted-foreground">Загрузка...</p>
        </div>
        <SettingsSkeleton />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="mb-8 space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Настройки магазина</h1>
        </div>
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>
            Не удалось загрузить настройки: {error?.message || 'Неизвестная ошибка'}
          </AlertDescription>
        </Alert>
      </div>
    )
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8 space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">Настройки магазина</h1>
        <p className="text-muted-foreground">
          Настройте параметры программы лояльности
        </p>
      </div>

      <form onSubmit={form.handleSubmit(onSubmit)}>
        <Tabs defaultValue="general" className="space-y-6">
          <TabsList className="grid w-full grid-cols-5">
            <TabsTrigger value="general" className="gap-2">
              <Settings className="h-4 w-4" />
              <span className="hidden sm:inline">Основные</span>
            </TabsTrigger>
            <TabsTrigger value="fastcheckout" className="gap-2">
              <Zap className="h-4 w-4" />
              <span className="hidden sm:inline">Касса</span>
            </TabsTrigger>
            <TabsTrigger value="stamps" className="gap-2">
              <Stamp className="h-4 w-4" />
              <span className="hidden sm:inline">Штампы</span>
            </TabsTrigger>
            <TabsTrigger value="discounts" className="gap-2">
              <Percent className="h-4 w-4" />
              <span className="hidden sm:inline">Скидки</span>
            </TabsTrigger>
            <TabsTrigger value="messages" className="gap-2">
              <MessageSquare className="h-4 w-4" />
              <span className="hidden sm:inline">Сообщения</span>
            </TabsTrigger>
          </TabsList>

          {/* General Settings */}
          <TabsContent value="general">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle>Основные настройки</CardTitle>
                  <CardDescription>
                    Базовые параметры вашего магазина
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-2">
                      <Label htmlFor="shopName">Название магазина</Label>
                      <Input
                        id="shopName"
                        {...form.register('shopName')}
                      />
                      {form.formState.errors.shopName && (
                        <p className="text-sm text-destructive">
                          {form.formState.errors.shopName.message}
                        </p>
                      )}
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="defaultLocationId">ID локации (опционально)</Label>
                      <Input
                        id="defaultLocationId"
                        placeholder="main"
                        {...form.register('defaultLocationId')}
                      />
                    </div>
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="telegramChannelUrl">Telegram канал</Label>
                    <Input
                      id="telegramChannelUrl"
                      type="url"
                      placeholder="https://t.me/your_channel"
                      {...form.register('telegramChannelUrl')}
                    />
                    <p className="text-xs text-muted-foreground">
                      Для публикации новостей и акций
                    </p>
                  </div>

                  <Separator />

                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <div className="space-y-0.5">
                        <Label>Авто-сообщения</Label>
                        <p className="text-sm text-muted-foreground">
                          Автоматические уведомления клиентам
                        </p>
                      </div>
                      <Controller
                        name="autoMessagesEnabled"
                        control={form.control}
                        render={({ field }) => (
                          <Switch
                            checked={field.value}
                            onCheckedChange={field.onChange}
                          />
                        )}
                      />
                    </div>

                    {form.watch('autoMessagesEnabled') && (
                      <div className="space-y-2 pl-4 border-l-2 border-primary/20">
                        <Label htmlFor="autoMessagesDailyLimit">
                          Лимит сообщений в день на клиента
                        </Label>
                        <Input
                          id="autoMessagesDailyLimit"
                          type="number"
                          className="w-32"
                          {...form.register('autoMessagesDailyLimitPerCustomer', { valueAsNumber: true })}
                        />
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>
            </motion.div>
          </TabsContent>

          {/* Fast Checkout Settings */}
          <TabsContent value="fastcheckout">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Zap className="h-5 w-5 text-warning" />
                    Быстрая покупка (Fast Checkout)
                  </CardTitle>
                  <CardDescription>
                    Клиенты сканируют QR-код на кассе и сразу получают награды
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <div className="flex items-center justify-between">
                    <div className="space-y-0.5">
                      <Label>Включить Fast Checkout</Label>
                      <p className="text-sm text-muted-foreground">
                        Быстрое начисление баллов/штампов по QR
                      </p>
                    </div>
                    <Controller
                      name="fastCheckoutEnabled"
                      control={form.control}
                      render={({ field }) => (
                        <Switch
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                      )}
                    />
                  </div>

                  {watchFastCheckout && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="space-y-4 pl-4 border-l-2 border-warning/30"
                    >
                      <div className="space-y-2">
                        <Label>Тип награды</Label>
                        <Controller
                          name="fastCheckoutType"
                          control={form.control}
                          render={({ field }) => (
                            <Select
                              value={field.value}
                              onValueChange={field.onChange}
                            >
                              <SelectTrigger className="w-full sm:w-64">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="STAMP">Штамп</SelectItem>
                                <SelectItem value="FIXED_POINTS">Фиксированные баллы</SelectItem>
                              </SelectContent>
                            </Select>
                          )}
                        />
                      </div>

                      <div className="grid gap-4 sm:grid-cols-3">
                        <div className="space-y-2">
                          <Label htmlFor="fastCheckoutValue">
                            Значение награды
                          </Label>
                          <Input
                            id="fastCheckoutValue"
                            type="number"
                            {...form.register('fastCheckoutValue', { valueAsNumber: true })}
                          />
                          <p className="text-xs text-muted-foreground">
                            {form.watch('fastCheckoutType') === 'STAMP'
                              ? 'Штампов за покупку'
                              : 'Баллов за покупку'}
                          </p>
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor="cooldown">
                            Cooldown (минуты)
                          </Label>
                          <Input
                            id="cooldown"
                            type="number"
                            {...form.register('fastCheckoutCooldownMinutes', { valueAsNumber: true })}
                          />
                          <p className="text-xs text-muted-foreground">
                            Между покупками
                          </p>
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor="dailyLimit">
                            Лимит в день
                          </Label>
                          <Input
                            id="dailyLimit"
                            type="number"
                            {...form.register('fastCheckoutDailyLimitPerCustomer', { valueAsNumber: true })}
                          />
                          <p className="text-xs text-muted-foreground">
                            На клиента
                          </p>
                        </div>
                      </div>
                    </motion.div>
                  )}
                </CardContent>
              </Card>
            </motion.div>
          </TabsContent>

          {/* Stamps Settings */}
          <TabsContent value="stamps">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Stamp className="h-5 w-5 text-primary" />
                    Карточка штампов
                  </CardTitle>
                  <CardDescription>
                    Классическая система: собери N штампов — получи награду
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <div className="flex items-center justify-between">
                    <div className="space-y-0.5">
                      <Label>Включить штампы</Label>
                      <p className="text-sm text-muted-foreground">
                        Карточка штампов для клиентов
                      </p>
                    </div>
                    <Controller
                      name="stampsEnabled"
                      control={form.control}
                      render={({ field }) => (
                        <Switch
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                      )}
                    />
                  </div>

                  {watchStamps && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="space-y-6 pl-4 border-l-2 border-primary/30"
                    >
                      <div className="grid gap-4 sm:grid-cols-2">
                        <div className="space-y-2">
                          <Label htmlFor="stampsRequired">
                            Штампов для награды
                          </Label>
                          <Input
                            id="stampsRequired"
                            type="number"
                            {...form.register('stampsRequiredForReward', { valueAsNumber: true })}
                          />
                          {form.formState.errors.stampsRequiredForReward && (
                            <p className="text-sm text-destructive">
                              {form.formState.errors.stampsRequiredForReward.message}
                            </p>
                          )}
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor="stampsPerPurchase">
                            Штампов за покупку
                          </Label>
                          <Input
                            id="stampsPerPurchase"
                            type="number"
                            {...form.register('stampsPerFastPurchase', { valueAsNumber: true })}
                          />
                        </div>
                      </div>

                      <Separator />

                      <div className="space-y-4">
                        <h4 className="font-medium">Награда</h4>
                        <div className="grid gap-4 sm:grid-cols-2">
                          <div className="space-y-2">
                            <Label htmlFor="rewardTitle">Название награды</Label>
                            <Input
                              id="rewardTitle"
                              placeholder="Бесплатный напиток"
                              {...form.register('rewardTitle')}
                            />
                          </div>
                          <div className="space-y-2">
                            <Label htmlFor="rewardDescription">Описание (опционально)</Label>
                            <Input
                              id="rewardDescription"
                              placeholder="Любой напиток на выбор"
                              {...form.register('rewardDescription')}
                            />
                          </div>
                        </div>
                      </div>

                      <Separator />

                      <div className="space-y-4">
                        <h4 className="font-medium">Погашение награды</h4>
                        <div className="flex items-center justify-between">
                          <div className="space-y-0.5">
                            <Label>Требуется подтверждение кассира</Label>
                            <p className="text-sm text-muted-foreground">
                              Кассир должен подтвердить погашение награды
                            </p>
                          </div>
                          <Controller
                            name="redeemRequiresCashierConfirm"
                            control={form.control}
                            render={({ field }) => (
                              <Switch
                                checked={field.value}
                                onCheckedChange={field.onChange}
                              />
                            )}
                          />
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor="redeemTtl">
                            Время действия кода погашения (минуты)
                          </Label>
                          <Input
                            id="redeemTtl"
                            type="number"
                            className="w-32"
                            {...form.register('redeemCodeTtlMinutes', { valueAsNumber: true })}
                          />
                        </div>
                      </div>
                    </motion.div>
                  )}
                </CardContent>
              </Card>
            </motion.div>
          </TabsContent>

          {/* Discount Tiers Settings */}
          <TabsContent value="discounts">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="space-y-6"
            >
              {/* Накопительные скидки */}
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Percent className="h-5 w-5 text-success" />
                    Накопительные скидки
                  </CardTitle>
                  <CardDescription>
                    Клиенты получают скидки при достижении порогов покупок
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <div className="flex items-center justify-between">
                    <div className="space-y-0.5">
                      <Label>Включить накопительные скидки</Label>
                      <p className="text-sm text-muted-foreground">
                        Система уровней скидок по сумме покупок
                      </p>
                    </div>
                    <Controller
                      name="discountTiersEnabled"
                      control={form.control}
                      render={({ field }) => (
                        <Switch
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                      )}
                    />
                  </div>

                  {watchDiscounts && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="space-y-4 pl-4 border-l-2 border-success/30"
                    >
                      <Alert>
                        <Info className="h-4 w-4" />
                        <AlertDescription>
                          {form.watch('discountValidityDays') === 0
                            ? 'Скидка НЕ сгорает после достижения уровня'
                            : `Скидка действует ${form.watch('discountValidityDays')} дней после достижения уровня`}
                        </AlertDescription>
                      </Alert>

                      {/* Dynamic tiers */}
                      {permanentTierFields.map((field, index) => (
                        <div key={field.id} className="flex items-end gap-3">
                          <div className="space-y-1 flex-1">
                            <Label className="text-xs text-muted-foreground">
                              Сумма покупок (₽)
                            </Label>
                            <Input
                              type="number"
                              placeholder="50000"
                              {...form.register(`permanentDiscountTiersList.${index}.amount`, { valueAsNumber: true })}
                            />
                          </div>
                          <div className="space-y-1 w-28">
                            <Label className="text-xs text-muted-foreground">
                              Скидка (%)
                            </Label>
                            <Input
                              type="number"
                              placeholder="5"
                              {...form.register(`permanentDiscountTiersList.${index}.percent`, { valueAsNumber: true })}
                            />
                          </div>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="text-destructive hover:text-destructive/80 shrink-0"
                            onClick={() => removeTier(index)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      ))}

                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="gap-2"
                        onClick={() => appendTier({ amount: 0, percent: 0 })}
                      >
                        <Plus className="h-4 w-4" />
                        Добавить уровень
                      </Button>

                      <Separator />

                      <div className="flex items-center justify-between">
                        <div className="space-y-0.5">
                          <Label>Бессрочная скидка</Label>
                          <p className="text-sm text-muted-foreground">
                            Скидка не сгорает после достижения уровня
                          </p>
                        </div>
                        <Switch
                          checked={form.watch('discountValidityDays') === 0}
                          onCheckedChange={(checked) => {
                            form.setValue('discountValidityDays', checked ? 0 : 30, { shouldDirty: true })
                          }}
                        />
                      </div>

                      {form.watch('discountValidityDays') !== 0 && (
                        <div className="space-y-2">
                          <Label htmlFor="validityDays">
                            Срок действия скидки (дней)
                          </Label>
                          <Input
                            id="validityDays"
                            type="number"
                            className="w-32"
                            {...form.register('discountValidityDays', { valueAsNumber: true })}
                          />
                          <p className="text-xs text-muted-foreground">
                            После достижения уровня скидка действует указанное количество дней
                          </p>
                        </div>
                      )}
                    </motion.div>
                  )}
                </CardContent>
              </Card>

              {/* Балльная система (кэшбек) */}
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Diamond className="h-5 w-5 text-blue-500" />
                    Балльная система (кэшбек)
                  </CardTitle>
                  <CardDescription>
                    Процент от каждой покупки возвращается клиенту баллами
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <div className="flex items-center justify-between">
                    <div className="space-y-0.5">
                      <Label>Включить балльную систему</Label>
                      <p className="text-sm text-muted-foreground">
                        Клиенты получают баллы с каждой покупки и могут тратить их
                      </p>
                    </div>
                    <Controller
                      name="bonusPointsEnabled"
                      control={form.control}
                      render={({ field }) => (
                        <Switch
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                      )}
                    />
                  </div>

                  {watchBonus && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="space-y-4 pl-4 border-l-2 border-blue-500/30"
                    >
                      <Alert>
                        <Info className="h-4 w-4" />
                        <AlertDescription>
                          Клиент покупает на 1000₽ при кэшбеке {form.watch('bonusCashbackPercent')}% → получает {Math.round(1000 * (form.watch('bonusCashbackPercent') || 0) / 100)} баллов.
                          1 балл = 1₽ при оплате.
                        </AlertDescription>
                      </Alert>

                      <div className="grid gap-4 sm:grid-cols-2">
                        <div className="space-y-2">
                          <Label htmlFor="bonusCashbackPercent">Процент кэшбека</Label>
                          <Input
                            id="bonusCashbackPercent"
                            type="number"
                            {...form.register('bonusCashbackPercent', { valueAsNumber: true })}
                          />
                          <p className="text-xs text-muted-foreground">
                            Сколько % от покупки возвращается баллами
                          </p>
                        </div>
                        <div className="space-y-2">
                          <Label htmlFor="bonusMaxSpendPercent">Максимум оплаты баллами (%)</Label>
                          <Input
                            id="bonusMaxSpendPercent"
                            type="number"
                            {...form.register('bonusMaxSpendPercent', { valueAsNumber: true })}
                          />
                          <p className="text-xs text-muted-foreground">
                            Какую часть покупки можно оплатить баллами (100% = без ограничений)
                          </p>
                        </div>
                      </div>
                    </motion.div>
                  )}
                </CardContent>
              </Card>
            </motion.div>
          </TabsContent>

          {/* Messages Settings */}
          <TabsContent value="messages">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <MessageSquare className="h-5 w-5 text-blue-500" />
                    Кастомные сообщения
                  </CardTitle>
                  <CardDescription>
                    Настройте тексты сообщений, которые бот отправляет клиентам.
                    Оставьте пустым для использования текста по умолчанию.
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <Alert>
                    <Info className="h-4 w-4" />
                    <AlertDescription>
                      <strong>Доступные переменные:</strong> {'{shopName}'}, {'{userName}'}, {'{code}'}, {'{stamps}'}, {'{stampsLeft}'}, {'{reward}'}
                    </AlertDescription>
                  </Alert>

                  <div className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="welcomeMessage">Приветственное сообщение</Label>
                      <textarea
                        id="welcomeMessage"
                        className="flex min-h-[100px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                        placeholder="👋 Добро пожаловать в программу лояльности {shopName}!

Для регистрации отправьте свой номер телефона."
                        {...form.register('welcomeMessage')}
                      />
                      <p className="text-xs text-muted-foreground">
                        Отправляется при первом запуске бота (/start)
                      </p>
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label htmlFor="purchaseCodeMessage">Сообщение с кодом покупки</Label>
                      <textarea
                        id="purchaseCodeMessage"
                        className="flex min-h-[100px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                        placeholder="🛍 Код для покупки создан!

📋 Ваш код: *{code}*

⏱ Покажите этот код кассиру в течение 10 минут."
                        {...form.register('purchaseCodeMessage')}
                      />
                      <p className="text-xs text-muted-foreground">
                        Отправляется при генерации кода покупки
                      </p>
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label htmlFor="stampEarnedMessage">Сообщение о полученном штампе</Label>
                      <textarea
                        id="stampEarnedMessage"
                        className="flex min-h-[100px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                        placeholder="☕ +1 штамп!

📊 Всего штампов: {stamps}
До награды: {stampsLeft}

🎁 Награда: {reward}"
                        {...form.register('stampEarnedMessage')}
                      />
                      <p className="text-xs text-muted-foreground">
                        Отправляется при начислении штампа
                      </p>
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label htmlFor="rewardEarnedMessage">Сообщение о получении награды</Label>
                      <textarea
                        id="rewardEarnedMessage"
                        className="flex min-h-[100px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                        placeholder="🎉 Поздравляем! Вы получили награду!

🎁 {reward}

📋 Код для получения: *{code}*

Покажите этот код кассиру."
                        {...form.register('rewardEarnedMessage')}
                      />
                      <p className="text-xs text-muted-foreground">
                        Отправляется при получении награды (все штампы собраны)
                      </p>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </motion.div>
          </TabsContent>
        </Tabs>

        {/* Save Button */}
        <div className="sticky bottom-4 mt-8">
          <Card className={cn(
            'transition-all duration-300',
            isDirty ? 'shadow-lg ring-2 ring-primary/50' : ''
          )}>
            <CardContent className="flex items-center justify-between p-4">
              <div>
                {isDirty ? (
                  <p className="text-sm font-medium text-primary">
                    Есть несохранённые изменения
                  </p>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    Все изменения сохранены
                  </p>
                )}
              </div>
              <Button
                type="submit"
                disabled={!isDirty || updateMutation.isPending}
                className="min-w-32"
              >
                {updateMutation.isPending ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Сохранение...
                  </>
                ) : (
                  <>
                    <Save className="mr-2 h-4 w-4" />
                    Сохранить
                  </>
                )}
              </Button>
            </CardContent>
          </Card>
        </div>
      </form>
    </div>
  )
}

