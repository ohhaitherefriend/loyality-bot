import { useEffect, useRef } from 'react'
import { Outlet } from 'react-router-dom'
import { applyTelegramTheme, expand, ready } from './telegramWebApp'
import './storefront.css'

export function StorefrontApp() {
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    ready()
    expand()
    if (rootRef.current) {
      applyTelegramTheme(rootRef.current)
    }
  }, [])

  return (
    <div ref={rootRef} className="storefront-root">
      <div className="storefront-shell">
        <Outlet />
      </div>
    </div>
  )
}
