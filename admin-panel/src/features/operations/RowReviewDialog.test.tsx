import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { RowDetailResponse } from '@/api/types'
import { renderWithProviders } from '@/test/test-utils'
import { RowReviewDialog } from './RowReviewDialog'

const { getRowDetailMock, reviewRowMock } = vi.hoisted(() => ({
  getRowDetailMock: vi.fn(),
  reviewRowMock: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    api: {
      getRowDetail: getRowDetailMock,
      reviewRow: reviewRowMock,
    },
  }
})

const baseRow: RowDetailResponse = {
  rowId: 42,
  version: 3,
  batchId: 7,
  sourceSheet: 'Косметика и уход',
  sourceRowNumber: 15,
  status: 'NEEDS_REVIEW',
  rawData: { Бренд: 'Nivea', Артикул: 'ABC-1', Номенклатура: 'Крем для рук' },
  normalizedData: { brand: 'NIVEA', name: 'Крем для рук нормализованный' },
  candidates: [
    {
      productId: 101,
      productName: 'Nivea Крем для рук 100мл',
      totalScore: 0.87,
      componentScores: { name: 0.9, brand: 1.0 },
      matchedAttributes: ['brand', 'name'],
      conflicts: [],
    },
  ],
  matchedProductId: undefined,
  matchedProductName: undefined,
  decisions: [],
  createdAt: '2026-08-01T10:00:00',
  updatedAt: undefined,
}

describe('RowReviewDialog', () => {
  beforeEach(() => {
    getRowDetailMock.mockReset()
    reviewRowMock.mockReset()
    getRowDetailMock.mockResolvedValue(baseRow)
  })

  it('renders raw data, normalized data and candidates once the row loads', async () => {
    renderWithProviders(
      <RowReviewDialog shopId="shop-1" rowId={42} open onOpenChange={() => {}} />
    )

    await waitFor(() => expect(screen.getByText('Крем для рук')).toBeInTheDocument())

    expect(screen.getByText('Nivea')).toBeInTheDocument()
    expect(screen.getByText('Крем для рук нормализованный')).toBeInTheDocument()
    expect(screen.getByText('Nivea Крем для рук 100мл')).toBeInTheDocument()
    expect(getRowDetailMock).toHaveBeenCalledWith('shop-1', 42)
  })

  it('does not fetch when the dialog is closed', () => {
    renderWithProviders(
      <RowReviewDialog shopId="shop-1" rowId={42} open={false} onOpenChange={() => {}} />
    )
    expect(getRowDetailMock).not.toHaveBeenCalled()
  })

  it('sends a MATCH review with the selected candidate product id and current version', async () => {
    reviewRowMock.mockResolvedValue({ rowId: 42, version: 4, status: 'APPROVED' })
    const user = userEvent.setup()

    renderWithProviders(
      <RowReviewDialog shopId="shop-1" rowId={42} open onOpenChange={() => {}} />
    )

    await waitFor(() => expect(screen.getByText('Nivea Крем для рук 100мл')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /Выбрать/ }))
    await user.click(screen.getByRole('button', { name: /Подтвердить совпадение/ }))

    await waitFor(() =>
      expect(reviewRowMock).toHaveBeenCalledWith('shop-1', 42, {
        action: 'MATCH',
        expectedVersion: 3,
        productId: 101,
        note: undefined,
      })
    )
  })

  it('sends a NO_MATCH review when the operator clicks "Нет совпадения"', async () => {
    reviewRowMock.mockResolvedValue({ rowId: 42, version: 4, status: 'IGNORED' })
    const user = userEvent.setup()

    renderWithProviders(
      <RowReviewDialog shopId="shop-1" rowId={42} open onOpenChange={() => {}} />
    )

    await waitFor(() => expect(screen.getByText('Nivea')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /Нет совпадения/ }))

    await waitFor(() =>
      expect(reviewRowMock).toHaveBeenCalledWith('shop-1', 42, {
        action: 'NO_MATCH',
        expectedVersion: 3,
        productId: undefined,
        note: undefined,
      })
    )
  })

  it('disables the confirm-match button until a product id is chosen', async () => {
    renderWithProviders(
      <RowReviewDialog shopId="shop-1" rowId={42} open onOpenChange={() => {}} />
    )

    await waitFor(() => expect(screen.getByText('Nivea')).toBeInTheDocument())

    expect(screen.getByRole('button', { name: /Подтвердить совпадение/ })).toBeDisabled()
  })
})
