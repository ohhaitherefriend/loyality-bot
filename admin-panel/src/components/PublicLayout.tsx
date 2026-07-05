import { type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Bot, MessageCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface PublicLayoutProps {
  children: ReactNode
}

export function PublicLayout({ children }: PublicLayoutProps) {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-50 border-b bg-background/80 backdrop-blur-lg">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
          <Link to="/" className="flex items-center gap-2">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-primary to-primary/60 text-primary-foreground shadow-lg shadow-primary/25">
              <Bot className="h-4 w-4" />
            </div>
            <span className="text-lg font-bold tracking-tight">Заботик</span>
          </Link>
          <div className="flex items-center gap-3">
            <Button variant="ghost" size="sm" asChild>
              <Link to="/pricing">Тарифы</Link>
            </Button>
            <Button variant="ghost" asChild>
              <Link to="/login">Войти</Link>
            </Button>
            <Button asChild>
              <Link to="/register">Начать бесплатно</Link>
            </Button>
          </div>
        </div>
      </header>

      {children}

      <footer className="border-t py-8">
        <div className="mx-auto max-w-6xl px-4">
          <div className="flex flex-col items-center gap-6 md:flex-row md:justify-between">
            <div className="flex items-center gap-2">
              <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-primary to-primary/60 text-primary-foreground">
                <Bot className="h-3.5 w-3.5" />
              </div>
              <span className="text-sm font-semibold">Заботик</span>
            </div>
            <div className="flex flex-wrap items-center justify-center gap-4 text-sm text-muted-foreground">
              <Link to="/offer" className="hover:text-foreground transition-colors">
                Оферта
              </Link>
              <span className="text-muted-foreground/40">|</span>
              <Link to="/privacy" className="hover:text-foreground transition-colors">
                Политика конфиденциальности
              </Link>
              <span className="text-muted-foreground/40">|</span>
              <Link to="/details" className="hover:text-foreground transition-colors">
                Реквизиты
              </Link>
            </div>
            <div className="flex items-center gap-4">
              <Button variant="ghost" size="sm" asChild>
                <a href="https://t.me/zabotik_support_bot" target="_blank" rel="noopener noreferrer">
                  <MessageCircle className="mr-2 h-4 w-4" />
                  Написать нам
                </a>
              </Button>
            </div>
          </div>
          <p className="mt-6 text-center text-sm text-muted-foreground">
            &copy; {new Date().getFullYear()} Заботик. ИП Усачев И.И. ИНН 772409355105
          </p>
        </div>
      </footer>

      <a
        href="https://t.me/zabotik_support_bot"
        target="_blank"
        rel="noopener noreferrer"
        className="fixed bottom-6 right-6 z-50 flex h-14 w-14 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg transition-transform hover:scale-110"
        title="Написать в поддержку"
      >
        <MessageCircle className="h-6 w-6" />
      </a>
    </div>
  )
}
