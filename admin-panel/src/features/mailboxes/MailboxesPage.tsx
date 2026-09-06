import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  CheckCircle2,
  Clock,
  Loader2,
  Mail,
  Plug,
  Plus,
  RefreshCw,
  Upload,
  XCircle,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type { MailAuthMode, Supplier, SupplierSource } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { formatDateTime, formatRelativeTime } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Alert, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'

const AUTH_MODE_LABELS: Record<MailAuthMode, string> = {
  APP_PASSWORD: 'Пароль приложения',
  OAUTH2: 'OAuth2',
}

function MailboxesPageSkeleton() {
  return (
    <div className="space-y-6">
      {[1, 2].map((i) => (
        <Card key={i}>
          <CardContent className="space-y-3 pt-6">
            <Skeleton className="h-6 w-48" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-3/4" />
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

export function MailboxesPage() {
  const { shopId } = useShopStore()
  const queryClient = useQueryClient()

  const [addMailboxOpen, setAddMailboxOpen] = useState(false)
  const [addSupplierOpen, setAddSupplierOpen] = useState(false)
  const [addSourceOpen, setAddSourceOpen] = useState(false)
  const [sourceSupplierId, setSourceSupplierId] = useState<string>('')
  const [sourceMailboxId, setSourceMailboxId] = useState<string>('')
  const [manualUploadOpen, setManualUploadOpen] = useState(false)
  const [uploadSourceId, setUploadSourceId] = useState<string>('')
  const [uploadFile, setUploadFile] = useState<File | null>(null)

  const mailboxesQuery = useQuery({
    queryKey: ['mailboxes', shopId],
    queryFn: () => api.listMailboxes(shopId!),
    enabled: !!shopId,
    refetchInterval: 30_000,
  })
  const suppliersQuery = useQuery({
    queryKey: ['suppliers', shopId],
    queryFn: () => api.listSuppliers(shopId!),
    enabled: !!shopId,
  })
  const sourcesQuery = useQuery({
    queryKey: ['supplierSources', shopId],
    queryFn: () => api.listSupplierSources(shopId!),
    enabled: !!shopId,
  })

  const testMutation = useMutation({
    mutationFn: (mailboxId: number) => api.testMailbox(shopId!, mailboxId),
    onSuccess: (result) => {
      toast({
        title: result.success ? 'Подключение успешно' : 'Подключение не удалось',
        description: result.message,
        variant: result.success ? 'default' : 'destructive',
      })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Ошибка проверки подключения',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const pollMutation = useMutation({
    mutationFn: (mailboxId: number) => api.pollMailbox(shopId!, mailboxId),
    onSuccess: (result) => {
      toast({
        title: 'Опрос завершён',
        description: `Загружено: ${result.ingestedCount}, пропущено: ${result.skippedCount}${result.message ? ` — ${result.message}` : ''}`,
      })
      queryClient.invalidateQueries({ queryKey: ['mailboxes', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Ошибка опроса ящика',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const createMailboxMutation = useMutation({
    mutationFn: api.createMailbox.bind(api),
    onSuccess: () => {
      toast({ title: 'Ящик добавлен' })
      setAddMailboxOpen(false)
      queryClient.invalidateQueries({ queryKey: ['mailboxes', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось добавить ящик',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const createSupplierMutation = useMutation({
    mutationFn: api.createSupplier.bind(api),
    onSuccess: () => {
      toast({ title: 'Поставщик добавлен' })
      setAddSupplierOpen(false)
      queryClient.invalidateQueries({ queryKey: ['suppliers', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось добавить поставщика',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const createSourceMutation = useMutation({
    mutationFn: api.createSupplierSource.bind(api),
    onSuccess: () => {
      toast({ title: 'Источник добавлен' })
      setAddSourceOpen(false)
      setSourceSupplierId('')
      setSourceMailboxId('')
      queryClient.invalidateQueries({ queryKey: ['supplierSources', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось добавить источник',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const manualUploadMutation = useMutation({
    mutationFn: ({ sourceId, file }: { sourceId: number; file: File }) =>
      api.uploadSupplierPrice(shopId!, sourceId, file),
    onSuccess: (result) => {
      toast({
        title: result.alreadyExisted ? 'Файл уже был принят' : 'Файл принят в обработку',
        description: `Партия #${result.batchId}${result.alreadyExisted ? ' — дубликат не создан' : ''}`,
      })
      setManualUploadOpen(false)
      setUploadSourceId('')
      setUploadFile(null)
      queryClient.invalidateQueries({ queryKey: ['importDashboard', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось загрузить файл',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  if (!shopId) {
    return (
      <Alert>
        <AlertTitle>Магазин не выбран</AlertTitle>
      </Alert>
    )
  }

  if (mailboxesQuery.isLoading || suppliersQuery.isLoading || sourcesQuery.isLoading) {
    return <MailboxesPageSkeleton />
  }

  const mailboxes = mailboxesQuery.data ?? []
  const suppliers = suppliersQuery.data ?? []
  const sources = sourcesQuery.data ?? []

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Импорт поставщиков — почта</h1>
          <p className="text-muted-foreground">
            Почтовые ящики автоматически опрашиваются каждые несколько минут; прайс-листы из
            разрешённых отправителей сохраняются в очередь обработки.
          </p>
        </div>
        <Dialog open={manualUploadOpen} onOpenChange={setManualUploadOpen}>
          <DialogTrigger asChild>
            <Button variant="outline" disabled={sources.length === 0}>
              <Upload className="mr-2 h-4 w-4" /> Загрузить файл вручную
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Ручная загрузка прайс-листа</DialogTitle>
              <DialogDescription>
                Резервный способ для редких случаев. Почта остаётся основным каналом, а файл
                пройдёт тот же автоматический pipeline и правила выбранного источника.
              </DialogDescription>
            </DialogHeader>
            <form
              className="space-y-4"
              onSubmit={(event) => {
                event.preventDefault()
                if (!uploadSourceId || !uploadFile) {
                  toast({ title: 'Выберите источник и Excel-файл', variant: 'destructive' })
                  return
                }
                manualUploadMutation.mutate({
                  sourceId: Number(uploadSourceId),
                  file: uploadFile,
                })
              }}
            >
              <div>
                <Label htmlFor="manual-upload-source">Источник поставщика</Label>
                <Select value={uploadSourceId} onValueChange={setUploadSourceId}>
                  <SelectTrigger id="manual-upload-source">
                    <SelectValue placeholder="Выберите источник" />
                  </SelectTrigger>
                  <SelectContent>
                    {sources.filter((source) => source.enabled).map((source) => (
                      <SelectItem key={source.id} value={String(source.id)}>
                        {source.supplierName} — {source.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="manual-upload-file">Excel-файл</Label>
                <Input
                  id="manual-upload-file"
                  type="file"
                  accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
                  onChange={(event) => setUploadFile(event.target.files?.[0] ?? null)}
                />
                <p className="mt-1 text-xs text-muted-foreground">Допустимы .xlsx и .xls.</p>
              </div>
              <DialogFooter>
                <Button
                  type="submit"
                  disabled={manualUploadMutation.isPending || !uploadSourceId || !uploadFile}
                >
                  {manualUploadMutation.isPending && (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  )}
                  Передать в обработку
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      </div>

      {/* ===== Mailboxes ===== */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Mail className="h-5 w-5" /> Почтовые ящики
            </CardTitle>
            <CardDescription>IMAP-подключения, из которых читаются прайс-листы</CardDescription>
          </div>
          <Dialog open={addMailboxOpen} onOpenChange={setAddMailboxOpen}>
            <DialogTrigger asChild>
              <Button size="sm"><Plus className="h-4 w-4 mr-1" /> Добавить ящик</Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Новый почтовый ящик</DialogTitle>
                <DialogDescription>
                  Пароль шифруется перед сохранением и никогда не показывается повторно.
                </DialogDescription>
              </DialogHeader>
              <form
                className="space-y-3"
                onSubmit={(e) => {
                  e.preventDefault()
                  const form = new FormData(e.currentTarget)
                  createMailboxMutation.mutate({
                    label: String(form.get('label') || ''),
                    host: String(form.get('host') || ''),
                    port: Number(form.get('port') || 993),
                    username: String(form.get('username') || ''),
                    secret: String(form.get('secret') || ''),
                    authMode: 'APP_PASSWORD',
                    folder: String(form.get('folder') || 'INBOX'),
                    useTls: true,
                    enabled: true,
                  })
                }}
              >
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <Label htmlFor="label">Название</Label>
                    <Input id="label" name="label" placeholder="Основной ящик" required />
                  </div>
                  <div>
                    <Label htmlFor="folder">Папка IMAP</Label>
                    <Input id="folder" name="folder" placeholder="INBOX" defaultValue="INBOX" />
                  </div>
                </div>
                <div>
                  <Label htmlFor="host">IMAP host</Label>
                  <Input id="host" name="host" placeholder="imap.mail.ru" required />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <Label htmlFor="port">Порт</Label>
                    <Input id="port" name="port" type="number" defaultValue={993} required />
                  </div>
                  <div>
                    <Label htmlFor="username">Логин / email</Label>
                    <Input id="username" name="username" placeholder="shop@mail.ru" required />
                  </div>
                </div>
                <div>
                  <Label htmlFor="secret">Пароль приложения</Label>
                  <Input id="secret" name="secret" type="password" required />
                </div>
                <DialogFooter>
                  <Button type="submit" disabled={createMailboxMutation.isPending}>
                    {createMailboxMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                    Добавить
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        </CardHeader>
        <CardContent className="space-y-3">
          {mailboxes.length === 0 && (
            <p className="text-sm text-muted-foreground">Пока нет подключённых ящиков.</p>
          )}
          {mailboxes.map((mailbox) => (
            <div key={mailbox.id} className="rounded-lg border p-4 space-y-2">
              <div className="flex items-center justify-between">
                <div>
                  <div className="font-medium">{mailbox.label}</div>
                  <div className="text-sm text-muted-foreground">
                    {mailbox.username} · {mailbox.host}:{mailbox.port} · папка {mailbox.folder}
                    {mailbox.authMode ? ` · ${AUTH_MODE_LABELS[mailbox.authMode]}` : ''}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  {mailbox.enabled ? (
                    <Badge variant="success">Включён</Badge>
                  ) : (
                    <Badge variant="secondary">Отключён</Badge>
                  )}
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => testMutation.mutate(mailbox.id)}
                    disabled={testMutation.isPending}
                  >
                    <Plug className="h-4 w-4 mr-1" /> Проверить
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => pollMutation.mutate(mailbox.id)}
                    disabled={pollMutation.isPending}
                  >
                    <RefreshCw className="h-4 w-4 mr-1" /> Опросить сейчас
                  </Button>
                </div>
              </div>
              <Separator />
              <div className="flex items-center gap-4 text-sm text-muted-foreground">
                {mailbox.lastPollAt ? (
                  <span className="flex items-center gap-1">
                    <Clock className="h-3.5 w-3.5" /> Последний опрос: {formatRelativeTime(mailbox.lastPollAt)}
                  </span>
                ) : (
                  <span>Ещё не опрашивался</span>
                )}
                {mailbox.lastPollSuccessAt && !mailbox.lastPollError && (
                  <span className="flex items-center gap-1 text-green-600">
                    <CheckCircle2 className="h-3.5 w-3.5" /> Успешно {formatDateTime(mailbox.lastPollSuccessAt)}
                  </span>
                )}
                {mailbox.lastPollError && (
                  <span className="flex items-center gap-1 text-destructive">
                    <XCircle className="h-3.5 w-3.5" /> {mailbox.lastPollError}
                  </span>
                )}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* ===== Suppliers ===== */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>Поставщики</CardTitle>
            <CardDescription>Кому принадлежат прайс-листы, приходящие на почту</CardDescription>
          </div>
          <Dialog open={addSupplierOpen} onOpenChange={setAddSupplierOpen}>
            <DialogTrigger asChild>
              <Button size="sm" variant="outline"><Plus className="h-4 w-4 mr-1" /> Добавить поставщика</Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Новый поставщик</DialogTitle>
              </DialogHeader>
              <form
                className="space-y-3"
                onSubmit={(e) => {
                  e.preventDefault()
                  const form = new FormData(e.currentTarget)
                  createSupplierMutation.mutate({
                    name: String(form.get('name') || ''),
                    code: String(form.get('code') || '') || undefined,
                  })
                }}
              >
                <div>
                  <Label htmlFor="name">Название</Label>
                  <Input id="name" name="name" required />
                </div>
                <div>
                  <Label htmlFor="code">Код (опционально)</Label>
                  <Input id="code" name="code" />
                </div>
                <DialogFooter>
                  <Button type="submit" disabled={createSupplierMutation.isPending}>
                    {createSupplierMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                    Добавить
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        </CardHeader>
        <CardContent className="space-y-2">
          {suppliers.length === 0 && (
            <p className="text-sm text-muted-foreground">Поставщиков пока нет.</p>
          )}
          {suppliers.map((supplier: Supplier) => (
            <div key={supplier.id} className="flex items-center justify-between rounded-lg border p-3">
              <span className="font-medium">{supplier.name}</span>
              {supplier.code && <Badge variant="secondary">{supplier.code}</Badge>}
            </div>
          ))}
        </CardContent>
      </Card>

      {/* ===== Supplier sources ===== */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>Источники (правила маршрутизации писем)</CardTitle>
            <CardDescription>
              Какой ящик, какие отправители/тема/имя файла относятся к какому поставщику
            </CardDescription>
          </div>
          <Dialog open={addSourceOpen} onOpenChange={setAddSourceOpen}>
            <DialogTrigger asChild>
              <Button size="sm" variant="outline" disabled={suppliers.length === 0}>
                <Plus className="h-4 w-4 mr-1" /> Добавить источник
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Новый источник</DialogTitle>
                <DialogDescription>
                  Allowlist отправителей — email или домен на строку. Пусто = любой отправитель ящика.
                </DialogDescription>
              </DialogHeader>
              <form
                className="space-y-3"
                onSubmit={(e) => {
                  e.preventDefault()
                  if (!sourceSupplierId) {
                    toast({ title: 'Выберите поставщика', variant: 'destructive' })
                    return
                  }
                  const form = new FormData(e.currentTarget)
                  createSourceMutation.mutate({
                    supplierId: Number(sourceSupplierId),
                    label: String(form.get('label') || ''),
                    mailboxConnectionId: sourceMailboxId ? Number(sourceMailboxId) : undefined,
                    senderAllowlist: String(form.get('senderAllowlist') || '') || undefined,
                    subjectPattern: String(form.get('subjectPattern') || '') || undefined,
                    filenamePattern: String(form.get('filenamePattern') || '') || undefined,
                  })
                }}
              >
                <div>
                  <Label htmlFor="supplierId">Поставщик</Label>
                  <Select value={sourceSupplierId} onValueChange={setSourceSupplierId}>
                    <SelectTrigger id="supplierId">
                      <SelectValue placeholder="Выберите поставщика" />
                    </SelectTrigger>
                    <SelectContent>
                      {suppliers.map((s) => (
                        <SelectItem key={s.id} value={String(s.id)}>{s.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div>
                  <Label htmlFor="mailboxConnectionId">Почтовый ящик</Label>
                  <Select value={sourceMailboxId} onValueChange={setSourceMailboxId}>
                    <SelectTrigger id="mailboxConnectionId">
                      <SelectValue placeholder="Не привязан" />
                    </SelectTrigger>
                    <SelectContent>
                      {mailboxes.map((m) => (
                        <SelectItem key={m.id} value={String(m.id)}>{m.label}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div>
                  <Label htmlFor="label">Название источника</Label>
                  <Input id="label" name="label" placeholder="Косметика" required />
                </div>
                <div>
                  <Label htmlFor="senderAllowlist">Разрешённые отправители</Label>
                  <Textarea
                    id="senderAllowlist"
                    name="senderAllowlist"
                    placeholder={'price@supplier.ru\nsupplier.ru'}
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <Label htmlFor="subjectPattern">Regex темы (опц.)</Label>
                    <Input id="subjectPattern" name="subjectPattern" placeholder="прайс" />
                  </div>
                  <div>
                    <Label htmlFor="filenamePattern">Regex имени файла (опц.)</Label>
                    <Input id="filenamePattern" name="filenamePattern" placeholder="cosmetics-.*" />
                  </div>
                </div>
                <DialogFooter>
                  <Button type="submit" disabled={createSourceMutation.isPending}>
                    {createSourceMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                    Добавить
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        </CardHeader>
        <CardContent className="space-y-2">
          {sources.length === 0 && (
            <p className="text-sm text-muted-foreground">Источников пока нет.</p>
          )}
          {sources.map((source: SupplierSource) => (
            <div key={source.id} className="rounded-lg border p-3 space-y-1">
              <div className="flex items-center justify-between">
                <span className="font-medium">{source.label}</span>
                <Badge variant="secondary">{source.supplierName}</Badge>
              </div>
              <div className="text-sm text-muted-foreground space-y-0.5">
                {source.senderAllowlist && <div>Отправители: {source.senderAllowlist.replace(/\n/g, ', ')}</div>}
                {source.subjectPattern && <div>Тема: /{source.subjectPattern}/</div>}
                {source.filenamePattern && <div>Файл: /{source.filenamePattern}/</div>}
                <div className="flex items-center gap-2 pt-1">
                  <Switch checked={source.shadowMode} disabled />
                  <span>Shadow mode (без auto-apply)</span>
                </div>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}
