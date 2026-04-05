import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { motion, AnimatePresence } from 'framer-motion'
import { useMutation } from '@tanstack/react-query'
import { QRCodeSVG } from 'qrcode.react'
import {
  Bot,
  Check,
  ArrowRight,
  ArrowLeft,
  Coffee,
  ShoppingBag,
  HelpCircle,
  Loader2,
  AlertCircle,
  Copy,
  ExternalLink,
  Sparkles,
  Diamond,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { LoyaltyMode, MessengerPlatform } from '@/api/types'
import { useAuthStore } from '@/lib/auth-store'
import { useShopStore } from '@/lib/store'
import { copyToClipboard, cn } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'

// Schemas
const botSchema = z.object({
  botToken: z.string().min(40, 'Некорректный токен бота'),
})

const mechanicSchema = z.object({
  shopName: z.string().max(100, 'Максимум 100 символов').optional().or(z.literal('')),
  stampsRequiredForReward: z.preprocess(
    (value) => Number(value),
    z.number().min(2, 'Минимум 2 штампа').max(50, 'Максимум 50')
  ),
  rewardTitle: z.string().max(100, 'Максимум 100 символов').optional().or(z.literal('')),
  bonusPercent: z.preprocess(
    (value) => Number(value),
    z.number().min(1, 'Минимум 1%').max(50, 'Максимум 50%')
  ),
})

type BotFormData = z.infer<typeof botSchema>
type MechanicFormData = z.infer<typeof mechanicSchema>

const loyaltyModes: Array<{
  value: LoyaltyMode | 'UNKNOWN'
  label: string
  subtitle: string
  micro: string
  icon: typeof Coffee
  color: string
}> = [
  {
    value: 'STAMPS',
    label: 'Штампы / визиты',
    subtitle: 'Каждая покупка приближает клиента к награде',
    micro: 'Отлично подходит для кофеен, баров, услуг',
    icon: Coffee,
    color: 'from-amber-500 to-orange-500',
  },
  {
    value: 'BONUS',
    label: 'Балльная система',
    subtitle: 'Процент от покупки возвращается баллами',
    micro: 'Клиенты копят и тратят баллы',
    icon: ShoppingBag,
    color: 'from-emerald-500 to-teal-500',
  },
  {
    value: 'CUMULATIVE_DISCOUNT',
    label: 'Накопительная скидка',
    subtitle: 'Скидка растёт с суммой покупок и не сгорает',
    micro: 'Идеально для розницы',
    icon: Diamond,
    color: 'from-blue-500 to-cyan-500',
  },
  {
    value: 'UNKNOWN',
    label: 'Пока не знаю',
    subtitle: 'Начнём с простой схемы',
    micro: 'Можно изменить позже в настройках',
    icon: HelpCircle,
    color: 'from-violet-500 to-indigo-500',
  },
]

const steps = [
  { id: 1, title: 'Механика', icon: Check },
  { id: 2, title: 'Настройка', icon: Check },
  { id: 3, title: 'Бот', icon: Bot },
  { id: 4, title: 'Готово', icon: Check },
]

export function OnboardingPage() {
  const navigate = useNavigate()
  const { setOnboarding } = useAuthStore()
  const { setShop } = useShopStore()
  
  const [currentStep, setCurrentStep] = useState(1)
  const [loyaltyMode, setLoyaltyMode] = useState<LoyaltyMode>('STAMPS')
  const [modeChoice, setModeChoice] = useState<LoyaltyMode | 'UNKNOWN'>('STAMPS')
  const [shopId, setShopId] = useState<string | null>(null)
  const [buyDeepLink, setBuyDeepLink] = useState<string | null>(null)
  const [adminDeepLink, setAdminDeepLink] = useState<string | null>(null)
  const [botUsername, setBotUsername] = useState<string | null>(null)
  const [copiedLink, setCopiedLink] = useState<string | null>(null)
  const [botConnected, setBotConnected] = useState(false)
  const [stampsPreset, setStampsPreset] = useState<'6' | '8' | '10' | 'custom'>('6')
  const [platform, setPlatform] = useState<MessengerPlatform>('TELEGRAM')
  
  // Forms
  const botForm = useForm<BotFormData>({
    resolver: zodResolver(botSchema),
    defaultValues: {
      botToken: '',
    },
  })

  const mechanicForm = useForm<MechanicFormData>({
    resolver: zodResolver(mechanicSchema),
    defaultValues: {
      shopName: '',
      stampsRequiredForReward: 6,
      rewardTitle: '',
      bonusPercent: 5,
    },
  })
  
  // Mutations
  const createShopMutation = useMutation({
    mutationFn: api.createShopOnboarding.bind(api),
  })
  
  const connectBotMutation = useMutation({
    mutationFn: api.connectBotOnboarding.bind(api),
    onSuccess: (data) => {
      if (data.success) {
        setBotUsername(data.botUsername || null)
        setBuyDeepLink(data.buyDeepLink || null)
        setAdminDeepLink(data.adminDeepLink || null)
        setBotConnected(true)
        
        // Update shop store for existing features
        if (data.shopId && data.botUsername && data.botInstanceId) {
          setShop({
            shopId: data.shopId,
            botInstanceId: data.botInstanceId,
            botUsername: data.botUsername,
            businessName: mechanicForm.getValues('shopName') || undefined,
            platform,
          })
        }
        
        toast({
          title: 'Бот подключен!',
          description: `@${data.botUsername} готов к работе`,
        })
      } else {
        throw new ApiClientError(data.error || 'Ошибка подключения бота')
      }
    },
    onError: (error) => {
      toast({
        title: 'Ошибка подключения',
        description: error instanceof Error ? error.message : 'Проверьте токен и попробуйте снова',
        variant: 'destructive',
      })
    },
  })
  
  const completeMutation = useMutation({
    mutationFn: api.completeOnboarding.bind(api),
    onSuccess: () => {
      setOnboarding(null)
      navigate('/settings')
    },
  })
  
  // Handlers
  const handleMechanicSubmit = () => {
    setCurrentStep(3)
  }
  
  const ensureShopCreated = async () => {
    if (shopId) return shopId
    
    const mechanicValues = mechanicForm.getValues()
    const result = await createShopMutation.mutateAsync({
      name: mechanicValues.shopName || undefined,
      timezone: 'Europe/Moscow',
      loyaltyMode,
      stampsRequiredForReward: mechanicValues.stampsRequiredForReward,
      rewardTitle: mechanicValues.rewardTitle || undefined,
      bonusPercent: mechanicValues.bonusPercent,
    })
    
    if (!result.success || !result.shopId) {
      throw new ApiClientError(result.error || 'Ошибка создания магазина')
    }
    
    setShopId(result.shopId)
    return result.shopId
  }
  
  const handleBotSubmit = async (data: BotFormData) => {
    try {
      const createdShopId = await ensureShopCreated()
      connectBotMutation.mutate({
        shopId: createdShopId,
        botToken: data.botToken,
        platform,
      })
    } catch (error) {
      toast({
        title: 'Ошибка',
        description: error instanceof Error ? error.message : 'Не удалось создать магазин',
        variant: 'destructive',
      })
    }
  }
  
  const handleComplete = () => {
    if (!shopId) return
    completeMutation.mutate({ shopId })
  }
  
  const handleCopy = async (text: string, label: string) => {
    const success = await copyToClipboard(text)
    if (success) {
      setCopiedLink(label)
      setTimeout(() => setCopiedLink(null), 2000)
    }
  }
  
  useEffect(() => {
    if (stampsPreset !== 'custom') {
      mechanicForm.setValue('stampsRequiredForReward', Number(stampsPreset), {
        shouldValidate: true,
      })
    }
  }, [stampsPreset, mechanicForm])
  
  return (
    <div className="min-h-screen bg-gradient-to-br from-background to-muted py-8 px-4">
      <div className="max-w-3xl mx-auto">
        {/* Progress Steps */}
        <div className="mb-8">
          <div className="flex items-center justify-between">
            {steps.map((step, index) => (
              <div key={step.id} className="flex items-center">
                <div
                  className={cn(
                    'flex h-10 w-10 items-center justify-center rounded-full border-2 transition-colors',
                    currentStep >= step.id
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-muted-foreground/30 text-muted-foreground'
                  )}
                >
                  {currentStep > step.id ? (
                    <Check className="h-5 w-5" />
                  ) : (
                    <step.icon className="h-5 w-5" />
                  )}
                </div>
                {index < steps.length - 1 && (
                  <div
                    className={cn(
                      'h-0.5 w-16 md:w-24 lg:w-32 mx-2',
                      currentStep > step.id ? 'bg-primary' : 'bg-muted-foreground/30'
                    )}
                  />
                )}
              </div>
            ))}
          </div>
          <div className="flex justify-between mt-2">
            {steps.map((step) => (
              <span
                key={step.id}
                className={cn(
                  'text-xs font-medium text-center',
                  currentStep >= step.id ? 'text-primary' : 'text-muted-foreground'
                )}
              >
                {step.title}
              </span>
            ))}
          </div>
        </div>
        
        <AnimatePresence mode="wait">
          {/* Step 1: Loyalty Mode */}
          {currentStep === 1 && (
            <motion.div
              key="step-1"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle>Как вы хотите поощрять клиентов?</CardTitle>
                  <CardDescription>
                    Выберите механику — мы сразу настроим систему
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="space-y-6">
                    <div className="grid gap-3 sm:grid-cols-2">
                      {loyaltyModes.map((mode) => {
                        const isSelected = modeChoice === mode.value
                        return (
                          <button
                            key={mode.value}
                            type="button"
                            onClick={() => {
                              setModeChoice(mode.value)
                              setLoyaltyMode(mode.value === 'UNKNOWN' ? 'STAMPS' : mode.value as LoyaltyMode)
                            }}
                            className={cn(
                              'relative flex flex-col items-center gap-2 rounded-xl border-2 p-4 text-center transition-all',
                              isSelected
                                ? 'border-primary bg-primary/5'
                                : 'border-border hover:border-primary/50 hover:bg-accent'
                            )}
                          >
                            <div
                              className={cn(
                                'flex h-12 w-12 items-center justify-center rounded-lg bg-gradient-to-br text-white',
                                mode.color
                              )}
                            >
                              <mode.icon className="h-6 w-6" />
                            </div>
                            <div className="space-y-1">
                              <p className="font-medium">{mode.label}</p>
                              <p className="text-xs text-muted-foreground">{mode.subtitle}</p>
                              <p className="text-xs text-muted-foreground">{mode.micro}</p>
                            </div>
                            {isSelected && (
                              <Badge className="absolute -top-2 -right-2" variant="default">
                                <Check className="h-3 w-3" />
                              </Badge>
                            )}
                          </button>
                        )
                      })}
                    </div>

                    <Button
                      type="button"
                      onClick={() => setCurrentStep(2)}
                      size="lg"
                      className="w-full"
                    >
                      Далее
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </motion.div>
          )}
          
          {/* Step 2: Base Mechanics */}
          {currentStep === 2 && (
            <motion.div
              key="step-2"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle>Давайте сделаем первую награду для клиентов</CardTitle>
                  <CardDescription>
                    Это займёт минуту, всё можно поменять позже
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <form onSubmit={mechanicForm.handleSubmit(handleMechanicSubmit)} className="space-y-6">
                    <div className="space-y-2">
                      <Label htmlFor="shopName">Название (можно позже)</Label>
                      <Input
                        id="shopName"
                        placeholder="Моя кофейня"
                        {...mechanicForm.register('shopName')}
                      />
                    </div>

                    {loyaltyMode === 'STAMPS' && (
                      <>
                        <div className="space-y-3">
                          <Label>Сколько штампов до награды?</Label>
                          <div className="flex flex-wrap gap-2">
                            {['6', '8', '10'].map((value) => (
                              <Button
                                key={value}
                                type="button"
                                variant={stampsPreset === value ? 'default' : 'outline'}
                                onClick={() => setStampsPreset(value as '6' | '8' | '10')}
                              >
                                {value}
                              </Button>
                            ))}
                            <Button
                              type="button"
                              variant={stampsPreset === 'custom' ? 'default' : 'outline'}
                              onClick={() => setStampsPreset('custom')}
                            >
                              Свой
                            </Button>
                          </div>
                          {stampsPreset === 'custom' && (
                            <div className="max-w-xs">
                              <Input
                                type="number"
                                min={2}
                                max={50}
                                {...mechanicForm.register('stampsRequiredForReward')}
                              />
                              {mechanicForm.formState.errors.stampsRequiredForReward && (
                                <p className="text-sm text-destructive">
                                  {mechanicForm.formState.errors.stampsRequiredForReward.message}
                                </p>
                              )}
                            </div>
                          )}
                        </div>
                        <div className="space-y-2">
                          <Label htmlFor="rewardTitle">Что получает клиент?</Label>
                          <Input
                            id="rewardTitle"
                            placeholder="Бесплатный кофе / Скидка / Подарок"
                            {...mechanicForm.register('rewardTitle')}
                          />
                        </div>
                      </>
                    )}

                    {loyaltyMode === 'BONUS' && (
                      <div className="space-y-3">
                        <Label htmlFor="bonusPercent">Процент кэшбека</Label>
                        <div className="max-w-xs">
                          <Input
                            id="bonusPercent"
                            type="number"
                            min={1}
                            max={50}
                            {...mechanicForm.register('bonusPercent')}
                          />
                          {mechanicForm.formState.errors.bonusPercent && (
                            <p className="text-sm text-destructive">
                              {mechanicForm.formState.errors.bonusPercent.message}
                            </p>
                          )}
                        </div>
                        <p className="text-sm text-muted-foreground">
                          Покупка на 1000₽ при {mechanicForm.watch('bonusPercent') || 5}% кэшбеке = {Math.round(1000 * (mechanicForm.watch('bonusPercent') || 5) / 100)} баллов на счёт.
                          Баллами можно оплатить часть следующей покупки.
                        </p>
                      </div>
                    )}

                    {loyaltyMode === 'CUMULATIVE_DISCOUNT' && (
                      <div className="space-y-3">
                        <Label htmlFor="bonusPercent">Базовый процент скидки</Label>
                        <div className="max-w-xs">
                          <Input
                            id="bonusPercent"
                            type="number"
                            min={1}
                            max={50}
                            {...mechanicForm.register('bonusPercent')}
                          />
                          {mechanicForm.formState.errors.bonusPercent && (
                            <p className="text-sm text-destructive">
                              {mechanicForm.formState.errors.bonusPercent.message}
                            </p>
                          )}
                        </div>
                        <p className="text-sm text-muted-foreground">
                          Скидка не сгорает. Мы создадим 3 уровня: {mechanicForm.watch('bonusPercent') || 1}% → {(mechanicForm.watch('bonusPercent') || 1) * 2}% → {(mechanicForm.watch('bonusPercent') || 1) * 3}%.
                          Пороги и уровни можно настроить позже.
                        </p>
                      </div>
                    )}

                    <div className="flex gap-3">
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => setCurrentStep(1)}
                      >
                        <ArrowLeft className="mr-2 h-4 w-4" />
                        Назад
                      </Button>
                      <Button type="submit" className="flex-1">
                        Далее
                        <ArrowRight className="ml-2 h-4 w-4" />
                      </Button>
                    </div>
                  </form>
                </CardContent>
              </Card>
            </motion.div>
          )}
          
          {/* Step 3: Connect Bot */}
          {currentStep === 3 && (
            <motion.div
              key="step-3"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
            >
              <Card>
                <CardHeader>
                  <CardTitle>Подключение бота</CardTitle>
                  <CardDescription>
                    Выберите платформу и вставьте токен. Мы настроим всё автоматически.
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  {/* Platform selector */}
                  <div className="space-y-3">
                    <Label>Платформа</Label>
                    <div className="grid gap-3 sm:grid-cols-2">
                      {[
                        { value: 'TELEGRAM' as MessengerPlatform, label: 'Telegram', color: 'from-sky-500 to-blue-500' },
                        { value: 'MAX' as MessengerPlatform, label: 'Max', color: 'from-violet-500 to-purple-500' },
                      ].map((p) => {
                        const isSelected = platform === p.value
                        return (
                          <button
                            key={p.value}
                            type="button"
                            onClick={() => setPlatform(p.value)}
                            className={cn(
                              'relative flex items-center gap-3 rounded-xl border-2 p-3 transition-all',
                              isSelected
                                ? 'border-primary bg-primary/5'
                                : 'border-border hover:border-primary/50 hover:bg-accent'
                            )}
                          >
                            <div className={cn('flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br text-white', p.color)}>
                              <Bot className="h-4 w-4" />
                            </div>
                            <span className="font-medium">{p.label}</span>
                            {isSelected && (
                              <Badge className="absolute -top-2 -right-2" variant="default">
                                <Check className="h-3 w-3" />
                              </Badge>
                            )}
                          </button>
                        )
                      })}
                    </div>
                  </div>

                  <Alert>
                    <Bot className="h-4 w-4" />
                    <AlertTitle>Как получить токен?</AlertTitle>
                    <AlertDescription>
                      {platform === 'MAX' ? (
                        <>
                          Создайте бота на{' '}
                          <a href="https://business.max.ru/self" target="_blank" rel="noopener noreferrer" className="font-medium underline">
                            business.max.ru
                          </a>
                          {' '}в разделе Чат-боты и скопируйте токен.
                        </>
                      ) : (
                        <>
                          Создайте бота через{' '}
                          <a href="https://t.me/BotFather" target="_blank" rel="noopener noreferrer" className="font-medium underline">
                            @BotFather
                          </a>
                          {' '}и скопируйте токен. Команда: /newbot
                        </>
                      )}
                    </AlertDescription>
                  </Alert>

                  {botConnected && botUsername && (
                    <Alert variant="default" className="border-success/40 bg-success/5">
                      <Check className="h-4 w-4 text-success" />
                      <AlertTitle>Бот подключен</AlertTitle>
                      <AlertDescription>
                        @{botUsername}. Теперь клиенты смогут пользоваться программой лояльности в {platform === 'MAX' ? 'Max' : 'Telegram'}.
                      </AlertDescription>
                    </Alert>
                  )}
                  
                  <form onSubmit={botForm.handleSubmit(handleBotSubmit)} className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="botToken">Токен бота *</Label>
                      <Input
                        id="botToken"
                        type="password"
                        placeholder="123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
                        className="font-mono"
                        {...botForm.register('botToken')}
                      />
                      {botForm.formState.errors.botToken && (
                        <p className="text-sm text-destructive">
                          {botForm.formState.errors.botToken.message}
                        </p>
                      )}
                    </div>
                    
                    {connectBotMutation.isError && (
                      <Alert variant="destructive">
                        <AlertCircle className="h-4 w-4" />
                        <AlertDescription>
                          {connectBotMutation.error instanceof Error
                            ? connectBotMutation.error.message
                            : 'Ошибка подключения'}
                        </AlertDescription>
                      </Alert>
                    )}
                    
                    <div className="flex gap-3">
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => setCurrentStep(2)}
                      >
                        <ArrowLeft className="mr-2 h-4 w-4" />
                        Назад
                      </Button>
                      {botConnected ? (
                        <Button
                          type="button"
                          className="flex-1"
                          onClick={() => setCurrentStep(4)}
                        >
                          Продолжить
                          <ArrowRight className="ml-2 h-4 w-4" />
                        </Button>
                      ) : (
                        <Button
                          type="submit"
                          className="flex-1"
                          disabled={connectBotMutation.isPending || createShopMutation.isPending}
                        >
                          {connectBotMutation.isPending || createShopMutation.isPending ? (
                            <>
                              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                              Подключение...
                            </>
                          ) : (
                            <>
                              Подключить
                              <ArrowRight className="ml-2 h-4 w-4" />
                            </>
                          )}
                        </Button>
                      )}
                    </div>
                  </form>
                </CardContent>
              </Card>
            </motion.div>
          )}
          
          {/* Step 4: Complete */}
          {currentStep === 4 && (
            <motion.div
              key="step-4"
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
            >
              <div className="text-center space-y-6">
                <div className="inline-flex items-center justify-center h-20 w-20 rounded-full bg-success/10 text-success mx-auto">
                  <Sparkles className="h-10 w-10" />
                </div>
                <h1 className="text-3xl font-bold">Поздравляем!</h1>
                <p className="text-lg text-muted-foreground">
                  Бот @{botUsername} готов к работе
                </p>
              </div>
              
              <div className="grid gap-6 md:grid-cols-2 mt-8">
                {/* QR Code */}
                {buyDeepLink && (
                  <Card>
                    <CardHeader>
                      <CardTitle>QR-код для клиентов</CardTitle>
                      <CardDescription>
                        Распечатайте и разместите на кассе
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="flex flex-col items-center gap-4">
                      <div className="p-4 bg-white rounded-xl shadow-inner">
                        <QRCodeSVG value={buyDeepLink} size={180} level="H" />
                      </div>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => handleCopy(buyDeepLink, 'buy')}
                      >
                        {copiedLink === 'buy' ? (
                          <Check className="mr-2 h-4 w-4" />
                        ) : (
                          <Copy className="mr-2 h-4 w-4" />
                        )}
                        Скопировать ссылку
                      </Button>
                    </CardContent>
                  </Card>
                )}
                
                {/* Links */}
                <Card>
                  <CardHeader>
                    <CardTitle>Ссылки</CardTitle>
                    <CardDescription>
                      Для клиентов и администраторов
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {buyDeepLink && (
                      <div className="space-y-2">
                        <Label className="text-xs text-muted-foreground">Для клиентов</Label>
                        <div className="flex gap-2">
                          <Input value={buyDeepLink} readOnly className="font-mono text-xs" />
                          <Button variant="ghost" size="icon" asChild>
                            <a href={buyDeepLink} target="_blank" rel="noopener noreferrer">
                              <ExternalLink className="h-4 w-4" />
                            </a>
                          </Button>
                        </div>
                      </div>
                    )}
                    {adminDeepLink && (
                      <div className="space-y-2">
                        <Label className="text-xs text-muted-foreground">Для кассиров</Label>
                        <div className="flex gap-2">
                          <Input value={adminDeepLink} readOnly className="font-mono text-xs" />
                          <Button variant="ghost" size="icon" asChild>
                            <a href={adminDeepLink} target="_blank" rel="noopener noreferrer">
                              <ExternalLink className="h-4 w-4" />
                            </a>
                          </Button>
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>
              </div>

              <Card className="mt-6">
                <CardHeader>
                  <CardTitle>📌 Как работает</CardTitle>
                </CardHeader>
                <CardContent className="text-sm text-muted-foreground space-y-2">
                  <p>Клиент сканирует QR</p>
                  <p>Бот показывает код</p>
                  <p>Кассир подтверждает покупку</p>
                  <p>Клиент получает награду</p>
                </CardContent>
              </Card>
              
              <div className="mt-8 flex justify-center gap-3">
                <Button size="lg" onClick={handleComplete}>
                  Перейти в настройки
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Button>
                {adminDeepLink && (
                  <Button size="lg" variant="outline" asChild>
                    <a href={adminDeepLink} target="_blank" rel="noopener noreferrer">
                      Открыть админ-бота
                    </a>
                  </Button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  )
}
