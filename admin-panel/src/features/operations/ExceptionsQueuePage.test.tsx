import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { BatchExceptionSummary, RowExceptionSummary } from '@/api/types'
import { useShopStore } from '@/lib/store'
import { renderWithProviders } from '@/test/test-utils'
import { ExceptionsQueuePage } from './ExceptionsQueuePage'

const {
  listRowExceptionsMock,
  listBatchExceptionsMock,
  listSuppliersMock,
  bulkReviewRowsMock,
  resumeBatchMock,
  getRowDetailMock,
} = vi.hoisted(() => ({
  listRowExceptionsMock: vi.fn(),
  listBatchExceptionsMock: vi.fn(),
  listSuppliersMock: vi.fn(),
  bulkReviewRowsMock: vi.fn(),
  resumeBatchMock: vi.fn(),
  getRowDetailMock: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    api: {
      listRowExceptions: listRowExceptionsMock,
      listBatchExceptions: listBatchExceptionsMock,
      listSuppliers: listSuppliersMock,
      bulkReviewRows: bulkReviewRowsMock,
      resumeBatch: resumeBatchMock,
      getRowDetail: getRowDetailMock,
    },
  }
})

const rowException: RowExceptionSummary = {
  rowId: 1,
  version: 1,
  batchId: 9,
  supplierSourceId: 5,
  supplierSourceLabel: 'Косметика',
  supplierId: 3,
  supplierName: 'ООО Ромашка',
  sourceSheet: 'Косметика и уход',
  sourceRowNumber: 12,
  status: 'NEEDS_REVIEW',
  rawNamePreview: 'Крем для лица',
  brandPreview: 'Nivea',
  supplierPricePreview: 199.5,
  createdAt: '2026-08-01T09:00:00',
}

const batchException: BatchExceptionSummary = {
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
  attemptNumber: 1,
  errorMessage: 'Schema mismatch',
  createdAt: '2026-08-01T09:00:00',
  finishedAt: '2026-08-01T09:05:00',
}

function emptyPage<T>(): { content: T[]; page: number; size: number; totalElements: number; totalPages: number } {
  return { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }
}

describe('ExceptionsQueuePage', () => {
  beforeEach(() => {
    useShopStore.setState({ shopId: 'shop-1' })
    listRowExceptionsMock.mockReset()
    listBatchExceptionsMock.mockReset()
    listSuppliersMock.mockReset()
    bulkReviewRowsMock.mockReset()
    resumeBatchMock.mockReset()
    getRowDetailMock.mockReset()

    listSuppliersMock.mockResolvedValue([{ id: 3, name: 'ООО Ромашка' }])
    listRowExceptionsMock.mockResolvedValue({
      content: [rowException],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
    listBatchExceptionsMock.mockResolvedValue({
      content: [batchException],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
  })

  it('renders row exceptions with supplier and preview info', async () => {
    renderWithProviders(<ExceptionsQueuePage />)

    await waitFor(() => expect(screen.getByText('Крем для лица')).toBeInTheDocument())
    expect(screen.getByText('ООО Ромашка')).toBeInTheDocument()
    expect(listRowExceptionsMock).toHaveBeenCalledWith('shop-1', { page: 0, size: 20, supplierId: undefined })
  })

  it('shows an empty state when there are no row exceptions', async () => {
    listRowExceptionsMock.mockResolvedValue(emptyPage())
    renderWithProviders(<ExceptionsQueuePage />)

    await waitFor(() => expect(screen.getByText('Очередь исключений по строкам пуста.')).toBeInTheDocument())
  })

  it('selects a row and triggers a bulk NO_MATCH action', async () => {
    bulkReviewRowsMock.mockResolvedValue({ succeededRowIds: [1], failures: {} })
    const user = userEvent.setup()

    renderWithProviders(<ExceptionsQueuePage />)

    await waitFor(() => expect(screen.getByText('Крем для лица')).toBeInTheDocument())

    const checkboxes = screen.getAllByRole('checkbox')
    // First checkbox is "select all"; the row checkbox is the second one.
    await user.click(checkboxes[1])

    await user.click(screen.getByRole('button', { name: /Нет совпадения/ }))

    await waitFor(() =>
      expect(bulkReviewRowsMock).toHaveBeenCalledWith('shop-1', { rowIds: [1], action: 'NO_MATCH' })
    )
  })

  it('shows batch exceptions with a resume button for quarantined batches', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ExceptionsQueuePage />)

    await user.click(screen.getByRole('tab', { name: 'Партии' }))

    await waitFor(() => expect(screen.getByText(/price-25-06\.xlsx/)).toBeInTheDocument())
    expect(screen.getByText('Schema mismatch')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Перезапустить/ })).toBeInTheDocument()
  })

  it('calls resumeBatch when the resume button is clicked', async () => {
    resumeBatchMock.mockResolvedValue({ batchId: 9, status: 'STORED' })
    const user = userEvent.setup()
    renderWithProviders(<ExceptionsQueuePage />)

    await user.click(screen.getByRole('tab', { name: 'Партии' }))
    await waitFor(() => expect(screen.getByText(/price-25-06\.xlsx/)).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /Перезапустить/ }))

    await waitFor(() => expect(resumeBatchMock).toHaveBeenCalledWith('shop-1', 9))
  })
})
