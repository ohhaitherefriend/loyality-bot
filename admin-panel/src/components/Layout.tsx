import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import {
  Bot,
  Link2,
  Settings,
  Activity,
  BarChart3,
  Menu,
  X,
  LayoutDashboard,
  CreditCard,
  LogOut,
  User,
} from 'lucide-react'
import { useState } from 'react'
import { cn } from '@/lib/utils'
import { useShopStore } from '@/lib/store'
import { useAuthStore } from '@/lib/auth-store'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { BotSwitcher } from '@/components/BotSwitcher'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

const navItems = [
  { path: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, requiresShop: false },
  { path: '/connect', label: 'Подключение', icon: Bot, requiresShop: false },
  { path: '/settings', label: 'Настройки', icon: Settings, requiresShop: true },
  { path: '/links', label: 'Ссылки и QR', icon: Link2, requiresShop: true },
  { path: '/status', label: 'Статус', icon: Activity, requiresShop: true },
  { path: '/reports', label: 'Отчёты', icon: BarChart3, requiresShop: true },
  { path: '/billing', label: 'Биллинг', icon: CreditCard, requiresShop: false },
]

export function Layout() {
  const location = useLocation()
  const navigate = useNavigate()
  const { shopId, botUsername, businessName } = useShopStore()
  const { user, logout } = useAuthStore()
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)

  const filteredNavItems = navItems.filter(
    (item) => !item.requiresShop || shopId
  )

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-background via-background to-accent/20">
      {/* Background pattern */}
      <div className="fixed inset-0 -z-10 opacity-30">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-primary/10 via-transparent to-transparent" />
        <svg className="absolute inset-0 h-full w-full" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <pattern id="grid" width="32" height="32" patternUnits="userSpaceOnUse">
              <path d="M 32 0 L 0 0 0 32" fill="none" stroke="currentColor" strokeWidth="0.5" opacity="0.1" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#grid)" />
        </svg>
      </div>

      {/* Header */}
      <header className="sticky top-0 z-50 border-b bg-background/80 backdrop-blur-lg">
        <div className="container flex h-16 items-center justify-between px-4">
          <div className="flex items-center gap-4">
            {/* Mobile menu button */}
            <Button
              variant="ghost"
              size="icon"
              className="md:hidden"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            >
              {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
            </Button>

            <Link to="/" className="flex items-center gap-3 group">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-primary to-primary/60 text-primary-foreground shadow-lg shadow-primary/25 transition-transform group-hover:scale-105">
                <Bot className="h-5 w-5" />
              </div>
              <div className="hidden sm:block">
                <h1 className="font-semibold tracking-tight">Loyalty Bot</h1>
                <p className="text-xs text-muted-foreground">Админ-панель</p>
              </div>
            </Link>
          </div>

          {/* Desktop Navigation */}
          <nav className="hidden md:flex items-center gap-1">
            {filteredNavItems.map((item) => {
              const isActive = location.pathname === item.path
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={cn(
                    'relative flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg transition-colors',
                    isActive
                      ? 'text-primary'
                      : 'text-muted-foreground hover:text-foreground hover:bg-accent'
                  )}
                >
                  <item.icon className="h-4 w-4" />
                  {item.label}
                  {isActive && (
                    <motion.div
                      layoutId="navbar-indicator"
                      className="absolute inset-0 rounded-lg bg-primary/10 -z-10"
                      transition={{ type: 'spring', duration: 0.5 }}
                    />
                  )}
                </Link>
              )
            })}
          </nav>

          {/* Right side: Bot Switcher + User Menu */}
          <div className="flex items-center gap-2">
            <BotSwitcher />
            
            {/* User Menu */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" className="rounded-full">
                  <User className="h-5 w-5" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>
                  <div className="flex flex-col space-y-1">
                    <p className="text-sm font-medium">{user?.name || 'Пользователь'}</p>
                    <p className="text-xs text-muted-foreground">{user?.email}</p>
                  </div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => navigate('/billing')}>
                  <CreditCard className="mr-2 h-4 w-4" />
                  Биллинг
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={handleLogout} className="text-destructive">
                  <LogOut className="mr-2 h-4 w-4" />
                  Выйти
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>

      {/* Mobile Navigation */}
      {mobileMenuOpen && (
        <motion.div
          initial={{ opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -10 }}
          className="fixed inset-x-0 top-16 z-40 border-b bg-background/95 backdrop-blur-lg md:hidden"
        >
          <nav className="container flex flex-col gap-1 p-4">
            {filteredNavItems.map((item) => {
              const isActive = location.pathname === item.path
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  onClick={() => setMobileMenuOpen(false)}
                  className={cn(
                    'flex items-center gap-3 px-4 py-3 text-sm font-medium rounded-lg transition-colors',
                    isActive
                      ? 'bg-primary/10 text-primary'
                      : 'text-muted-foreground hover:text-foreground hover:bg-accent'
                  )}
                >
                  <item.icon className="h-5 w-5" />
                  {item.label}
                </Link>
              )
            })}
            {shopId && (
              <>
                <Separator className="my-2" />
                <div className="px-4 py-2">
                  <p className="text-sm font-medium">{businessName || botUsername}</p>
                  <p className="text-xs text-muted-foreground">@{botUsername}</p>
                </div>
              </>
            )}
          </nav>
        </motion.div>
      )}

      {/* Main content */}
      <main className="container px-4 py-8">
        <motion.div
          key={location.pathname}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3 }}
        >
          <Outlet />
        </motion.div>
      </main>

      {/* Footer */}
      <footer className="border-t bg-background/50 backdrop-blur-sm">
        <div className="container px-4 py-6">
          <p className="text-center text-sm text-muted-foreground">
            Loyalty Bot Platform — Управление программой лояльности
          </p>
        </div>
      </footer>
    </div>
  )
}

