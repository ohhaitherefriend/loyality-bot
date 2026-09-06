import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import userEvent from '@testing-library/user-event'

import type { BatchDetailResponse } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { createTestQueryClient } from '@/test/test-utils'
import { BatchDetailPage } from './BatchDetailPage'

const { getBatchDetailMock, listBatchRowsMock, resumeBatchMock } = vi.hoisted(() => ({
  getBatchDetailMock: vi.fn(),
  listBatchRowsMock: vi.fn(),
  resumeBatchMock: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    api: {
      getBatchDetail: getBatchDetailMock,
      listBatchRows: listBatchRowsMock,
      resumeBatch: resumeBatchMock,
      getRowDetail: vi.fn(),
    },
  }
})

const batch: BatchDetailResponse = {
  batchId: 9,
  status: 'QUARANTINED',
  supplierSourceId: 5,
  supplierSourceLabel: 'Косметика',
  supplierId: 3,
  supplierName: 'ООО Ромашка',
  originalFilename: 'price-25-06.xlsx',
  totalRows: 100,
  validRows: 90,
  invalidRows: 10,
  attemptNumber: 2,
  errorMessage: 'Schema mismatch',
  rowStatusCounts: { NEEDS_REVIEW: 3, APPLIED: 87 },
  createdAt: '2026-08-01T09:00:00',
}

function renderBatchDetailPage(batchId = '9') {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/operations/batches/${batchId}`]}>
        <Routes>
          <Route path="/operations/batches/:batchId" element={<BatchDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('BatchDetailPage', () => {
  beforeEach(() => {
    useShopStore.setState({ shopId: 'shop-1' })
    getBatchDetailMock.mockReset()
    listBatchRowsMock.mockReset()
    resumeBatchMock.mockReset()

    getBatchDetailMock.mockResolvedValue(batch)
    listBatchRowsMock.mockResolvedValue({
      content: [
        {
          rowId: 1,
          version: 1,
          sourceSheet: 'Косметика и уход',
          sourceRowNumber: 15,
          status: 'NEEDS_REVIEW',
          rawNamePreview: 'Крем для лица',
          brandPreview: 'Nivea',
          supplierPricePreview: 199.5,
          createdAt: '2026-08-01T09:00:00',
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
    })
  })

  it('renders batch info, row status counts and a resume button for quarantined batches', async () => {
    renderBatchDetailPage()

    await waitFor(() => expect(screen.getByText('ООО Ромашка')).toBeInTheDocument())
    expect(screen.getByText('В карантине')).toBeInTheDocument()
    expect(screen.getByText('Schema mismatch')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Перезапустить партию/ })).toBeInTheDocument()
    expect(getBatchDetailMock).toHaveBeenCalledWith('shop-1', 9)
  })

  it('renders rows for the batch', async () => {
    renderBatchDetailPage()

    await waitFor(() => expect(screen.getByText('Крем для лица')).toBeInTheDocument())
    expect(listBatchRowsMock).toHaveBeenCalledWith('shop-1', 9, { page: 0, size: 50, status: undefined })
  })

  it('calls resumeBatch when the resume button is clicked', async () => {
    resumeBatchMock.mockResolvedValue({ batchId: 9, status: 'STORED' })
    const user = userEvent.setup()
    renderBatchDetailPage()

    await waitFor(() => expect(screen.getByRole('button', { name: /Перезапустить партию/ })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /Перезапустить партию/ }))

    await waitFor(() => expect(resumeBatchMock).toHaveBeenCalledWith('shop-1', 9))
  })
})
