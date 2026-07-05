import { Link } from 'react-router-dom'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowRight,
  CheckCircle,
  Sparkles,
  Zap,
  Shield,
} from 'lucide-react'

import { api } from '@/api/client'
import { resolvePaidPlans } from '@/lib/public-plans-fallback'
import { PublicLayout } from '@/components/PublicLayout'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'

const fadeUp = {
  hidden: { opacity: 0, y: 30 },
  visible: (i: number) => ({
    opacity: 1,
    y: 0,
    transition: { delay: i * 0.1, duration: 0.5, ease: 'easeOut' },
  }),
}

export function PricingPage() {
  const { data: plans } = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.getPlans(),
  })

  const paidPlans = resolvePaidPlans(plans)

  return (
    <PublicLayout>
      <section className="relative overflow-hidden py-20">
        <div className="absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-primary/10 via-transparent to-transparent" />
        <div className="mx-auto max-w-6xl px-4">
          <motion.div
            initial="hidden"
            animate="visible"
            variants={fadeUp}
            custom={0}
            className="mb-16 text-center"
          >
            <h1 className="mb-4 text-4xl font-extrabold tracking-tight md:text-5xl">
              Тарифы
            </h1>
            <p className="mx-auto max-w-2xl text-lg text-muted-foreground">
              Начните с бесплатного пробного периода — 7 дней без привязки карты.
              Выберите план, который подходит вашему бизнесу.
            </p>
          </motion.div>

          <motion.div
            initial="hidden"
            animate="visible"
            className="grid gap-8 md:grid-cols-2 max-w-3xl mx-auto"
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
                    <CardTitle className="text-xl">{plan.name}</CardTitle>
                    <CardDescription>{plan.description}</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="text-4xl font-bold">
                      {plan.priceAmount === 0 ? 'Бесплатно' : `${(plan.priceAmount / 100).toLocaleString()} \u20BD`}
                      {plan.priceAmount > 0 && (
                        <span className="text-base font-normal text-muted-foreground">
                          /{plan.periodDays === 30 ? 'мес' : plan.periodDays >= 365 ? 'год' : `${plan.periodDays} дн.`}
                        </span>
                      )}
                    </div>
                    <Separator className="my-6" />
                    <ul className="space-y-3 text-sm">
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Неограниченные клиенты
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Штампы и награды
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Накопительные скидки
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Бонусный кешбэк
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Fast Checkout
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Автосообщения
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 shrink-0 text-primary" />
                        Аналитика и отчёты
                      </li>
                      {plan.periodDays >= 365 && (
                        <li className="flex items-center gap-2 font-medium text-primary">
                          <Zap className="h-4 w-4 shrink-0" />
                          Экономия 17%
                        </li>
                      )}
                    </ul>
                  </CardContent>
                  <CardFooter>
                    <Button className="w-full" size="lg" asChild>
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
        </div>
      </section>

      {/* FAQ */}
      <section className="border-t bg-muted/30 py-20">
        <div className="mx-auto max-w-3xl px-4">
          <h2 className="mb-10 text-center text-2xl font-bold tracking-tight md:text-3xl">
            Часто задаваемые вопросы
          </h2>
          <div className="space-y-6">
            {[
              {
                q: 'Что включает пробный период?',
                a: 'Полный доступ ко всем функциям на 7 дней. Без привязки карты. Все данные клиентов сохраняются после перехода на платный план.',
              },
              {
                q: 'Что происходит когда trial заканчивается?',
                a: 'Бот приостанавливается до оплаты подписки. Все данные клиентов сохраняются.',
              },
              {
                q: 'Как работает автопродление?',
                a: 'После оплаты подписка автоматически продлевается каждый месяц (или год). Отменить автопродление можно на my.cloudpayments.ru.',
              },
              {
                q: 'Какие способы оплаты принимаются?',
                a: 'Visa, MasterCard, МИР, Apple Pay, Google Pay. Оплата защищена 3-D Secure.',
              },
              {
                q: 'Безопасно ли это?',
                a: 'Да. Оплата обрабатывается через CloudPayments — сертифицированную платёжную систему. Мы не храним данные вашей карты.',
              },
              {
                q: 'Можно ли сменить тариф?',
                a: 'Да. Вы можете перейти с месячного плана на годовой в любое время из личного кабинета.',
              },
            ].map((item) => (
              <div key={item.q} className="rounded-xl border bg-card p-6">
                <h3 className="mb-2 font-semibold">{item.q}</h3>
                <p className="text-sm text-muted-foreground">{item.a}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="py-16">
        <div className="mx-auto max-w-6xl px-4 text-center">
          <h2 className="mb-4 text-2xl font-bold">Готовы начать?</h2>
          <p className="mb-8 text-muted-foreground">
            Создайте аккаунт за 2 минуты и запустите программу лояльности
          </p>
          <Button size="lg" asChild>
            <Link to="/register">
              Попробовать бесплатно
              <ArrowRight className="ml-2 h-4 w-4" />
            </Link>
          </Button>
        </div>
      </section>

      <div className="flex items-center justify-center gap-2 py-4 text-sm text-muted-foreground">
        <Shield className="h-4 w-4" />
        <span>Безопасная оплата через CloudPayments</span>
      </div>
    </PublicLayout>
  )
}
