import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveHistoricalTransactions,
  cancelHistoricalTransactions,
  downloadHistoricalTransactionTemplate,
  fetchHistoricalTransactionBatches,
  fetchHistoricalTransactionEvents,
  fetchHistoricalTransactionRows,
  previewHistoricalTransactions,
} from '@/shared/api/historicalTransactions'
import type { HistoricalBatch, HistoricalRow } from '@/shared/types/historicalTransactions'
import { backendError, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { HistoricalTransactionsPanel } from './HistoricalTransactionsPanel'

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
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const templateMock = vi.mocked(downloadHistoricalTransactionTemplate)
const previewMock = vi.mocked(previewHistoricalTransactions)
const listMock = vi.mocked(fetchHistoricalTransactionBatches)
const rowsMock = vi.mocked(fetchHistoricalTransactionRows)
const eventsMock = vi.mocked(fetchHistoricalTransactionEvents)
const approveMock = vi.mocked(approveHistoricalTransactions)
const cancelMock = vi.mocked(cancelHistoricalTransactions)

const uploader: TestUser = { id: 'uploader', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_WRITE'] }
const authority: TestUser = { id: 'president', roles: ['PRESIDENT'], permissions: ['CONTRIBUTION_WRITE', 'FUND_AUTHORIZE'] }

const batchOf = (extra: Partial<HistoricalBatch> = {}): HistoricalBatch => ({
  id: 'b1',
  status: 'PREVIEWED',
  fileName: 'history.xlsx',
  reused: false,
  totalRows: 12,
  validRows: 12,
  errorRows: 0,
  errorCount: 0,
  canApprove: true,
  currency: 'RWF',
  historyEnd: '2024-05-01',
  openingBalance: '1000',
  declaredClosingBalance: '12550',
  computedClosingBalance: '12550',
  balanceVariance: '0',
  varianceAcknowledged: false,
  varianceNote: null,
  uploadedBy: 'uploader',
  uploadedAt: '2026-10-01T08:00:00Z',
  approvedBy: null,
  approvedAt: null,
  cancelledAt: null,
  summary: {
    reconciliation: {
      currency: 'RWF',
      opening: '1000',
      declaredClosing: '12550',
      computedClosing: '12550',
      variance: '0',
      balanced: true,
      historyEnd: '2024-05-01',
      byYear: [
        { year: 2023, credits: '13150', debits: '3000', net: '10150', rows: 5 },
        { year: 2024, credits: '5700', debits: '4300', net: '1400', rows: 5 },
      ],
      byType: [],
      loans: [{ reference: 'LN1', username: 'a', disbursed: '3000', principalRepaid: '3000', interestPaid: '150', closed: true }],
      unresolvedAttribution: { amount: '15000', rows: 3, byModel: { UNSTATED: '15000' } },
    },
    warnings: ['Row 4: someone is not listed on the Members sheet.'],
    sheetIssues: [],
  },
  ...extra,
})

const invalid = (): HistoricalBatch =>
  batchOf({
    id: 'b2',
    fileName: 'bad.xlsx',
    errorCount: 2,
    errorRows: 2,
    validRows: 10,
    canApprove: false,
    balanceVariance: null,
    summary: {
      reconciliation: { ...batchOf().summary!.reconciliation, variance: null, balanced: false, computedClosing: null, declaredClosing: null },
      warnings: [],
      sheetIssues: [{ code: 'MISSING_SETTING', field: 'DECLARED_CLOSING_BALANCE', message: 'x' }],
    },
  })

const errorRow = (rowNumber: number, code: string): HistoricalRow => ({
  sheet: 'TRANSACTIONS',
  rowNumber,
  rowType: 'DIVIDEND',
  username: 'a',
  date: '2023-01-10',
  amount: '100',
  originalReference: null,
  periodYear: null,
  periodMonth: null,
  loanReference: null,
  operatingModel: null,
  settingKey: null,
  settingValue: null,
  valid: false,
  errors: [{ code, field: 'Row Type', message: 'server text' }],
  posted: false,
})

function render(user: TestUser) {
  return renderWithApp(<HistoricalTransactionsPanel cooperativeId="c1" />, { user })
}

async function chooseAndValidate(file = new File(['x'], 'history.xlsx')) {
  await userEvent.upload(await screen.findByTestId('ht-file'), file)
  await userEvent.click(screen.getByRole('button', { name: 'Validate file' }))
}

describe('historical transactions panel', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listMock.mockResolvedValue([])
    rowsMock.mockResolvedValue([])
    eventsMock.mockResolvedValue([])
  })

  it('explains the rules, offers the template, and starts with no file chosen', async () => {
    templateMock.mockResolvedValue({ filename: 'historical-transactions-template.xlsx' })
    render(uploader)
    const intro = await screen.findByTestId('historical-transactions-intro')
    expect(intro).toHaveTextContent(/exactly two worksheets/)
    expect(intro).toHaveTextContent(/Loans and member payouts cannot be imported/)
    expect(intro).toHaveTextContent(/never payout-eligible/)
    expect(intro).toHaveTextContent(/Posting is final/)
    expect(screen.getByTestId('ht-file-name')).toHaveTextContent('No file chosen')
    expect(screen.getByRole('button', { name: 'Validate file' })).toBeDisabled()
    await userEvent.click(screen.getByTestId('ht-template'))
    await waitFor(() => expect(templateMock).toHaveBeenCalledWith('c1'))
  })

  it('validates a file and shows the reconciliation, multi-year totals and the unresolved attribution warning', async () => {
    previewMock.mockResolvedValue(batchOf())
    render(uploader)
    const file = new File(['x'], 'history.xlsx')
    await chooseAndValidate(file)
    await waitFor(() => expect(previewMock).toHaveBeenCalledWith('c1', file))

    expect(await screen.findByTestId('ht-ready')).toBeInTheDocument()
    expect(screen.getByTestId('ht-counts')).toHaveTextContent('12 rows, 12 valid, 0 error(s)')
    expect(screen.getByTestId('ht-computed')).toHaveTextContent(/12,?550/)
    expect(screen.getByTestId('ht-declared')).toHaveTextContent(/12,?550/)
    expect(screen.getByTestId('ht-balanced')).toHaveTextContent('Balanced')
    const years = screen.getByTestId('ht-by-year')
    expect(within(years).getByText('2023')).toBeInTheDocument()
    expect(within(years).getByText('2024')).toBeInTheDocument()
    expect(screen.getByTestId('ht-loans')).toHaveTextContent('1 of 1 loans fully repaid')
    expect(screen.getByTestId('ht-unresolved')).toHaveTextContent(/never payout-eligible and is not assigned to any recipient/)
    expect(screen.getByTestId('ht-warnings')).toHaveTextContent(/not listed on the Members sheet/)
    expect(screen.getByTestId('ht-status')).toHaveTextContent('Validated, awaiting approval')
  })

  it('reports per-row errors in words with their row numbers and never offers approval for an invalid file', async () => {
    previewMock.mockResolvedValue(invalid())
    rowsMock.mockResolvedValue([errorRow(2, 'UNSUPPORTED_ROW_TYPE'), errorRow(7, 'LOAN_NOT_CLOSED')])
    render(authority)
    await chooseAndValidate()

    expect(await screen.findByTestId('ht-has-errors')).toHaveTextContent('2 error(s) found')
    const table = await screen.findByTestId('ht-row-errors')
    await waitFor(() => expect(within(table).getByText(/cannot be imported/)).toBeInTheDocument())
    expect(within(table).getByText(/Transactions #2/)).toBeInTheDocument()
    expect(within(table).getByText(/Transactions #7/)).toBeInTheDocument()
    expect(within(table).getAllByText(/opening-balance loan migration/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByTestId('ht-sheet-issues')).toHaveTextContent(/required setting is missing/)
    expect(rowsMock).toHaveBeenCalledWith('c1', 'b2', true)
    expect(screen.getByRole('button', { name: 'Approve and post' })).toBeDisabled()
  })

  it('tells the user when the same file was already uploaded', async () => {
    previewMock.mockResolvedValue(batchOf({ reused: true }))
    render(uploader)
    await chooseAndValidate()
    expect(await screen.findByTestId('ht-reused')).toHaveTextContent('already uploaded')
  })

  it('lets only an independent fund authority approve, and posts a balanced history in one confirmed step', async () => {
    previewMock.mockResolvedValue(batchOf())
    approveMock.mockResolvedValue(batchOf({ status: 'POSTED' }))
    render(authority)
    await chooseAndValidate()
    await userEvent.click(await screen.findByRole('button', { name: 'Approve and post' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/All 12 rows will be posted together/)).toBeInTheDocument()
    expect(within(dialog).queryByTestId('ht-variance-alert')).not.toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve and post' }))
    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('c1', 'b1', { acknowledgeVariance: false, note: undefined }))
  })

  it('needs a written acknowledgement before posting a history that does not reconcile', async () => {
    const variance = batchOf({ balanceVariance: '450' })
    variance.summary!.reconciliation = { ...variance.summary!.reconciliation, variance: '450', balanced: false }
    previewMock.mockResolvedValue(variance)
    approveMock.mockResolvedValue(batchOf({ status: 'POSTED', varianceAcknowledged: true, varianceNote: 'Cash box short' }))
    render(authority)
    await chooseAndValidate()
    expect(await screen.findByTestId('ht-balanced')).toHaveTextContent('Does not reconcile')
    await userEvent.click(screen.getByRole('button', { name: 'Approve and post' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByTestId('ht-variance-alert')).toHaveTextContent(/does not reconcile/)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve and post' }))
    expect(approveMock).not.toHaveBeenCalled()
    expect(within(dialog).getByText(/Acknowledge the variance and explain it/)).toBeInTheDocument()

    await userEvent.click(within(dialog).getByLabelText('I acknowledge this variance'))
    await userEvent.type(within(dialog).getByLabelText(/Explain the variance/), 'The cash box was short by 450')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve and post' }))
    await waitFor(() =>
      expect(approveMock).toHaveBeenCalledWith('c1', 'b1', { acknowledgeVariance: true, note: 'The cash box was short by 450' }),
    )
  })

  it('never lets the uploader or an officer without fund authority approve, but allows cancelling before posting', async () => {
    previewMock.mockResolvedValue(batchOf())
    cancelMock.mockResolvedValue(batchOf({ status: 'CANCELLED' }))
    const own = render({ ...authority, id: 'uploader' })
    await chooseAndValidate()
    expect(await screen.findByTestId('ht-needs-another')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve and post' })).not.toBeInTheDocument()
    own.unmount()

    render(uploader)
    await chooseAndValidate()
    expect(await screen.findByTestId('ht-needs-authority')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve and post' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('ht-cancel'))
    await waitFor(() => expect(cancelMock).toHaveBeenCalledWith('c1', 'b1'))
  })

  it('shows a posted batch as final, with its acknowledged variance, and offers nothing more', async () => {
    const posted = batchOf({ status: 'POSTED', varianceAcknowledged: true, varianceNote: 'Cash box short', approvedBy: 'president' })
    listMock.mockResolvedValue([posted])
    render(authority)
    await userEvent.click(await screen.findByText('history.xlsx'))
    const notice = await screen.findByTestId('ht-posted')
    expect(notice).toHaveTextContent('approved and posted')
    expect(notice).toHaveTextContent('Cash box short')
    expect(screen.queryByRole('button', { name: 'Approve and post' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('ht-cancel')).not.toBeInTheDocument()
  })

  it('shows the real backend refusal of an approval', async () => {
    previewMock.mockResolvedValue(batchOf())
    approveMock.mockRejectedValue(backendError(422, 'HISTORICAL_IMPORT_STALE'))
    render(authority)
    await chooseAndValidate()
    await userEvent.click(await screen.findByRole('button', { name: 'Approve and post' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve and post' }))
    expect(await within(dialog).findByText(/The scheme changed since this file was validated/)).toBeInTheDocument()
  })

  it('lists the import history with status and error counts', async () => {
    listMock.mockResolvedValue([batchOf(), invalid()])
    render(uploader)
    expect(await screen.findByText('history.xlsx')).toBeInTheDocument()
    expect(screen.getByText('bad.xlsx')).toBeInTheDocument()
    expect(screen.getAllByText('Validated, awaiting approval')).toHaveLength(2)
  })
})
