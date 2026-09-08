import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  GraduationCap,
  Loader2,
  Mail,
  Pencil,
  Plug,
  Plus,
  RefreshCw,
  Tags,
  Trash2,
  Upload,
  XCircle,
} from 'lucide-react'

import { api, ApiClientError } from '@/api/client'
import type {
  BrandAlias,
  MailAuthMode,
  PriceRoundingPolicy,
  SnapshotMode,
  Supplier,
  SupplierSource,
  UpdateSupplierSourceRequest,
} from '@/api/types'
import { useShopStore } from '@/lib/store'
import { formatDateTime, formatRelativeTime } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
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
  const [editSourceId, setEditSourceId] = useState<number | null>(null)

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
  const brandAliasesQuery = useQuery({
    queryKey: ['brandAliases', shopId],
    queryFn: () => api.listBrandAliases(shopId!),
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

  const updateSourceMutation = useMutation({
    mutationFn: ({ sourceId, request }: { sourceId: number; request: UpdateSupplierSourceRequest }) =>
      api.updateSupplierSource(shopId!, sourceId, request),
    onSuccess: () => {
      toast({ title: 'Источник обновлён' })
      setEditSourceId(null)
      queryClient.invalidateQueries({ queryKey: ['supplierSources', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось обновить источник',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const graduateSourceMutation = useMutation({
    mutationFn: (sourceId: number) => api.graduateSupplierSource(shopId!, sourceId, true),
    onSuccess: () => {
      toast({ title: 'Источник переведён в боевой режим', description: 'Shadow mode выключен, auto-apply включён' })
      setEditSourceId(null)
      queryClient.invalidateQueries({ queryKey: ['supplierSources', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось активировать источник',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const createBrandAliasMutation = useMutation({
    mutationFn: api.createBrandAlias.bind(api),
    onSuccess: () => {
      toast({ title: 'Алиас бренда добавлен' })
      queryClient.invalidateQueries({ queryKey: ['brandAliases', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось добавить алиас',
        description: error instanceof ApiClientError ? error.message : 'Неизвестная ошибка',
        variant: 'destructive',
      })
    },
  })

  const deleteBrandAliasMutation = useMutation({
    mutationFn: (aliasId: number) => api.deleteBrandAlias(shopId!, aliasId),
    onSuccess: () => {
      toast({ title: 'Алиас удалён' })
      queryClient.invalidateQueries({ queryKey: ['brandAliases', shopId] })
    },
    onError: (error: unknown) => {
      toast({
        title: 'Не удалось удалить алиас',
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
  const brandAliases = brandAliasesQuery.data ?? []

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
                <div className="flex items-center gap-2">
                  <span className="font-medium">{source.label}</span>
                  <Badge variant="secondary">{source.supplierName}</Badge>
                  {!source.enabled && <Badge variant="secondary">Отключён</Badge>}
                </div>
                <Button size="sm" variant="outline" onClick={() => setEditSourceId(source.id)}>
                  <Pencil className="h-4 w-4 mr-1" /> Настроить
                </Button>
              </div>
              <div className="text-sm text-muted-foreground space-y-0.5">
                {source.senderAllowlist && <div>Отправители: {source.senderAllowlist.replace(/\n/g, ', ')}</div>}
                {source.subjectPattern && <div>Тема: /{source.subjectPattern}/</div>}
                {source.filenamePattern && <div>Файл: /{source.filenamePattern}/</div>}
                <div className="flex items-center gap-2 pt-1">
                  <Badge variant={source.shadowMode ? 'secondary' : 'success'}>
                    {source.shadowMode ? 'Shadow mode' : 'Боевой режим'}
                  </Badge>
                  <Badge variant={source.autoApply ? 'success' : 'secondary'}>
                    Auto-apply: {source.autoApply ? 'включён' : 'выключен'}
                  </Badge>
                  <Badge variant="outline">{source.snapshotMode} · {source.snapshotScope}</Badge>
                  {source.commissionPercentOverride != null && (
                    <Badge variant="outline">Комиссия: {source.commissionPercentOverride}%</Badge>
                  )}
                </div>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* ===== Brand aliases (Stage 4 matching) ===== */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Tags className="h-5 w-5" /> Алиасы брендов
          </CardTitle>
          <CardDescription>
            Разные написания одного бренда (опечатки, транслитерация, кириллица/латиница) для
            подбора кандидатов при сопоставлении. Например: Chanel / Шанель / Channel.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              const form = new FormData(e.currentTarget)
              const canonicalBrand = String(form.get('canonicalBrand') || '').trim()
              const alias = String(form.get('alias') || '').trim()
              if (!canonicalBrand || !alias) {
                toast({ title: 'Укажите канонический бренд и алиас', variant: 'destructive' })
                return
              }
              createBrandAliasMutation.mutate({ canonicalBrand, alias }, {
                onSuccess: () => (e.currentTarget as HTMLFormElement).reset(),
              })
            }}
          >
            <div>
              <Label htmlFor="canonicalBrand">Канонический бренд</Label>
              <Input id="canonicalBrand" name="canonicalBrand" placeholder="Chanel" required />
            </div>
            <div>
              <Label htmlFor="alias">Алиас</Label>
              <Input id="alias" name="alias" placeholder="Шанель" required />
            </div>
            <Button type="submit" size="sm" disabled={createBrandAliasMutation.isPending}>
              {createBrandAliasMutation.isPending ? (
                <Loader2 className="h-4 w-4 mr-1 animate-spin" />
              ) : (
                <Plus className="h-4 w-4 mr-1" />
              )}
              Добавить
            </Button>
          </form>
          <Separator />
          {brandAliases.length === 0 && (
            <p className="text-sm text-muted-foreground">Алиасов пока нет — добавьте первую группу выше.</p>
          )}
          <div className="flex flex-wrap gap-2">
            {brandAliases.map((alias: BrandAlias) => (
              <Badge key={alias.id} variant="outline" className="flex items-center gap-1.5 py-1.5">
                <span className="font-medium">{alias.canonicalBrand}</span>
                <span className="text-muted-foreground">↔ {alias.alias}</span>
                <button
                  type="button"
                  className="ml-1 text-muted-foreground hover:text-destructive"
                  disabled={deleteBrandAliasMutation.isPending}
                  onClick={() => deleteBrandAliasMutation.mutate(alias.id)}
                  aria-label={`Удалить алиас ${alias.alias}`}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </Badge>
            ))}
          </div>
        </CardContent>
      </Card>

      <EditSourceDialog
        source={sources.find((s) => s.id === editSourceId) ?? null}
        mailboxes={mailboxes}
        open={editSourceId != null}
        onOpenChange={(open) => !open && setEditSourceId(null)}
        onSave={(request) => updateSourceMutation.mutate({ sourceId: editSourceId!, request })}
        onGraduate={() => graduateSourceMutation.mutate(editSourceId!)}
        saving={updateSourceMutation.isPending}
        graduating={graduateSourceMutation.isPending}
      />
    </div>
  )
}

function EditSourceDialog({
  source,
  mailboxes,
  open,
  onOpenChange,
  onSave,
  onGraduate,
  saving,
  graduating,
}: {
  source: SupplierSource | null
  mailboxes: Array<{ id: number; label: string }>
  open: boolean
  onOpenChange: (open: boolean) => void
  onSave: (request: UpdateSupplierSourceRequest) => void
  onGraduate: () => void
  saving: boolean
  graduating: boolean
}) {
  const [confirmGraduateOpen, setConfirmGraduateOpen] = useState(false)
  const [confirmAutoApplyOpen, setConfirmAutoApplyOpen] = useState(false)
  const [pendingAutoApplyRequest, setPendingAutoApplyRequest] = useState<UpdateSupplierSourceRequest | null>(null)

  if (!source) {
    return null
  }

  function submit(form: FormData) {
    const autoApply = form.get('autoApply') === 'on'
    const shadowMode = form.get('shadowMode') === 'on'
    const request: UpdateSupplierSourceRequest = {
      expectedVersion: source!.version,
      label: String(form.get('label') || source!.label),
      mailboxConnectionId: form.get('mailboxConnectionId') ? Number(form.get('mailboxConnectionId')) : undefined,
      clearMailboxConnectionId: !form.get('mailboxConnectionId'),
      senderAllowlist: String(form.get('senderAllowlist') || ''),
      subjectPattern: String(form.get('subjectPattern') || ''),
      filenamePattern: String(form.get('filenamePattern') || ''),
      enabled: form.get('enabled') === 'on',
      snapshotMode: form.get('snapshotMode') as SnapshotMode,
      snapshotScope: String(form.get('snapshotScope') || 'SUPPLIER_ALL'),
      commissionPercentOverride: form.get('commissionPercentOverride')
        ? Number(form.get('commissionPercentOverride'))
        : undefined,
      clearCommissionPercentOverride: !form.get('commissionPercentOverride'),
      roundingPolicy: form.get('roundingPolicy') as PriceRoundingPolicy,
      shadowMode,
      autoApply,
      confirmAutoApply: autoApply && !source!.autoApply ? true : undefined,
      aiAutoApproveMinScoreOverride: form.get('aiAutoApproveMinScoreOverride')
        ? Number(form.get('aiAutoApproveMinScoreOverride'))
        : undefined,
      clearAiAutoApproveMinScoreOverride: !form.get('aiAutoApproveMinScoreOverride'),
      aiMinConfidenceOverride: form.get('aiMinConfidenceOverride')
        ? Number(form.get('aiMinConfidenceOverride'))
        : undefined,
      clearAiMinConfidenceOverride: !form.get('aiMinConfidenceOverride'),
    }

    if (autoApply && !source!.autoApply) {
      setPendingAutoApplyRequest(request)
      setConfirmAutoApplyOpen(true)
      return
    }
    onSave(request)
  }

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Настройки источника «{source.label}»</DialogTitle>
            <DialogDescription>
              Комиссия, snapshot-политика, автоматизация и фильтры почты для этого источника.
            </DialogDescription>
          </DialogHeader>
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault()
              submit(new FormData(e.currentTarget))
            }}
          >
            <div>
              <Label htmlFor="edit-label">Название источника</Label>
              <Input id="edit-label" name="label" defaultValue={source.label} required />
            </div>
            <div>
              <Label htmlFor="edit-mailboxConnectionId">Почтовый ящик</Label>
              <Select name="mailboxConnectionId" defaultValue={source.mailboxConnectionId ? String(source.mailboxConnectionId) : ''}>
                <SelectTrigger id="edit-mailboxConnectionId">
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
              <Label htmlFor="edit-senderAllowlist">Разрешённые отправители</Label>
              <Textarea id="edit-senderAllowlist" name="senderAllowlist" defaultValue={source.senderAllowlist ?? ''} />
              <p className="mt-1 text-xs text-muted-foreground">
                Для auto-apply нужен хотя бы один точный email — обычный заголовок From легко подделать.
              </p>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label htmlFor="edit-subjectPattern">Regex темы</Label>
                <Input id="edit-subjectPattern" name="subjectPattern" defaultValue={source.subjectPattern ?? ''} />
              </div>
              <div>
                <Label htmlFor="edit-filenamePattern">Regex имени файла</Label>
                <Input id="edit-filenamePattern" name="filenamePattern" defaultValue={source.filenamePattern ?? ''} />
              </div>
            </div>
            <Separator />
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label htmlFor="edit-snapshotMode">Snapshot</Label>
                <Select name="snapshotMode" defaultValue={source.snapshotMode}>
                  <SelectTrigger id="edit-snapshotMode">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="FULL">FULL (полный ассортимент)</SelectItem>
                    <SelectItem value="DELTA">DELTA (только изменения)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="edit-snapshotScope">Snapshot scope</Label>
                <Input id="edit-snapshotScope" name="snapshotScope" defaultValue={source.snapshotScope} required />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label htmlFor="edit-commissionPercentOverride">Комиссия, % (опц.)</Label>
                <Input
                  id="edit-commissionPercentOverride"
                  name="commissionPercentOverride"
                  type="number"
                  step="0.01"
                  min="0"
                  defaultValue={source.commissionPercentOverride ?? ''}
                  placeholder="Использовать умолчание магазина"
                />
              </div>
              <div>
                <Label htmlFor="edit-roundingPolicy">Округление</Label>
                <Select name="roundingPolicy" defaultValue={source.roundingPolicy}>
                  <SelectTrigger id="edit-roundingPolicy">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="WHOLE_UNIT_HALF_UP">До целых (округление)</SelectItem>
                    <SelectItem value="NO_ROUNDING">Без округления</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label htmlFor="edit-aiAutoApproveMinScoreOverride">AI: мин. score (0..1, опц.)</Label>
                <Input
                  id="edit-aiAutoApproveMinScoreOverride"
                  name="aiAutoApproveMinScoreOverride"
                  type="number"
                  step="0.01"
                  min="0"
                  max="1"
                  defaultValue={source.aiAutoApproveMinScoreOverride ?? ''}
                />
              </div>
              <div>
                <Label htmlFor="edit-aiMinConfidenceOverride">AI: мин. confidence (0..1, опц.)</Label>
                <Input
                  id="edit-aiMinConfidenceOverride"
                  name="aiMinConfidenceOverride"
                  type="number"
                  step="0.01"
                  min="0"
                  max="1"
                  defaultValue={source.aiMinConfidenceOverride ?? ''}
                />
              </div>
            </div>
            <Separator />
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <Label htmlFor="edit-enabled">Источник включён</Label>
                <p className="text-xs text-muted-foreground">Выключенный источник не принимает новые письма/файлы.</p>
              </div>
              <Switch id="edit-enabled" name="enabled" defaultChecked={source.enabled} />
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <Label htmlFor="edit-shadowMode">Shadow mode</Label>
                <p className="text-xs text-muted-foreground">Решения считаются, но каталог не изменяется.</p>
              </div>
              <Switch id="edit-shadowMode" name="shadowMode" defaultChecked={source.shadowMode} />
            </div>
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div>
                <Label htmlFor="edit-autoApply">Auto-apply</Label>
                <p className="text-xs text-muted-foreground">
                  Автоматическое применение безопасных batch без ручного подтверждения.
                </p>
              </div>
              <Switch id="edit-autoApply" name="autoApply" defaultChecked={source.autoApply} />
            </div>

            {source.shadowMode && (
              <Alert>
                <GraduationCap className="h-4 w-4" />
                <AlertTitle>Рекомендуемый путь для нового поставщика</AlertTitle>
                <AlertDescription>
                  Вместо ручного переключения используйте «Перевести в боевой режим» ниже — эта операция
                  дополнительно проверяет, что хотя бы один shadow-прогон прошёл успешно и нет открытых
                  QUARANTINED/FAILED партий или строк NEEDS_REVIEW.
                  <div className="mt-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={graduating}
                      onClick={() => setConfirmGraduateOpen(true)}
                    >
                      {graduating ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <GraduationCap className="mr-1 h-4 w-4" />}
                      Перевести в боевой режим
                    </Button>
                  </div>
                </AlertDescription>
              </Alert>
            )}

            <DialogFooter>
              <Button type="submit" disabled={saving}>
                {saving && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                Сохранить
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmGraduateOpen} onOpenChange={setConfirmGraduateOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-destructive" /> Подтвердите переход в боевой режим
            </DialogTitle>
            <DialogDescription>
              Shadow mode будет выключен, auto-apply включён. Следующий FULL snapshot этого источника
              автоматически скроет с сайта товары, которых больше нет в прайс-листе. Убедитесь, что вы
              проверили shadow-прогоны и очередь исключений для этого источника.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmGraduateOpen(false)}>Отмена</Button>
            <Button
              variant="destructive"
              disabled={graduating}
              onClick={() => {
                setConfirmGraduateOpen(false)
                onGraduate()
              }}
            >
              {graduating && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
              Да, перевести в боевой режим
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmAutoApplyOpen} onOpenChange={setConfirmAutoApplyOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-destructive" /> Включить auto-apply?
            </DialogTitle>
            <DialogDescription>
              Auto-apply означает, что безопасные партии будут применяться к каталогу без ручного
              подтверждения. Для FULL snapshot это включает автоматическое скрытие товаров, пропавших
              из прайс-листа. Продолжить?
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmAutoApplyOpen(false)}>Отмена</Button>
            <Button
              variant="destructive"
              onClick={() => {
                setConfirmAutoApplyOpen(false)
                if (pendingAutoApplyRequest) {
                  onSave(pendingAutoApplyRequest)
                }
              }}
            >
              Да, включить auto-apply
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
