import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  confirmCashReceived,
  extractReceipt,
  fetchReceiptExtractions,
  fetchVerificationOverview,
  recheckPayment,
  reviewReceiptExtraction,
} from '@/shared/api/paymentVerification'
import type { ReceiptExtraction, VerificationCheck, VerificationOverview } from '@/shared/types/paymentVerification'
import { backendError, paymentOf, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { ReceiptExtractionSection } from './ReceiptExtractionSection'
import { VerificationOutcomeSection } from './VerificationOutcomeSection'

vi.mock('@/shared/api/paymentVerification', () => ({
  fetchVerificationCapabilities: vi.fn().mockResolvedValue(null),
  fetchReceiptExtractions: vi.fn(),
  extractReceipt: vi.fn(),
  reviewReceiptExtraction: vi.fn(),
  fetchVerificationOverview: vi.fn(),
  recheckPayment: vi.fn(),
  confirmCashReceived: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const listMock = vi.mocked(fetchReceiptExtractions)
const extractMock = vi.mocked(extractReceipt)
const reviewMock = vi.mocked(reviewReceiptExtraction)
const overviewMock = vi.mocked(fetchVerificationOverview)
const recheckMock = vi.mocked(recheckPayment)
const cashMock = vi.mocked(confirmCashReceived)

const officer: TestUser = { id: 'officer', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_WRITE', 'LOAN_WRITE'] }
const payer: TestUser = { id: 'payer', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_WRITE'] }

const extractionOf = (extra: Partial<ReceiptExtraction> = {}): ReceiptExtraction => ({
  id: 'e1',
  paymentId: 'p1',
  status: 'COMPLETED',
  failureCode: null,
  engine: 'tesseract-cli',
  attempts: 1,
  meanConfidence: 0.62,
  fields: [
    { name: 'reference', value: 'TX123', confidence: 0.9, level: 'HIGH' },
    { name: 'amount', value: '3000.0000', confidence: 0.55, level: 'MEDIUM' },
    { name: 'recipient', value: '+250788123456', confidence: 0.3, level: 'LOW' },
  ],
  rawText: null,
  duplicateReceipt: false,
  duplicateOfPaymentId: null,
  discrepancies: [],
  proofOfPayment: false,
  reviewedBy: null,
  reviewedAt: null,
  reviewNote: null,
  createdAt: '2026-10-02T08:00:00Z',
  ...extra,
})

const checkOf = (extra: Partial<VerificationCheck> = {}): VerificationCheck => ({
  id: 'k1',
  paymentId: 'p1',
  outcome: 'CONFIRMED',
  method: 'MOBILE_MONEY_RECONCILED',
  source: 'MOBILE_MONEY_STATEMENT_IMPORT',
  authoritative: true,
  trustedTransactionId: 't1',
  reasons: [],
  detail: null,
  checkedBy: null,
  createdAt: '2026-10-02T09:00:00Z',
  ...extra,
})

const overviewOf = (extra: Partial<VerificationOverview> = {}): VerificationOverview => ({
  requiresTrustedConfirmation: false,
  automaticProviderConfirmation: 'UNAVAILABLE',
  current: checkOf(),
  history: [checkOf()],
  ...extra,
})

describe('receipt reading section', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows nothing for a payment without an uploaded receipt and never calls the API', () => {
    renderWithApp(<ReceiptExtractionSection cooperativeId="c1" payment={paymentOf({ evidenceFileKey: null })} />, { user: officer })
    expect(screen.queryByTestId('receipt-extraction')).not.toBeInTheDocument()
    expect(listMock).not.toHaveBeenCalled()
  })

  it('always states that a reading is not proof and reads the receipt on request', async () => {
    listMock.mockResolvedValueOnce([]).mockResolvedValue([extractionOf()])
    extractMock.mockResolvedValue(extractionOf())
    renderWithApp(<ReceiptExtractionSection cooperativeId="c1" payment={paymentOf({ evidenceFileKey: 'k' })} />, { user: officer })

    expect(await screen.findByTestId('receipt-not-proof')).toHaveTextContent(/not proof of payment/i)
    await userEvent.click(await screen.findByRole('button', { name: 'Read the receipt' }))
    expect(extractMock).toHaveBeenCalledWith('c1', 'p1')
    expect(await screen.findByTestId('receipt-fields')).toBeInTheDocument()
    expect(screen.getByTestId('receipt-not-proof')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Read again' })).toBeInTheDocument()
  })

  it('labels every reading with its confidence and lists the differences from the claim', async () => {
    listMock.mockResolvedValue([
      extractionOf({
        discrepancies: ['AMOUNT_MISMATCH', 'DUPLICATE_RECEIPT'],
        duplicateReceipt: true,
        duplicateOfPaymentId: 'other',
      }),
    ])
    renderWithApp(<ReceiptExtractionSection cooperativeId="c1" payment={paymentOf({ evidenceFileKey: 'k' })} />, { user: officer })

    const fields = await screen.findByTestId('receipt-fields')
    expect(within(fields).getByText('High 90%')).toBeInTheDocument()
    expect(within(fields).getByText('Medium 55%')).toBeInTheDocument()
    expect(within(fields).getByText('Low 30%')).toBeInTheDocument()
    expect(screen.getByTestId('receipt-mean-confidence')).toHaveTextContent('Overall confidence 62%')
    const chips = screen.getByTestId('receipt-discrepancies')
    expect(within(chips).getByText('Amount differs from the claim')).toBeInTheDocument()
    expect(within(chips).getByText('Receipt already used')).toBeInTheDocument()
    expect(screen.getByTestId('receipt-duplicate')).toBeInTheDocument()
  })

  it('reports an unavailable engine honestly and shows no invented fields', async () => {
    listMock.mockResolvedValue([
      extractionOf({ status: 'UNAVAILABLE', failureCode: 'OCR_ENGINE_UNAVAILABLE', fields: [], meanConfidence: null, engine: null }),
    ])
    renderWithApp(<ReceiptExtractionSection cooperativeId="c1" payment={paymentOf({ evidenceFileKey: 'k' })} />, { user: officer })

    expect(await screen.findByTestId('receipt-failure')).toHaveTextContent(/No text-reading engine is installed/)
    expect(screen.queryByTestId('receipt-fields')).not.toBeInTheDocument()
    expect(screen.queryByTestId('receipt-discrepancies')).not.toBeInTheDocument()
  })

  it('lets an officer record a review but never the payer, and shows a refusal in words', async () => {
    listMock.mockResolvedValue([extractionOf()])
    reviewMock.mockRejectedValue(backendError(403, null))
    const payment = paymentOf({ evidenceFileKey: 'k', memberUserId: 'payer' })
    const view = renderWithApp(<ReceiptExtractionSection cooperativeId="c1" payment={payment} />, { user: payer })
    await screen.findByTestId('receipt-fields')
    expect(screen.queryByRole('button', { name: 'Mark as reviewed' })).not.toBeInTheDocument()
    view.unmount()

    renderWithApp(<ReceiptExtractionSection cooperativeId="c1" payment={payment} />, { user: officer })
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as reviewed' }))
    await waitFor(() => expect(reviewMock).toHaveBeenCalledWith('c1', 'p1', 'e1'))
    expect(await screen.findByTestId('payment-error')).toBeInTheDocument()
  })
})

describe('verification outcome section', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the evidence outcome beside, not instead of, the payment status', async () => {
    overviewMock.mockResolvedValue(overviewOf())
    renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={paymentOf({ status: 'UNDER_REVIEW' })} />, { user: officer })

    const current = await screen.findByTestId('verification-current')
    expect(within(current).getByText('Confirmed')).toBeInTheDocument()
    expect(within(current).getByText('Mobile-money transaction reconciled')).toBeInTheDocument()
    expect(within(current).getByText(/Mobile-money statement line · authoritative evidence/)).toBeInTheDocument()
    expect(screen.getByTestId('verification-payment-status')).toHaveTextContent('Under review')
    expect(screen.getByTestId('verification-current-outcome')).toHaveTextContent('Confirmed')
    expect(screen.getByText(/does not verify or post the payment/i)).toBeInTheDocument()
  })

  it('blocks and explains conflicting evidence with its reasons', async () => {
    overviewMock.mockResolvedValue(
      overviewOf({
        current: checkOf({ outcome: 'CONFLICTED', reasons: ['AMOUNT_MISMATCH', 'WRONG_RECIPIENT'] }),
      }),
    )
    renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={paymentOf({ status: 'UNDER_REVIEW' })} />, { user: officer })

    expect(await screen.findByTestId('verification-blocked')).toBeInTheDocument()
    const reasons = screen.getByTestId('verification-reasons')
    expect(within(reasons).getByText('Amount does not match')).toBeInTheDocument()
    expect(within(reasons).getByText('Receipt names a different recipient')).toBeInTheDocument()
    expect(screen.getByTestId('verification-current-outcome')).toHaveTextContent('Conflicting evidence')
  })

  it('says automatic provider confirmation is unavailable unless a signed callback is configured', async () => {
    overviewMock.mockResolvedValue(overviewOf({ current: null, history: [] }))
    const view = renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={paymentOf()} />, { user: officer })
    expect(await screen.findByTestId('provider-availability')).toHaveTextContent(/Automatic provider confirmation is not available/)
    expect(screen.getByTestId('verification-current-outcome')).toHaveTextContent('Not checked yet')
    view.unmount()

    overviewMock.mockResolvedValue(overviewOf({ automaticProviderConfirmation: 'CALLBACK_CONFIGURED', requiresTrustedConfirmation: true }))
    renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={paymentOf()} />, { user: officer })
    expect(await screen.findByTestId('provider-availability')).toHaveTextContent(/Signed provider confirmations are accepted/)
    expect(screen.getByTestId('verification-strict')).toBeInTheDocument()
  })

  it('re-checks on request and shows a backend refusal in words', async () => {
    overviewMock.mockResolvedValue(overviewOf({ current: null, history: [] }))
    recheckMock.mockRejectedValue(backendError(403, null))
    renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={paymentOf()} />, { user: officer })
    await userEvent.click(await screen.findByTestId('verification-recheck'))
    await waitFor(() => expect(recheckMock).toHaveBeenCalledWith('c1', 'p1'))
    expect(await screen.findByTestId('payment-error')).toBeInTheDocument()
  })

  it('confirms cash only through an officer who is not the payer, with a recorded note', async () => {
    overviewMock.mockResolvedValue(overviewOf({ current: checkOf({ outcome: 'PENDING', method: 'CASH_RECEIPT_CONFIRMED', source: 'REFERENCE_MATCH', authoritative: false, reasons: ['AWAITING_CASH_CONFIRMATION'], trustedTransactionId: null }) }))
    cashMock.mockResolvedValue(checkOf({ outcome: 'CONFIRMED', method: 'CASH_RECEIPT_CONFIRMED', source: 'MANUAL_CASH_CONFIRMATION', trustedTransactionId: null }))
    const cash = paymentOf({ paymentMethod: 'CASH', transactionReference: null, status: 'UNDER_REVIEW', memberUserId: 'payer', submittedBy: 'payer' })

    // the payer-officer is told someone else must confirm, and gets no button
    const own = renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={cash} />, { user: payer })
    expect(await screen.findByTestId('verification-cash-not-allowed')).toBeInTheDocument()
    expect(screen.queryByTestId('verification-confirm-cash')).not.toBeInTheDocument()
    own.unmount()

    renderWithApp(<VerificationOutcomeSection cooperativeId="c1" payment={cash} />, { user: officer })
    await userEvent.click(await screen.findByTestId('verification-confirm-cash'))
    const dialog = await screen.findByRole('dialog')
    // a note and an explicit confirmation are both required
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm cash received' }))
    expect(cashMock).not.toHaveBeenCalled()
    await userEvent.type(within(dialog).getByLabelText(/How did you receive the cash/), 'Counted 3500 at the office')
    await userEvent.click(within(dialog).getByLabelText('I received this cash'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm cash received' }))
    await waitFor(() => expect(cashMock).toHaveBeenCalledWith('c1', 'p1', 'Counted 3500 at the office'))
  })
})
