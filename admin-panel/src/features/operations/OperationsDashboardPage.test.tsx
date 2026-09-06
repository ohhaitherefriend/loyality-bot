import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'

import type { ImportDashboardResponse } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { renderWithProviders } from '@/test/test-utils'
import { OperationsDashboardPage } from './OperationsDashboardPage'

const { getImportDashboardMock } = vi.hoisted(() => ({
  getImportDashboardMock: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    api: {
      getImportDashboard: getImportDashboardMock,
    },
  }
})

const dashboard: ImportDashboardResponse = {
  automationRate: { autoDecidedRows: 180, humanDecidedRows: 20, ratePercent: 90 },
  batches: { total: 12, running: 1, needsAttention: 2, failed: 1, quarantined: 1, applied: 7 },
  exceptionQueueSize: 5,
  mailboxes: [
    {
      mailboxId: 1,
      label: 'Основной ящик',
      enabled: true,
      lastPollAt: '2026-08-01T10:00:00',
      lastPollSuccessAt: '2026-08-01T10:00:00',
      healthy: true,
    },
  ],
  supplierExceptionRates: [
    { supplierId: 3, supplierName: 'ООО Ромашка', totalRows: 100, exceptionRows: 25, exceptionRatePercent: 25 },
  ],
  recentActivity: { windowHours: 24, filesProcessed: 4, rowsProcessed: 200 },
  recentProductChanges: { windowHours: 24, added: 10, updated: 5, priceChanged: 3, removedFromStorefront: 1, reactivated: 2 },
}

describe('OperationsDashboardPage', () => {
  beforeEach(() => {
    useShopStore.setState({ shopId: 'shop-1' })
    getImportDashboardMock.mockReset()
    getImportDashboardMock.mockResolvedValue(dashboard)
  })

  it('renders automation rate, exception queue size and batch counts', async () => {
    renderWithProviders(<OperationsDashboardPage />)

    await waitFor(() => expect(screen.getByText('90.0%')).toBeInTheDocument())
    expect(screen.getByText('Авто: 180 · Вручную: 20')).toBeInTheDocument()
    expect(screen.getAllByText('5').length).toBeGreaterThan(0)
    expect(getImportDashboardMock).toHaveBeenCalledWith('shop-1', 24)
  })

  it('renders mailbox health and supplier exception rate rows', async () => {
    renderWithProviders(<OperationsDashboardPage />)

    await waitFor(() => expect(screen.getByText('Основной ящик')).toBeInTheDocument())
    expect(screen.getByText('ООО Ромашка')).toBeInTheDocument()
    expect(screen.getByText('25.0%')).toBeInTheDocument()
  })
})
