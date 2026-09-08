import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { useShopStore } from '@/lib/store'
import { renderWithProviders } from '@/test/test-utils'
import { MailboxesPage } from './MailboxesPage'

const {
  listMailboxesMock,
  listSuppliersMock,
  listSupplierSourcesMock,
  uploadSupplierPriceMock,
} = vi.hoisted(() => ({
  listMailboxesMock: vi.fn(),
  listSuppliersMock: vi.fn(),
  listSupplierSourcesMock: vi.fn(),
  uploadSupplierPriceMock: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    api: {
      listMailboxes: listMailboxesMock,
      listSuppliers: listSuppliersMock,
      listSupplierSources: listSupplierSourcesMock,
      uploadSupplierPrice: uploadSupplierPriceMock,
      listBrandAliases: vi.fn().mockResolvedValue([]),
      testMailbox: vi.fn(),
      pollMailbox: vi.fn(),
      createMailbox: vi.fn(),
      createSupplier: vi.fn(),
      createSupplierSource: vi.fn(),
      updateSupplierSource: vi.fn(),
      graduateSupplierSource: vi.fn(),
      createBrandAlias: vi.fn(),
      deleteBrandAlias: vi.fn(),
    },
  }
})

describe('MailboxesPage manual upload fallback', () => {
  beforeEach(() => {
    useShopStore.setState({ shopId: 'shop-1' })
    listMailboxesMock.mockReset()
    listSuppliersMock.mockReset()
    listSupplierSourcesMock.mockReset()
    uploadSupplierPriceMock.mockReset()

    listMailboxesMock.mockResolvedValue([])
    listSuppliersMock.mockResolvedValue([
      { id: 4, name: 'ООО Ромашка', active: true },
    ])
    listSupplierSourcesMock.mockResolvedValue([
      {
        id: 8,
        label: 'Основной прайс',
        supplierId: 4,
        supplierName: 'ООО Ромашка',
        enabled: true,
        shadowMode: false,
        autoApply: true,
      },
    ])
    uploadSupplierPriceMock.mockResolvedValue({
      importFileId: 11,
      batchId: 12,
      status: 'STORED',
      alreadyExisted: false,
    })
  })

  it('offers upload as a secondary action and sends file with the selected source', async () => {
    const user = userEvent.setup()
    renderWithProviders(<MailboxesPage />)

    const openButton = await screen.findByRole('button', { name: /Загрузить файл вручную/ })
    expect(openButton).toHaveClass('border')
    await user.click(openButton)

    await user.click(screen.getByRole('combobox', { name: 'Источник поставщика' }))
    await user.click(screen.getByRole('option', { name: /ООО Ромашка — Основной прайс/ }))

    const file = new File(
      [new Uint8Array([0x50, 0x4b, 0x03, 0x04])],
      'price.xlsx',
      { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }
    )
    await user.upload(screen.getByLabelText('Excel-файл'), file)

    const submitButton = screen.getByRole('button', { name: 'Передать в обработку' })
    await waitFor(() => expect(submitButton).not.toBeDisabled())
    await user.click(submitButton)

    await waitFor(() =>
      expect(uploadSupplierPriceMock).toHaveBeenCalledWith('shop-1', 8, file)
    )
  })
})
