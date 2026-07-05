import { PublicLayout } from '@/components/PublicLayout'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export function DetailsPage() {
  return (
    <PublicLayout>
      <div className="mx-auto max-w-3xl px-4 py-16">
        <h1 className="mb-2 text-3xl font-bold tracking-tight">Реквизиты</h1>
        <p className="mb-10 text-sm text-muted-foreground">
          Реквизиты юридического лица — оператора сервиса «Заботик»
        </p>

        <Card>
          <CardHeader>
            <CardTitle>Индивидуальный предприниматель</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-4 sm:grid-cols-2">
              <div>
                <dt className="text-sm text-muted-foreground">Наименование</dt>
                <dd className="font-medium">ИП Усачев Игорь Игоревич</dd>
              </div>
              <div>
                <dt className="text-sm text-muted-foreground">ИНН</dt>
                <dd className="font-medium">772409355105</dd>
              </div>
              <div>
                <dt className="text-sm text-muted-foreground">ОГРНИП</dt>
                <dd className="font-medium">324774600201740</dd>
              </div>
              <div>
                <dt className="text-sm text-muted-foreground">Расчётный счёт</dt>
                <dd className="font-medium">40802810400006092631</dd>
              </div>
            </dl>
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardHeader>
            <CardTitle>Банковские реквизиты</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-4 sm:grid-cols-2">
              <div>
                <dt className="text-sm text-muted-foreground">Банк</dt>
                <dd className="font-medium">АО «ТБанк»</dd>
              </div>
              <div>
                <dt className="text-sm text-muted-foreground">БИК</dt>
                <dd className="font-medium">044525974</dd>
              </div>
              <div>
                <dt className="text-sm text-muted-foreground">ИНН банка</dt>
                <dd className="font-medium">7710140679</dd>
              </div>
              <div>
                <dt className="text-sm text-muted-foreground">Корреспондентский счёт</dt>
                <dd className="font-medium">30101810145250000974</dd>
              </div>
              <div className="sm:col-span-2">
                <dt className="text-sm text-muted-foreground">Юридический адрес банка</dt>
                <dd className="font-medium">
                  127287, г. Москва, ул. Хуторская 2-я, д. 38А, стр. 26
                </dd>
              </div>
            </dl>
          </CardContent>
        </Card>

        <div className="mt-10 rounded-xl border bg-muted/30 p-6 text-center text-sm text-muted-foreground">
          <p>
            По всем вопросам обращайтесь в поддержку:{' '}
            <a
              href="https://t.me/zabotik_support_bot"
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary underline"
            >
              @zabotik_support_bot
            </a>
          </p>
        </div>
      </div>
    </PublicLayout>
  )
}
