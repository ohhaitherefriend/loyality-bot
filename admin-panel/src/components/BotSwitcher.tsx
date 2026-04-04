import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Check,
  ChevronDown,
  Plus,
  Bot,
  Loader2,
} from 'lucide-react'

import { api } from '@/api/client'
import type { BotListItem } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { cn } from '@/lib/utils'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Badge } from '@/components/ui/badge'

const statusColors: Record<string, 'success' | 'warning' | 'destructive' | 'secondary'> = {
  ACTIVE: 'success',
  PENDING: 'warning',
  CONNECTING: 'warning',
  ERROR: 'destructive',
  DISABLED: 'secondary',
}

export function BotSwitcher() {
  const navigate = useNavigate()
  const { shopId, botUsername, businessName, setShop } = useShopStore()
  const [open, setOpen] = useState(false)

  const { data: bots, isLoading } = useQuery({
    queryKey: ['botsList'],
    queryFn: () => api.listBots(),
    refetchInterval: 30000, // Refresh every 30s
  })

  const handleSelectBot = async (bot: BotListItem) => {
    setShop({
      shopId: bot.shopId,
      botInstanceId: bot.id,
      botUsername: bot.botUsername,
      businessName: bot.businessName,
      platform: bot.platform,
    })
    setOpen(false)
    
    // Если мы на странице connect, перейти на settings
    if (window.location.pathname === '/connect') {
      navigate('/settings')
    }
  }

  const handleAddNew = () => {
    setOpen(false)
    navigate('/connect')
  }

  // Если нет ботов — показать кнопку подключения
  if (!isLoading && (!bots || bots.length === 0) && !shopId) {
    return (
      <Button variant="outline" size="sm" onClick={() => navigate('/connect')}>
        <Plus className="mr-2 h-4 w-4" />
        Подключить бота
      </Button>
    )
  }

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" className="gap-2 min-w-[180px] justify-between">
          <div className="flex items-center gap-2 truncate">
            <Bot className="h-4 w-4 shrink-0" />
            <span className="truncate">
              {shopId ? (businessName || `@${botUsername}`) : 'Выберите бота'}
            </span>
          </div>
          <ChevronDown className="h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-[280px]">
        <DropdownMenuLabel>Ваши боты</DropdownMenuLabel>
        <DropdownMenuSeparator />
        
        {isLoading ? (
          <div className="flex items-center justify-center py-4">
            <Loader2 className="h-4 w-4 animate-spin" />
          </div>
        ) : bots && bots.length > 0 ? (
          <>
            {bots.map((bot) => (
              <DropdownMenuItem
                key={bot.id}
                onClick={() => handleSelectBot(bot)}
                className="flex items-center justify-between cursor-pointer"
              >
                <div className="flex items-center gap-2 min-w-0">
                  {shopId === bot.shopId && (
                    <Check className="h-4 w-4 shrink-0 text-primary" />
                  )}
                  <div className={cn("min-w-0", shopId !== bot.shopId && "ml-6")}>
                    <p className="font-medium truncate flex items-center gap-1.5">
                      {bot.businessName || `@${bot.botUsername}`}
                      {bot.platform === 'MAX' && (
                        <span className="inline-flex items-center rounded bg-violet-100 px-1 py-0.5 text-[10px] font-semibold text-violet-700 dark:bg-violet-900/30 dark:text-violet-400">
                          Max
                        </span>
                      )}
                    </p>
                    <p className="text-xs text-muted-foreground truncate">
                      @{bot.botUsername}
                    </p>
                  </div>
                </div>
                <Badge 
                  variant={statusColors[bot.status] || 'secondary'}
                  className="ml-2 shrink-0"
                >
                  {bot.status === 'ACTIVE' ? '●' : '○'}
                </Badge>
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
          </>
        ) : (
          <div className="py-4 text-center text-sm text-muted-foreground">
            Нет подключенных ботов
          </div>
        )}
        
        <DropdownMenuItem onClick={handleAddNew} className="cursor-pointer">
          <Plus className="mr-2 h-4 w-4" />
          Подключить нового бота
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

