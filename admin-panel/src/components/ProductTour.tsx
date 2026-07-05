import { useState, useEffect, useCallback } from 'react'
import {
  Joyride,
  type EventData,
  type Controls,
  type Step,
  type TooltipRenderProps,
  STATUS,
  ACTIONS,
} from 'react-joyride'
import { X, ArrowRight, ArrowLeft, Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'

const TOUR_STORAGE_KEY = 'zabotik_tour_completed'

const tourSteps: Step[] = [
  {
    target: 'body',
    placement: 'center',
    skipBeacon: true,
    content: 'Давайте покажем, как здесь всё устроено. Это займёт меньше минуты.',
    title: 'Добро пожаловать в Заботик!',
  },
  {
    target: '[data-tour="bot-status"]',
    placement: 'bottom',
    skipBeacon: true,
    content: 'Здесь вы видите, работает ли ваш бот. Кликните на карточку, чтобы перейти к подробностям.',
    title: 'Статус бота',
  },
  {
    target: '[data-tour="settings"]',
    placement: 'bottom',
    skipBeacon: true,
    content: 'Настройте механику лояльности: штампы, скидки, бонусы и награды для ваших клиентов.',
    title: 'Настройки программы',
  },
  {
    target: '[data-tour="links"]',
    placement: 'bottom',
    skipBeacon: true,
    content: 'Получите QR-код для кассы и ссылки для клиентов и кассиров. Распечатайте и разместите на видном месте.',
    title: 'Ссылки и QR',
  },
  {
    target: '[data-tour="reports"]',
    placement: 'bottom',
    skipBeacon: true,
    content: 'Статистика по клиентам, продажам и наградам. Доступны ежедневные, еженедельные и месячные отчёты.',
    title: 'Отчёты',
  },
  {
    target: '[data-tour="navigation"]',
    placement: 'bottom',
    skipBeacon: true,
    content: 'Все разделы админ-панели доступны в верхнем меню. Переходите между ними в любой момент.',
    title: 'Навигация',
  },
  {
    target: '[data-tour="connect"]',
    placement: 'bottom',
    skipBeacon: true,
    content: 'Здесь можно подключить нового бота или посмотреть текущие подключения.',
    title: 'Подключение бота',
  },
  {
    target: '[data-tour="bot-switcher"]',
    placement: 'bottom-end',
    skipBeacon: true,
    content: 'Если у вас несколько ботов — переключайтесь между ними здесь.',
    title: 'Переключатель ботов',
  },
  {
    target: '[data-tour="subscription"]',
    placement: 'top',
    skipBeacon: true,
    content: 'Управление подпиской и оплата. Пробный период уже включён — пользуйтесь всеми функциями.',
    title: 'Подписка',
  },
  {
    target: 'body',
    placement: 'center',
    skipBeacon: true,
    content: 'Начните с настройки вашего бота и раздайте QR-код клиентам. Удачи!',
    title: 'Всё готово!',
  },
]

function CustomTooltip({
  continuous,
  index,
  step,
  backProps,
  closeProps,
  primaryProps,
  tooltipProps,
  size,
  isLastStep,
}: TooltipRenderProps) {
  const isWelcome = index === 0
  const isFinal = isLastStep

  return (
    <div
      {...tooltipProps}
      className="max-w-sm rounded-xl border bg-background shadow-2xl shadow-primary/10 animate-in fade-in zoom-in-95 duration-200"
    >
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div className="flex items-center gap-2">
          {(isWelcome || isFinal) && (
            <Sparkles className="h-4 w-4 text-primary" />
          )}
          <span className="text-sm font-semibold">
            {step.title as string}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">
            {index + 1} / {size}
          </span>
          <button
            {...closeProps}
            className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="px-4 py-3">
        <p className="text-sm text-muted-foreground leading-relaxed">
          {step.content as string}
        </p>
      </div>

      <div className="flex items-center justify-between border-t px-4 py-3">
        <div>
          {index > 0 && (
            <Button
              {...backProps}
              variant="ghost"
              size="sm"
            >
              <ArrowLeft className="mr-1 h-3.5 w-3.5" />
              Назад
            </Button>
          )}
        </div>

        <div className="flex items-center gap-2">
          {!isFinal && index > 0 && (
            <Button
              {...closeProps}
              variant="ghost"
              size="sm"
              className="text-muted-foreground"
            >
              Пропустить
            </Button>
          )}
          {continuous && (
            <Button
              {...primaryProps}
              size="sm"
            >
              {isFinal ? 'Завершить' : isWelcome ? 'Начать' : 'Далее'}
              {!isFinal && <ArrowRight className="ml-1 h-3.5 w-3.5" />}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

export function ProductTour() {
  const [run, setRun] = useState(false)

  useEffect(() => {
    const completed = localStorage.getItem(TOUR_STORAGE_KEY)
    if (!completed) {
      const timer = setTimeout(() => setRun(true), 800)
      return () => clearTimeout(timer)
    }
  }, [])

  const handleEvent = useCallback((data: EventData, _controls: Controls) => {
    const { status, action } = data

    if (status === STATUS.FINISHED || status === STATUS.SKIPPED) {
      setRun(false)
      localStorage.setItem(TOUR_STORAGE_KEY, 'true')
    }

    if (action === ACTIONS.CLOSE) {
      setRun(false)
      localStorage.setItem(TOUR_STORAGE_KEY, 'true')
    }
  }, [])

  if (!run) return null

  return (
    <Joyride
      steps={tourSteps}
      run={run}
      continuous
      scrollToFirstStep={false}
      onEvent={handleEvent}
      tooltipComponent={CustomTooltip}
      options={{
        overlayClickAction: false,
        scrollOffset: 100,
        spotlightPadding: 8,
        spotlightRadius: 12,
        overlayColor: 'rgba(0, 0, 0, 0.4)',
        zIndex: 10000,
      }}
    />
  )
}
