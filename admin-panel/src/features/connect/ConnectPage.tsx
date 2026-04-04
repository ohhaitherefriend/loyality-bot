import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import { QRCodeSVG } from 'qrcode.react'
import {
  Bot,
  Coffee,
  ShoppingBag,
  Wrench,
  ArrowRight,
  Check,
  Copy,
  ExternalLink,
  AlertCircle,
  Loader2,
  Sparkles,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { BusinessType, ConnectBotResponse, MessengerPlatform } from '@/api/types'
import { connectBotSchema, type ConnectBotFormData } from '@/lib/validators'
import { copyToClipboard, cn } from '@/lib/utils'
import { useShopStore } from '@/lib/store'
import { toast } from '@/components/ui/use-toast'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'

const businessTypes: Array<{
  value: BusinessType
  label: string
  description: string
  icon: typeof Coffee
  color: string
}> = [
  {
    value: 'COFFEE',
    label: 'Кофейня',
    description: 'Штампы + быстрая покупка',
    icon: Coffee,
    color: 'from-amber-500 to-orange-500',
  },
  {
    value: 'RETAIL',
    label: 'Розница',
    description: 'Накопительные скидки',
    icon: ShoppingBag,
    color: 'from-emerald-500 to-teal-500',
  },
  {
    value: 'SERVICE',
    label: 'Услуги',
    description: 'Баллы за покупки',
    icon: Wrench,
    color: 'from-blue-500 to-indigo-500',
  },
]

export function ConnectPage() {
  const navigate = useNavigate()
  const { setShop, shopId, botUsername: existingBot } = useShopStore()
  const [connectResult, setConnectResult] = useState<ConnectBotResponse | null>(null)
  const [copiedLink, setCopiedLink] = useState<string | null>(null)

  const form = useForm<ConnectBotFormData>({
    resolver: zodResolver(connectBotSchema),
    defaultValues: {
      botToken: '',
      businessType: 'COFFEE',
      businessName: '',
      ownerEmail: '',
      platform: 'TELEGRAM',
    },
  })

  const selectedPlatform = form.watch('platform') as MessengerPlatform

  const connectMutation = useMutation({
    mutationFn: (request: Parameters<typeof api.connectBot>[0]) => api.connectBot(request),
    onSuccess: (data: ConnectBotResponse) => {
      if (data.success && data.shopId && data.botUsername && data.botInstanceId) {
        setShop({
          shopId: data.shopId,
          botInstanceId: data.botInstanceId,
          botUsername: data.botUsername,
          businessName: form.getValues('businessName') || undefined,
          platform: form.getValues('platform') as MessengerPlatform,
        })
        setConnectResult(data)
        toast({
          title: 'Бот подключен!',
          description: `@${data.botUsername} готов к работе`,
          variant: 'default',
        })
      } else {
        throw new ApiClientError(data.error || 'Не удалось подключить бота')
      }
    },
    onError: (error: Error) => {
      const message = error instanceof ApiClientError 
        ? error.message 
        : 'Произошла ошибка при подключении'
      toast({
        title: 'Ошибка подключения',
        description: message,
        variant: 'destructive',
      })
    },
  })

  const selectedType = form.watch('businessType')

  const handleCopy = async (text: string, label: string) => {
    const success = await copyToClipboard(text)
    if (success) {
      setCopiedLink(label)
      setTimeout(() => setCopiedLink(null), 2000)
      toast({
        title: 'Скопировано!',
        description: `${label} скопирована в буфер обмена`,
      })
    }
  }

  const onSubmit = (data: ConnectBotFormData) => {
    connectMutation.mutate({
      botToken: data.botToken,
      businessType: data.businessType,
      businessName: data.businessName || undefined,
      ownerEmail: data.ownerEmail || undefined,
      platform: data.platform,
    })
  }

  // If already connected, show connection info
  if (shopId && existingBot && !connectResult) {
    return (
      <div className="max-w-2xl mx-auto space-y-6">
        <div className="space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Подключение бота</h1>
          <p className="text-muted-foreground">
            У вас уже подключен бот @{existingBot}
          </p>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Check className="h-5 w-5 text-success" />
              Бот подключен
            </CardTitle>
            <CardDescription>
              Вы можете перейти к настройкам или подключить другого бота
            </CardDescription>
          </CardHeader>
          <CardContent className="flex gap-3">
            <Button onClick={() => navigate('/settings')}>
              <ArrowRight className="mr-2 h-4 w-4" />
              Перейти к настройкам
            </Button>
            <Button 
              variant="outline"
              onClick={() => {
                const { clearShop } = useShopStore.getState()
                clearShop()
              }}
            >
              Подключить другого бота
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  // Success state
  if (connectResult?.success) {
    return (
      <div className="max-w-3xl mx-auto space-y-8">
        <motion.div
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          className="text-center space-y-4"
        >
          <div className="inline-flex items-center justify-center h-20 w-20 rounded-full bg-success/10 text-success mx-auto">
            <Sparkles className="h-10 w-10" />
          </div>
          <h1 className="text-3xl font-bold tracking-tight">Поздравляем!</h1>
          <p className="text-lg text-muted-foreground">
            Бот @{connectResult.botUsername} успешно подключен
          </p>
        </motion.div>

        <div className="grid gap-6 md:grid-cols-2">
          {/* QR Code Card */}
          <Card>
            <CardHeader>
              <CardTitle>QR-код для клиентов</CardTitle>
              <CardDescription>
                Распечатайте и разместите на кассе
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col items-center gap-4">
              <div className="p-4 bg-white rounded-xl shadow-inner">
                <QRCodeSVG
                  value={connectResult.buyDeepLink || ''}
                  size={180}
                  level="H"
                  includeMargin={false}
                />
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => handleCopy(connectResult.buyDeepLink || '', 'Ссылка для клиентов')}
              >
                {copiedLink === 'Ссылка для клиентов' ? (
                  <Check className="mr-2 h-4 w-4" />
                ) : (
                  <Copy className="mr-2 h-4 w-4" />
                )}
                Скопировать ссылку
              </Button>
            </CardContent>
          </Card>

          {/* Links Card */}
          <Card>
            <CardHeader>
              <CardTitle>Ссылки</CardTitle>
              <CardDescription>
                Ссылки для клиентов и администраторов
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label className="text-xs text-muted-foreground">Для клиентов</Label>
                <div className="flex gap-2">
                  <Input
                    value={connectResult.buyDeepLink || ''}
                    readOnly
                    className="font-mono text-xs"
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => handleCopy(connectResult.buyDeepLink || '', 'Ссылка для клиентов')}
                  >
                    {copiedLink === 'Ссылка для клиентов' ? (
                      <Check className="h-4 w-4 text-success" />
                    ) : (
                      <Copy className="h-4 w-4" />
                    )}
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    asChild
                  >
                    <a href={connectResult.buyDeepLink} target="_blank" rel="noopener noreferrer">
                      <ExternalLink className="h-4 w-4" />
                    </a>
                  </Button>
                </div>
              </div>

              {connectResult.adminDeepLink && (
                <div className="space-y-2">
                  <Label className="text-xs text-muted-foreground">Для кассиров/админов</Label>
                  <div className="flex gap-2">
                    <Input
                      value={connectResult.adminDeepLink}
                      readOnly
                      className="font-mono text-xs"
                    />
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => handleCopy(connectResult.adminDeepLink || '', 'Ссылка для админов')}
                    >
                      {copiedLink === 'Ссылка для админов' ? (
                        <Check className="h-4 w-4 text-success" />
                      ) : (
                        <Copy className="h-4 w-4" />
                      )}
                    </Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </div>

        {/* Next Steps */}
        <Card>
          <CardHeader>
            <CardTitle>Что дальше?</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 md:grid-cols-3">
              <div className="flex gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary font-semibold">
                  1
                </div>
                <div>
                  <p className="font-medium">Настройте бота</p>
                  <p className="text-sm text-muted-foreground">
                    Задайте параметры штампов, скидок и наград
                  </p>
                </div>
              </div>
              <div className="flex gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary font-semibold">
                  2
                </div>
                <div>
                  <p className="font-medium">Распечатайте QR</p>
                  <p className="text-sm text-muted-foreground">
                    Разместите код на видном месте у кассы
                  </p>
                </div>
              </div>
              <div className="flex gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary font-semibold">
                  3
                </div>
                <div>
                  <p className="font-medium">Обучите кассиров</p>
                  <p className="text-sm text-muted-foreground">
                    Покажите как подтверждать покупки в боте
                  </p>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-center gap-3">
          <Button size="lg" onClick={() => navigate('/settings')}>
            <ArrowRight className="mr-2 h-4 w-4" />
            Перейти к настройкам
          </Button>
          <Button size="lg" variant="outline" onClick={() => navigate('/links')}>
            Ссылки и QR
          </Button>
        </div>
      </div>
    )
  }

  // Connect form
  return (
    <div className="max-w-2xl mx-auto space-y-8">
      <div className="space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">Подключение бота</h1>
        <p className="text-muted-foreground">
          Подключите бота для запуска программы лояльности
        </p>
      </div>

      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
        {/* Platform Selection */}
        <div className="space-y-3">
          <Label>Платформа</Label>
          <div className="grid gap-3 sm:grid-cols-2">
            {[
              { value: 'TELEGRAM' as const, label: 'Telegram', color: 'from-sky-500 to-blue-500' },
              { value: 'MAX' as const, label: 'Max', color: 'from-violet-500 to-purple-500' },
            ].map((p) => {
              const isSelected = selectedPlatform === p.value
              return (
                <button
                  key={p.value}
                  type="button"
                  onClick={() => form.setValue('platform', p.value)}
                  className={cn(
                    'relative flex items-center gap-3 rounded-xl border-2 p-4 transition-all',
                    isSelected
                      ? 'border-primary bg-primary/5'
                      : 'border-border hover:border-primary/50 hover:bg-accent'
                  )}
                >
                  <div className={cn('flex h-10 w-10 items-center justify-center rounded-lg bg-gradient-to-br text-white', p.color)}>
                    <Bot className="h-5 w-5" />
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
          <AlertTitle>Как получить токен бота?</AlertTitle>
          <AlertDescription>
            {selectedPlatform === 'MAX' ? (
              <>
                Создайте бота на{' '}
                <a href="https://business.max.ru/self" target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">
                  business.max.ru
                </a>
                {' '}в разделе Чат-боты и скопируйте токен.
              </>
            ) : (
              <>
                Создайте бота через{' '}
                <a href="https://t.me/BotFather" target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">
                  @BotFather
                </a>
                {' '}в Telegram и скопируйте токен. Команда: /newbot
              </>
            )}
          </AlertDescription>
        </Alert>

        {/* Business Type Selection */}
        <div className="space-y-3">
          <Label>Тип бизнеса</Label>
          <div className="grid gap-3 sm:grid-cols-3">
            {businessTypes.map((type) => {
              const isSelected = selectedType === type.value
              return (
                <button
                  key={type.value}
                  type="button"
                  onClick={() => form.setValue('businessType', type.value)}
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
                      type.color
                    )}
                  >
                    <type.icon className="h-6 w-6" />
                  </div>
                  <div>
                    <p className="font-medium">{type.label}</p>
                    <p className="text-xs text-muted-foreground">
                      {type.description}
                    </p>
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
        </div>

        {/* Bot Token */}
        <div className="space-y-2">
          <Label htmlFor="botToken">Токен бота *</Label>
          <Input
            id="botToken"
            type="password"
            placeholder="123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
            {...form.register('botToken')}
            className="font-mono"
          />
          {form.formState.errors.botToken && (
            <p className="text-sm text-destructive">
              {form.formState.errors.botToken.message}
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            Токен никуда не сохраняется локально, только на сервере
          </p>
        </div>

        {/* Business Name */}
        <div className="space-y-2">
          <Label htmlFor="businessName">Название бизнеса</Label>
          <Input
            id="businessName"
            placeholder="Моя кофейня"
            {...form.register('businessName')}
          />
          {form.formState.errors.businessName && (
            <p className="text-sm text-destructive">
              {form.formState.errors.businessName.message}
            </p>
          )}
        </div>

        {/* Owner Email */}
        <div className="space-y-2">
          <Label htmlFor="ownerEmail">Email владельца (опционально)</Label>
          <Input
            id="ownerEmail"
            type="email"
            placeholder="owner@example.com"
            {...form.register('ownerEmail')}
          />
          {form.formState.errors.ownerEmail && (
            <p className="text-sm text-destructive">
              {form.formState.errors.ownerEmail.message}
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            Для получения уведомлений и отчётов
          </p>
        </div>

        {/* Error Alert */}
        {connectMutation.isError && (
          <Alert variant="destructive">
            <AlertCircle className="h-4 w-4" />
            <AlertTitle>Ошибка подключения</AlertTitle>
            <AlertDescription>
              {connectMutation.error instanceof ApiClientError
                ? connectMutation.error.message
                : 'Произошла неизвестная ошибка. Проверьте токен и попробуйте снова.'}
            </AlertDescription>
          </Alert>
        )}

        {/* Submit Button */}
        <Button
          type="submit"
          size="lg"
          className="w-full"
          disabled={connectMutation.isPending}
        >
          {connectMutation.isPending ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Подключение...
            </>
          ) : (
            <>
              <Bot className="mr-2 h-4 w-4" />
              Подключить бота
            </>
          )}
        </Button>
      </form>
    </div>
  )
}

