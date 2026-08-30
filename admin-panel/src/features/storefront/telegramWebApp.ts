export function getTelegramWebApp() {
  return typeof window !== 'undefined' ? window.Telegram?.WebApp : undefined
}

export function getInitData(): string {
  return getTelegramWebApp()?.initData ?? ''
}

export function getTelegramUser() {
  return getTelegramWebApp()?.initDataUnsafe?.user
}

export function ready() {
  getTelegramWebApp()?.ready()
}

export function expand() {
  getTelegramWebApp()?.expand()
}

export function close() {
  getTelegramWebApp()?.close()
}

export function showBackButton(handler: () => void) {
  const backButton = getTelegramWebApp()?.BackButton
  if (!backButton) return
  backButton.onClick(handler)
  backButton.show()
}

export function hideBackButton(handler?: () => void) {
  const backButton = getTelegramWebApp()?.BackButton
  if (!backButton) return
  if (handler) {
    backButton.offClick(handler)
  }
  backButton.hide()
}

export function triggerHapticFeedback(type: 'light' | 'medium' | 'heavy' = 'light') {
  getTelegramWebApp()?.HapticFeedback?.impactOccurred(type)
}

export function applyTelegramTheme(root: HTMLElement) {
  const params = getTelegramWebApp()?.themeParams
  if (!params) return
  if (params.bg_color) root.style.setProperty('--sf-bg', params.bg_color)
  if (params.text_color) root.style.setProperty('--sf-text', params.text_color)
  if (params.hint_color) root.style.setProperty('--sf-hint', params.hint_color)
  if (params.button_color) root.style.setProperty('--sf-button', params.button_color)
  if (params.button_text_color) root.style.setProperty('--sf-button-text', params.button_text_color)
  if (params.secondary_bg_color) root.style.setProperty('--sf-secondary-bg', params.secondary_bg_color)
}
