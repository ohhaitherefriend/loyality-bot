import { useState, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { QRCodeSVG } from 'qrcode.react'
import {
  Copy,
  Check,
  Download,
  ExternalLink,
  Printer,
  QrCode,
  Users,
  UserCog,
  Info,
  AlertCircle,
} from 'lucide-react'

import { api } from '@/api/client'
import { useShopStore } from '@/lib/store'
import { copyToClipboard, generateQRPayload } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import { Separator } from '@/components/ui/separator'

export function LinksPage() {
  const { shopId, botUsername, platform } = useShopStore()
  const isMax = platform === 'MAX'
  const messengerName = isMax ? 'Max' : 'Telegram'
  const botDomain = isMax ? 'max.ru' : 't.me'
  const [copiedLink, setCopiedLink] = useState<string | null>(null)
  const [locationId, setLocationId] = useState('')
  const qrRef = useRef<HTMLDivElement>(null)

  const { data: links, isLoading } = useQuery({
    queryKey: ['deepLinks', shopId, locationId],
    queryFn: () => api.getDeepLinks(shopId!, locationId || undefined),
    enabled: !!shopId,
  })

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

  const handleDownloadQR = () => {
    const svg = qrRef.current?.querySelector('svg')
    if (!svg) return

    const svgData = new XMLSerializer().serializeToString(svg)
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d')
    const img = new Image()

    img.onload = () => {
      canvas.width = 512
      canvas.height = 512
      ctx?.drawImage(img, 0, 0, 512, 512)
      
      const pngUrl = canvas.toDataURL('image/png')
      const downloadLink = document.createElement('a')
      downloadLink.download = `qr-${botUsername}-${locationId || 'default'}.png`
      downloadLink.href = pngUrl
      downloadLink.click()
    }

    img.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svgData)))
  }

  const handlePrint = () => {
    const printWindow = window.open('', '_blank')
    if (!printWindow) return

    const svg = qrRef.current?.querySelector('svg')
    if (!svg) return

    const svgData = new XMLSerializer().serializeToString(svg)
    
    printWindow.document.write(`
      <!DOCTYPE html>
      <html>
        <head>
          <title>QR-код для ${botUsername}</title>
          <style>
            @page { margin: 0; size: auto; }
            body {
              display: flex;
              flex-direction: column;
              align-items: center;
              justify-content: center;
              min-height: 100vh;
              margin: 0;
              font-family: system-ui, sans-serif;
              padding: 40px;
              box-sizing: border-box;
            }
            .qr-container {
              padding: 32px;
              background: white;
              border-radius: 16px;
              box-shadow: 0 4px 24px rgba(0,0,0,0.1);
            }
            svg { display: block; }
            h2 {
              margin: 24px 0 8px;
              font-size: 24px;
              font-weight: 600;
            }
            p {
              margin: 0;
              color: #666;
              font-size: 14px;
            }
            .instructions {
              margin-top: 32px;
              padding: 24px;
              background: #f5f5f5;
              border-radius: 12px;
              max-width: 300px;
            }
            .instructions h3 {
              margin: 0 0 12px;
              font-size: 16px;
            }
            .instructions ol {
              margin: 0;
              padding-left: 20px;
            }
            .instructions li {
              margin-bottom: 8px;
              font-size: 14px;
            }
          </style>
        </head>
        <body>
          <div class="qr-container">
            ${svgData}
          </div>
          <h2>Программа лояльности</h2>
          <p>Отсканируйте QR-код в ${messengerName}</p>
          <div class="instructions">
            <h3>Как это работает:</h3>
            <ol>
              <li>Откройте камеру в ${messengerName}</li>
              <li>Наведите на QR-код</li>
              <li>Получите баллы за покупку!</li>
            </ol>
          </div>
          <script>
            window.onload = () => window.print();
          </script>
        </body>
      </html>
    `)
    printWindow.document.close()
  }

  // Generate QR payload manually if API fails
  const qrPayload = links?.buyDeepLink || 
    (botUsername && shopId ? generateQRPayload(botUsername, shopId, locationId || undefined, platform || undefined) : '')

  if (isLoading) {
    return (
      <div className="max-w-4xl mx-auto space-y-8">
        <div className="space-y-2">
          <h1 className="text-3xl font-bold tracking-tight">Ссылки и QR-коды</h1>
          <p className="text-muted-foreground">Загрузка...</p>
        </div>
        <div className="grid gap-6 md:grid-cols-2">
          <Card>
            <CardContent className="flex flex-col items-center gap-4 py-8">
              <Skeleton className="h-48 w-48 rounded-xl" />
              <Skeleton className="h-10 w-32" />
            </CardContent>
          </Card>
          <Card>
            <CardContent className="space-y-4 py-8">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </CardContent>
          </Card>
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-4xl mx-auto space-y-8">
      <div className="space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">Ссылки и QR-коды</h1>
        <p className="text-muted-foreground">
          Материалы для распространения среди клиентов и кассиров
        </p>
      </div>

      {/* Location selector */}
      <Card>
        <CardContent className="flex flex-wrap items-end gap-4 pt-6">
          <div className="space-y-2 flex-1 min-w-[200px]">
            <Label htmlFor="locationId">ID локации (опционально)</Label>
            <Input
              id="locationId"
              placeholder="Например: main, branch1"
              value={locationId}
              onChange={(e) => setLocationId(e.target.value)}
            />
          </div>
          <p className="text-sm text-muted-foreground pb-2">
            Если у вас несколько точек, укажите ID для отслеживания
          </p>
        </CardContent>
      </Card>

      <Tabs defaultValue="customers" className="space-y-6">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="customers" className="gap-2">
            <Users className="h-4 w-4" />
            Для клиентов
          </TabsTrigger>
          <TabsTrigger value="staff" className="gap-2">
            <UserCog className="h-4 w-4" />
            Для персонала
          </TabsTrigger>
        </TabsList>

        {/* Customer Links */}
        <TabsContent value="customers">
          <div className="grid gap-6 md:grid-cols-2">
            {/* QR Code */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <QrCode className="h-5 w-5" />
                  QR-код для покупок
                </CardTitle>
                <CardDescription>
                  Клиенты сканируют для получения баллов/штампов
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col items-center gap-4">
                <div 
                  ref={qrRef}
                  className="p-6 bg-white rounded-2xl shadow-inner ring-1 ring-border"
                >
                  <QRCodeSVG
                    value={qrPayload}
                    size={200}
                    level="H"
                    includeMargin={false}
                    bgColor="#ffffff"
                    fgColor="#000000"
                  />
                </div>
                <div className="flex gap-2 flex-wrap justify-center">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleDownloadQR}
                  >
                    <Download className="mr-2 h-4 w-4" />
                    Скачать PNG
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handlePrint}
                  >
                    <Printer className="mr-2 h-4 w-4" />
                    Печать
                  </Button>
                </div>
              </CardContent>
            </Card>

            {/* Links */}
            <Card>
              <CardHeader>
                <CardTitle>Прямые ссылки</CardTitle>
                <CardDescription>
                  Для размещения на сайте или в соцсетях
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="space-y-2">
                  <Label className="text-xs text-muted-foreground uppercase tracking-wider">
                    Ссылка для покупки
                  </Label>
                  <div className="flex gap-2">
                    <Input
                      value={qrPayload}
                      readOnly
                      className="font-mono text-xs"
                    />
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => handleCopy(qrPayload, 'Ссылка для покупки')}
                    >
                      {copiedLink === 'Ссылка для покупки' ? (
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
                      <a href={qrPayload} target="_blank" rel="noopener noreferrer">
                        <ExternalLink className="h-4 w-4" />
                      </a>
                    </Button>
                  </div>
                </div>

                <Separator />

                <Alert>
                  <Info className="h-4 w-4" />
                  <AlertTitle>Как использовать</AlertTitle>
                  <AlertDescription className="text-sm">
                    <ul className="list-disc list-inside space-y-1 mt-2">
                      <li>Распечатайте QR и разместите у кассы</li>
                      <li>Добавьте ссылку на сайт или в Instagram</li>
                      <li>Отправьте постоянным клиентам в рассылке</li>
                    </ul>
                  </AlertDescription>
                </Alert>
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        {/* Staff Links */}
        <TabsContent value="staff">
          <div className="grid gap-6 md:grid-cols-2">
            {/* Admin Link */}
            <Card>
              <CardHeader>
                <CardTitle>Ссылка для кассиров</CardTitle>
                <CardDescription>
                  Отправьте кассирам для регистрации в системе
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="space-y-2">
                  <Label className="text-xs text-muted-foreground uppercase tracking-wider">
                    Административная ссылка
                  </Label>
                  <div className="flex gap-2">
                    <Input
                      value={links?.adminDeepLink || `https://${botDomain}/${botUsername}?start=admin_${shopId}`}
                      readOnly
                      className="font-mono text-xs"
                    />
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => handleCopy(
                        links?.adminDeepLink || `https://${botDomain}/${botUsername}?start=admin_${shopId}`,
                        'Ссылка для админов'
                      )}
                    >
                      {copiedLink === 'Ссылка для админов' ? (
                        <Check className="h-4 w-4 text-success" />
                      ) : (
                        <Copy className="h-4 w-4" />
                      )}
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* Instructions */}
            <Card>
              <CardHeader>
                <CardTitle>Инструкция для кассиров</CardTitle>
                <CardDescription>
                  Как работать с системой лояльности
                </CardDescription>
              </CardHeader>
              <CardContent>
                <ol className="space-y-3">
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary text-sm font-medium">
                      1
                    </span>
                    <div>
                      <p className="font-medium">Перейдите по ссылке</p>
                      <p className="text-sm text-muted-foreground">
                        Откройте административную ссылку в {messengerName}
                      </p>
                    </div>
                  </li>
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary text-sm font-medium">
                      2
                    </span>
                    <div>
                      <p className="font-medium">Введите секретный код</p>
                      <p className="text-sm text-muted-foreground">
                        Получите код у администратора магазина
                      </p>
                    </div>
                  </li>
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary text-sm font-medium">
                      3
                    </span>
                    <div>
                      <p className="font-medium">Подтверждайте покупки</p>
                      <p className="text-sm text-muted-foreground">
                        Когда клиент показывает код, подтвердите его в боте
                      </p>
                    </div>
                  </li>
                </ol>
              </CardContent>
            </Card>
          </div>

          <Alert className="mt-6">
            <AlertCircle className="h-4 w-4" />
            <AlertTitle>Важно</AlertTitle>
            <AlertDescription>
              Не публикуйте административную ссылку публично. 
              Отправляйте её только доверенным сотрудникам через личные сообщения.
            </AlertDescription>
          </Alert>
        </TabsContent>
      </Tabs>
    </div>
  )
}

