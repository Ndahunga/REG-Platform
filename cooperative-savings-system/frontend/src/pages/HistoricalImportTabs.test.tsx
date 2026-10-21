import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHistoricalImportHistory } from '@/shared/api/historicalImport'
import { fetchHistoricalTransactionBatches } from '@/shared/api/historicalTransactions'
import { renderWithApp } from '@/test/paymentTestHarness'
import { HistoricalImportPage } from './HistoricalImportPage'

vi.mock('@/shared/api/historicalImport', () => ({
  cancelHistoricalImport: vi.fn(),
  confirmHistoricalImport: vi.fn(),
  downloadHistoricalImportTemplate: vi.fn(),
  fetchHistoricalImport: vi.fn(),
  fetchHistoricalImportHistory: vi.fn(),
  previewHistoricalImport: vi.fn(),
}))
vi.mock('@/shared/api/historicalTransactions', () => ({
  downloadHistoricalTransactionTemplate: vi.fn(),
  previewHistoricalTransactions: vi.fn(),
  fetchHistoricalTransactionBatches: vi.fn(),
  fetchHistoricalTransactionRows: vi.fn(),
  fetchHistoricalTransactionEvents: vi.fn(),
  approveHistoricalTransactions: vi.fn(),
  cancelHistoricalTransactions: vi.fn(),
  fetchModelReport: vi.fn(),
}))

const president = { id: 'p', roles: ['PRESIDENT'], permissions: ['CONTRIBUTION_WRITE', 'FUND_AUTHORIZE'] }

describe('historical import page tabs', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchHistoricalImportHistory).mockResolvedValue([])
    vi.mocked(fetchHistoricalTransactionBatches).mockResolvedValue([])
  })

  it('keeps the opening-balance import as it was and adds the guided history import beside it', async () => {
    renderWithApp(<HistoricalImportPage />, { user: president, route: '/historical-import' })
    expect(await screen.findByRole('tab', { name: 'Opening balances' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Historical transactions' })).toBeInTheDocument()
    expect(screen.queryByTestId('historical-transactions-panel')).not.toBeInTheDocument()
    expect(fetchHistoricalTransactionBatches).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('tab', { name: 'Historical transactions' }))
    expect(await screen.findByTestId('historical-transactions-panel')).toBeInTheDocument()
    expect(fetchHistoricalTransactionBatches).toHaveBeenCalledWith('c1')
    expect(screen.getByRole('tab', { name: 'Historical transactions' })).toHaveAttribute('aria-selected', 'true')
  })

  it('asks for a Saving Scheme before showing the history import', async () => {
    renderWithApp(<HistoricalImportPage />, { user: president, cooperativeId: null, route: '/historical-import' })
    await userEvent.click(await screen.findByRole('tab', { name: 'Historical transactions' }))
    expect(await screen.findByText('Choose a Saving Scheme')).toBeInTheDocument()
    expect(fetchHistoricalTransactionBatches).not.toHaveBeenCalled()
  })
})
