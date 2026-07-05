import { Link } from 'react-router-dom'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import {
  Stamp,
  BarChart3,
  Gift,
  QrCode,
  Trophy,
  TrendingUp,
  Zap,
  Users,
  Smartphone,
  ArrowRight,
  Check,
  CheckCircle,
  Coffee,
  ShoppingBag,
  Scissors,
  Sparkles,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardFooter, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import { PublicLayout } from '@/components/PublicLayout'
import { api } from '@/api/client'
import { resolvePaidPlans } from '@/lib/public-plans-fallback'

const fadeUp = {
  hidden: { opacity: 0, y: 30 },
  visible: (i: number) => ({
    opacity: 1,
    y: 0,
    transition: { delay: i * 0.1, duration: 0.5, ease: 'easeOut' },
  }),
}

const features = [
  {
    icon: Stamp,
    title: 'Штамп-карты',
    description: 'Цифровые карты лояльности: собирай штампы — получай подарок. Клиенты видят прогресс прямо в мессенджере.',
  },
  {
    icon: TrendingUp,
    title: 'Накопительные скидки',
    description: 'Автоматические скидки по сумме покупок. Чем больше покупает клиент — тем выше его персональная скидка.',
  },
  {
    icon: Gift,
    title: 'Бонусный кешбэк',
    description: 'Начисляйте кешбэк с каждой покупки. Клиенты копят бонусы и оплачивают ими следующие заказы.',
  },
  {
    icon: Zap,
    title: 'Промо-акции',
    description: 'Создавайте промокоды и рассылайте скидки всем клиентам одним нажатием. Новые клиенты получают акции автоматически.',
  },
  {
    icon: Trophy,
    title: 'Достижения',
    description: 'Геймификация: награждайте клиентов за активность. Бейджи, статусы и бонусы за достижение целей.',
  },
  {
    icon: BarChart3,
    title: 'Аналитика и отчёты',
    description: 'Статистика по клиентам, продажам и активности. Ежедневные, еженедельные и месячные отчёты.',
  },
]

const steps = [
  {
    num: '01',
    title: 'Подключите бота',
    description: 'Создайте бота в Telegram или Max и подключите его к Заботику за 2 минуты.',
  },
  {
    num: '02',
    title: 'Настройте программу',
    description: 'Выберите тип лояльности: штампы, скидки или кешбэк. Настройте правила под свой бизнес.',
  },
  {
    num: '03',
    title: 'Клиент сканирует QR',
    description: 'Разместите QR-код на кассе. Клиент сканирует — и сразу попадает в вашего бота.',
  },
  {
    num: '04',
    title: 'Получайте результат',
    description: 'Отслеживайте рост повторных покупок, анализируйте статистику и управляйте всем из админ-панели.',
  },
]

const audiences = [
  {
    icon: Coffee,
    title: 'Кафе и рестораны',
    description: 'Штамп-карты для кофеен, накопительные скидки для ресторанов. Увеличьте частоту визитов.',
  },
  {
    icon: ShoppingBag,
    title: 'Магазины',
    description: 'Бонусный кешбэк, персональные промокоды, статусы клиентов. Выстраивайте долгосрочные отношения.',
  },
  {
    icon: Scissors,
    title: 'Салоны красоты',
    description: 'Каждый 5-й визит бесплатно, напоминания о записи, персональные предложения для постоянных клиентов.',
  },
]

export function LandingPage() {
  const { data: plans } = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.getPlans(),
  })

  const paidPlans = resolvePaidPlans(plans)

  return (
    <PublicLayout>
      {/* Hero */}
      <section className="relative overflow-hidden">
        <div className="absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-primary/10 via-transparent to-transparent" />
        <div className="mx-auto grid max-w-6xl items-center gap-12 px-4 py-20 md:grid-cols-2 md:py-32">
          <motion.div
            initial="hidden"
            animate="visible"
            variants={fadeUp}
            custom={0}
          >
            <div className="mb-4 inline-flex items-center gap-2 rounded-full border bg-background/80 px-4 py-1.5 text-sm text-muted-foreground backdrop-blur">
              <Smartphone className="h-4 w-4" />
              Мессенджер-бот для вашего бизнеса
            </div>
            <h1 className="mb-6 text-4xl font-extrabold tracking-tight md:text-5xl lg:text-6xl">
              <span className="bg-gradient-to-r from-primary to-primary/60 bg-clip-text text-transparent">
                Заботик
              </span>
              <br />
              <span className="text-foreground">
                заботится о ваших клиентах
              </span>
            </h1>
            <p className="mb-8 max-w-lg text-lg text-muted-foreground">
              Программа лояльности прямо в мессенджере. Штампы, скидки, кешбэк, промо-акции — всё, что нужно для роста повторных продаж.
            </p>
            <div className="flex flex-wrap gap-4">
              <Button size="lg" asChild>
                <Link to="/register">
                  Попробовать бесплатно
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
              <Button size="lg" variant="outline" asChild>
                <a href="#features">Узнать больше</a>
              </Button>
            </div>
            <div className="mt-8 flex items-center gap-6 text-sm text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <Check className="h-4 w-4 text-primary" /> 7 дней бесплатно
              </span>
              <span className="flex items-center gap-1.5">
                <Check className="h-4 w-4 text-primary" /> Без привязки карты
              </span>
            </div>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.3, duration: 0.6 }}
            className="flex justify-center"
          >
            <img
              src="/images/hero-phone.png"
              alt="Заботик — бот программы лояльности"
              className="w-full max-w-md rounded-2xl shadow-2xl shadow-primary/10"
            />
          </motion.div>
        </div>
      </section>

      {/* Advantages */}
      <section className="border-y bg-muted/30 py-16">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            className="grid gap-8 md:grid-cols-3"
          >
            {[
              {
                icon: Zap,
                title: 'Просто для клиентов',
                desc: 'Никаких приложений — всё в мессенджере. Клиент нажимает одну кнопку и сразу в программе лояльности.',
              },
              {
                icon: Users,
                title: 'Удобно для бизнеса',
                desc: 'Админ-панель для управления, автоматические отчёты, гибкие настройки. Запуск за 5 минут.',
              },
              {
                icon: Smartphone,
                title: 'Всё в мессенджере',
                desc: 'Telegram, Max — ваши клиенты уже там. Не нужно устанавливать отдельное приложение.',
              },
            ].map((item, i) => (
              <motion.div
                key={item.title}
                variants={fadeUp}
                custom={i}
                className="flex flex-col items-center rounded-2xl border bg-card p-8 text-center shadow-sm"
              >
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-primary/10 text-primary">
                  <item.icon className="h-6 w-6" />
                </div>
                <h3 className="mb-2 text-lg font-semibold">{item.title}</h3>
                <p className="text-sm text-muted-foreground">{item.desc}</p>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* Features */}
      <section id="features" className="py-20">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            variants={fadeUp}
            custom={0}
            className="mb-12 text-center"
          >
            <h2 className="mb-4 text-3xl font-bold tracking-tight md:text-4xl">
              Всё для программы лояльности
            </h2>
            <p className="mx-auto max-w-2xl text-muted-foreground">
              Выберите механику, которая подходит вашему бизнесу — или комбинируйте несколько
            </p>
          </motion.div>

          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            className="grid gap-6 md:grid-cols-2 lg:grid-cols-3"
          >
            {features.map((f, i) => (
              <motion.div
                key={f.title}
                variants={fadeUp}
                custom={i}
                className="group rounded-2xl border bg-card p-6 transition-shadow hover:shadow-lg"
              >
                <div className="mb-4 flex h-11 w-11 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                  <f.icon className="h-5 w-5" />
                </div>
                <h3 className="mb-2 text-base font-semibold">{f.title}</h3>
                <p className="text-sm leading-relaxed text-muted-foreground">
                  {f.description}
                </p>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* How it works */}
      <section className="border-y bg-muted/30 py-20">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            variants={fadeUp}
            custom={0}
            className="mb-12 text-center"
          >
            <h2 className="mb-4 text-3xl font-bold tracking-tight md:text-4xl">
              Как это работает
            </h2>
            <p className="mx-auto max-w-2xl text-muted-foreground">
              От регистрации до первых результатов — 4 простых шага
            </p>
          </motion.div>

          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            className="grid gap-8 md:grid-cols-2 lg:grid-cols-4"
          >
            {steps.map((s, i) => (
              <motion.div key={s.num} variants={fadeUp} custom={i} className="relative">
                <div className="mb-4 text-5xl font-black text-primary/15">{s.num}</div>
                <h3 className="mb-2 text-lg font-semibold">{s.title}</h3>
                <p className="text-sm text-muted-foreground">{s.description}</p>
                {i < steps.length - 1 && (
                  <ArrowRight className="absolute right-0 top-8 hidden h-5 w-5 text-muted-foreground/30 lg:block" />
                )}
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* For whom */}
      <section className="py-20">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            variants={fadeUp}
            custom={0}
            className="mb-12 text-center"
          >
            <h2 className="mb-4 text-3xl font-bold tracking-tight md:text-4xl">
              Для любого бизнеса с повторными клиентами
            </h2>
          </motion.div>

          <div className="grid gap-8 md:grid-cols-3">
            {audiences.map((a, i) => (
              <motion.div
                key={a.title}
                initial="hidden"
                whileInView="visible"
                viewport={{ once: true, margin: '-100px' }}
                variants={fadeUp}
                custom={i}
                className="overflow-hidden rounded-2xl border bg-card shadow-sm"
              >
                <div className="p-6">
                  <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <a.icon className="h-5 w-5" />
                  </div>
                  <h3 className="mb-2 text-lg font-semibold">{a.title}</h3>
                  <p className="text-sm text-muted-foreground">{a.description}</p>
                </div>
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      {/* QR Section */}
      <section className="border-y bg-muted/30 py-16">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            variants={fadeUp}
            custom={0}
            className="flex flex-col items-center gap-8 md:flex-row md:justify-between"
          >
            <div className="max-w-lg">
              <h2 className="mb-4 text-3xl font-bold tracking-tight md:text-4xl">
                QR-код на кассе — и клиент в программе
              </h2>
              <p className="text-muted-foreground">
                Генерируйте уникальный QR-код и deep-ссылку для вашего бота.
                Разместите на кассе, в меню или на визитке — клиент сканирует и моментально
                присоединяется к программе лояльности.
              </p>
            </div>
            <div className="flex h-40 w-40 items-center justify-center rounded-2xl border-2 border-dashed border-primary/30 bg-card">
              <QrCode className="h-20 w-20 text-primary/40" />
            </div>
          </motion.div>
        </div>
      </section>

      {/* Pricing */}
      <section id="pricing" className="py-20">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            variants={fadeUp}
            custom={0}
            className="mb-12 text-center"
          >
            <h2 className="mb-4 text-3xl font-bold tracking-tight md:text-4xl">
              Простые и прозрачные тарифы
            </h2>
            <p className="mx-auto max-w-2xl text-muted-foreground">
              Начните с бесплатного пробного периода. Выберите план, который подходит вашему бизнесу.
            </p>
          </motion.div>

          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            className="grid gap-6 md:grid-cols-2 max-w-3xl mx-auto"
          >
            {paidPlans.map((plan, i) => (
              <motion.div key={plan.code} variants={fadeUp} custom={i}>
                <Card className="relative h-full">
                  {plan.code === 'BASIC_MONTHLY' && (
                    <Badge className="absolute -top-2 -right-2">
                      <Sparkles className="mr-1 h-3 w-3" />
                      Популярный
                    </Badge>
                  )}
                  <CardHeader>
                    <CardTitle>{plan.name}</CardTitle>
                    <CardDescription>{plan.description}</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="text-3xl font-bold">
                      {plan.priceAmount === 0 ? 'Бесплатно' : `${(plan.priceAmount / 100).toLocaleString()} \u20BD`}
                      {plan.priceAmount > 0 && (
                        <span className="text-sm font-normal text-muted-foreground">
                          /{plan.periodDays === 30 ? 'мес' : plan.periodDays >= 365 ? 'год' : `${plan.periodDays} дн.`}
                        </span>
                      )}
                    </div>
                    <Separator className="my-4" />
                    <ul className="space-y-2 text-sm">
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-primary" />
                        Неограниченные клиенты
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-primary" />
                        Штампы и награды
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-primary" />
                        Накопительные скидки
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-primary" />
                        Fast Checkout
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-primary" />
                        Автосообщения
                      </li>
                      {plan.periodDays >= 365 && (
                        <li className="flex items-center gap-2">
                          <Zap className="h-4 w-4 text-primary" />
                          Экономия 17%
                        </li>
                      )}
                    </ul>
                  </CardContent>
                  <CardFooter>
                    <Button className="w-full" asChild>
                      <Link to="/register">
                        Попробовать бесплатно
                        <ArrowRight className="ml-2 h-4 w-4" />
                      </Link>
                    </Button>
                  </CardFooter>
                </Card>
              </motion.div>
            ))}
          </motion.div>

          <p className="mt-8 text-center text-sm text-muted-foreground">
            7 дней бесплатного пробного периода для всех планов. Без привязки карты.
          </p>
        </div>
      </section>

      {/* Final CTA */}
      <section className="py-20">
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: '-100px' }}
            variants={fadeUp}
            custom={0}
            className="rounded-3xl bg-gradient-to-br from-primary to-primary/70 p-12 text-center text-primary-foreground shadow-2xl shadow-primary/20 md:p-16"
          >
            <h2 className="mb-4 text-3xl font-bold md:text-4xl">
              Начните бесплатно уже сегодня
            </h2>
            <p className="mx-auto mb-8 max-w-xl text-primary-foreground/80">
              7 дней пробного периода. Без привязки карты. Подключите бота за 5 минут и начните возвращать клиентов.
            </p>
            <div className="flex flex-wrap items-center justify-center gap-4">
              <Button size="lg" variant="secondary" asChild>
                <Link to="/register">
                  Создать аккаунт
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
              <Button size="lg" variant="ghost" className="text-primary-foreground hover:text-primary-foreground hover:bg-primary-foreground/10" asChild>
                <Link to="/login">Уже есть аккаунт? Войти</Link>
              </Button>
            </div>
          </motion.div>
        </div>
      </section>
    </PublicLayout>
  )
}
