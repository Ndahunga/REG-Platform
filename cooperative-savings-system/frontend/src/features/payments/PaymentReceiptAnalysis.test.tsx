import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMyContributions } from '@/shared/api/contributions'
import { uploadCooperativeFile } from '@/shared/api/files'
import { fetchMyLoans } from '@/shared/api/loans'
import { analyzeReceipt, fetchCredits, fetchPaymentAccounts, submitPayment } from '@/shared/api/memberPayments'
import type { ReceiptAnalysis } from '@/shared/types/memberPayment'
import { backendError, pageOf, paymentOf, renderWithApp } from '@/test/paymentTestHarness'
import { PaymentSubmitPanel } from './PaymentSubmitPanel'
import { receiptReviewReasons } from './ReceiptAnalysisNotice'

vi.mock('@/shared/api/contributions', () => ({ fetchMyContributions: vi.fn() }))
vi.mock('@/shared/api/loans', () => ({ fetchMyLoans: vi.fn() }))
vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))
vi.mock('@/shared/api/memberPayments', () => ({
  submitPayment: vi.fn(),
  analyzeReceipt: vi.fn(),
  fetchPaymentAccounts: vi.fn(),
  fetchCredits: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const MEMBER = { id: 'payer', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }

const analysisOf = (extra: Partial<ReceiptAnalysis> = {}): ReceiptAnalysis => ({
  analysisId: 'a1',
  reading: 'COMPLETED',
  receiptStatus: 'SUCCESS_INDICATED',
  recommendedAction: 'CONTINUE',
  canSubmit: true,
  manualReviewNeeded: false,
  reviewReasons: [],
  extractedAmount: '1500',
  currency: 'RWF',
  extractedReference: 'TX9988776655',
  referenceAmbiguous: false,
  referenceAlreadyUsed: false,
  extractedDate: '2026-09-12',
  apparentSender: null,
  apparentRecipient: '*******456',
  recipientMatch: 'UNKNOWN',
  confidence: '0.9',
  confidenceLevel: 'HIGH',
  amountAmbiguous: false,
  amountCandidates: [],
  ...extra,
})

function renderPanel() {
  return renderWithApp(<PaymentSubmitPanel cooperativeId="c1" />, { user: MEMBER })
}

async function uploadReceipt(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText('Add contribution')
  await user.upload(screen.getByLabelText('Upload receipt'), new File(['x'], 'receipt.png', { type: 'image/png' }))
}

async function fillContribution(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('combobox', { name: 'Contribution period' }))
  await user.click(await screen.findByRole('option', { name: /2026-09/ }))
  await user.click(screen.getByRole('button', { name: 'Add contribution' }))
}

const submitButton = () => screen.getByRole('button', { name: 'Submit payment for verification' })

describe('member receipt analysis before submission', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMyContributions).mockResolvedValue(
      pageOf([
        { id: 'c9', memberUserId: 'payer', year: 2026, month: 9, expectedAmount: '2000', paidAmount: '500', outstandingAmount: '1500', status: 'PARTIALLY_PAID' },
      ]) as never,
    )
    vi.mocked(fetchMyLoans).mockResolvedValue(pageOf([]) as never)
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([])
    vi.mocked(fetchCredits).mockResolvedValue({ balances: [], totalHeld: '0' })
    vi.mocked(uploadCooperativeFile).mockResolvedValue({ storageKey: 'k1', originalFilename: 'receipt.png' } as never)
    vi.mocked(submitPayment).mockResolvedValue(paymentOf())
  })

  it('a failed receipt blocks the submission with a friendly message and never shows the technical status', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(
      analysisOf({ receiptStatus: 'FAILED_INDICATED', recommendedAction: 'REPLACE_RECEIPT', canSubmit: false, extractedAmount: null }),
    )
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)

    const notice = await screen.findByTestId('receipt-analysis-failed')
    expect(notice).toHaveTextContent(/indicates that the transaction failed/)
    expect(notice).toHaveTextContent(/make a successful payment to the Ikimina's provided account/)
    expect(document.body.textContent).not.toMatch(/FAILED_INDICATED|SUCCESS_INDICATED|PENDING_INDICATED/)

    await fillContribution(user)
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    expect(submitButton()).toBeDisabled()
    expect(submitPayment).not.toHaveBeenCalled()
  })

  it('a pending receipt blocks the submission and asks for the final confirmation', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(
      analysisOf({ receiptStatus: 'PENDING_INDICATED', recommendedAction: 'WAIT_FOR_FINAL_RECEIPT', canSubmit: false }),
    )
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    expect(await screen.findByTestId('receipt-analysis-pending')).toHaveTextContent(/still processing/)
    expect(screen.getByTestId('receipt-analysis-pending')).toHaveTextContent(/upload the final confirmation/)
    expect(submitButton()).toBeDisabled()
  })

  it('an unclear receipt needs an explicit manual-review request before the payment can be submitted', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(
      analysisOf({
        receiptStatus: 'UNKNOWN',
        recommendedAction: 'REPLACE_OR_REQUEST_REVIEW',
        manualReviewNeeded: true,
        reviewReasons: ['RECEIPT_UNCLEAR'],
      }),
    )
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    expect(await screen.findByTestId('receipt-analysis-review')).toHaveTextContent(/does not clearly say the payment was successful/)
    await fillContribution(user)
    expect(screen.getByRole('textbox', { name: /Total amount paid/ })).toHaveValue('1,500') // filled from the receipt
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX9988776655')
    expect(submitButton()).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: 'Submit this payment for manual review' }))
    expect(submitButton()).toBeEnabled()
    await user.click(submitButton())
    await waitFor(() => expect(submitPayment).toHaveBeenCalledTimes(1))
    expect(vi.mocked(submitPayment).mock.calls[0][1]).toMatchObject({ evidenceFileKey: 'k1', manualReview: true })
  })

  it('a successful receipt lets the member continue, fills in the amount, and the payment waits for verification', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(analysisOf())
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)

    expect(await screen.findByTestId('receipt-analysis-success')).toHaveTextContent(/An officer still has to verify the payment/)
    expect(screen.getByTestId('receipt-analysis-details')).toHaveTextContent('Reference on the receipt: TX9988776655')
    expect(screen.getByTestId('receipt-analysis-details')).toHaveTextContent('Date on the receipt: 2026-09-12')
    expect(screen.getByRole('textbox', { name: /Total amount paid/ })).toHaveValue('1,500')
    expect(screen.queryByRole('checkbox', { name: 'Submit this payment for manual review' })).not.toBeInTheDocument()

    await fillContribution(user)
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX9988776655')
    await user.click(submitButton())
    await waitFor(() => expect(submitPayment).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(submitPayment).mock.calls[0][1]
    expect(payload.manualReview).toBeUndefined()
    // submitting records a claim: nothing says the payment succeeded or that a balance changed
    expect(await screen.findByTestId('payment-accepted')).toBeInTheDocument()
    expect(screen.queryByText(/payment successful/i)).not.toBeInTheDocument()
  })

  it('competing amounts are offered as choices and nothing is prefilled', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(
      analysisOf({
        amountAmbiguous: true,
        extractedAmount: null,
        amountCandidates: ['80000', '75000'],
        manualReviewNeeded: true,
        recommendedAction: 'REPLACE_OR_REQUEST_REVIEW',
        reviewReasons: ['AMOUNT_AMBIGUOUS'],
      }),
    )
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    const candidates = await screen.findByTestId('receipt-analysis-candidates')
    expect(screen.getByRole('textbox', { name: /Total amount paid/ })).toHaveValue('')
    expect(candidates).toHaveTextContent(/more than one possible amount/)
    await user.click(screen.getByText(/80,000/))
    expect(screen.getByRole('textbox', { name: /Total amount paid/ })).toHaveValue('80,000')
  })

  it('warns about a different recipient account and a reference already used', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(
      analysisOf({
        recipientMatch: 'MISMATCH',
        referenceAlreadyUsed: true,
        manualReviewNeeded: true,
        recommendedAction: 'REPLACE_OR_REQUEST_REVIEW',
        reviewReasons: ['RECIPIENT_MISMATCH'],
      }),
    )
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    expect(await screen.findByTestId('receipt-analysis-recipient-mismatch')).toHaveTextContent(/different account/)
    expect(screen.getByTestId('receipt-analysis-reference-used')).toHaveTextContent(/already used/)
  })

  it('an amount that differs from the receipt needs a review, and agreeing with it removes the need', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(analysisOf({ extractedAmount: '1500' }))
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    await screen.findByTestId('receipt-analysis-success')
    const total = screen.getByRole('textbox', { name: /Total amount paid/ })
    await user.clear(total)
    await user.type(total, '1000')
    expect(await screen.findByTestId('receipt-analysis-review')).toHaveTextContent(/different from the amount on the receipt/)
    await user.clear(total)
    await user.type(total, '1500')
    await waitFor(() => expect(screen.queryByTestId('receipt-analysis-review')).not.toBeInTheDocument())
  })

  it('if the receipt cannot be checked right now the member is not blocked and the server decides', async () => {
    vi.mocked(analyzeReceipt).mockRejectedValue(backendError(503, 'SERVICE_UNAVAILABLE'))
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    expect(await screen.findByTestId('receipt-analysis-unavailable-check')).toBeInTheDocument()
    await fillContribution(user)
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    expect(submitButton()).toBeEnabled()
  })

  it('when the server still refuses the receipt at submission the member sees the friendly message', async () => {
    vi.mocked(analyzeReceipt).mockResolvedValue(analysisOf());
    vi.mocked(submitPayment).mockRejectedValue(backendError(422, 'RECEIPT_INDICATES_FAILURE'))
    renderPanel()
    const user = userEvent.setup()
    await uploadReceipt(user)
    await screen.findByTestId('receipt-analysis-success')
    await fillContribution(user)
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX9988776655')
    await user.click(submitButton())
    expect(await screen.findByTestId('payment-error')).toHaveTextContent(/indicates that the transaction failed/)
  })

  it('shows where members should pay, masked, when the scheme has registered accounts', async () => {
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([
      { id: 'p1', channel: 'MTN_MOMO', accountIdentifier: '*******111', masked: true, label: 'Ikimina MoMo', active: true, createdAt: '2026-01-01T00:00:00Z' },
    ])
    renderPanel()
    expect(await screen.findByTestId('payment-pay-to')).toHaveTextContent(/\*{7}111/)
    expect(screen.getByTestId('payment-pay-to')).toHaveTextContent('Ikimina MoMo')
  })
})

describe('receiptReviewReasons', () => {
  it('recomputes the amount and reference comparisons from what is typed now', () => {
    const analysis = analysisOf({ reviewReasons: ['AMOUNT_MISMATCH'] })
    expect(receiptReviewReasons(analysis, { amount: '1500', reference: 'tx-9988776655' })).toEqual([])
    expect(receiptReviewReasons(analysis, { amount: '900', reference: 'TX9988776655' })).toEqual(['AMOUNT_MISMATCH'])
    expect(receiptReviewReasons(analysis, { amount: '1500', reference: 'OTHER123456' })).toEqual(['REFERENCE_MISMATCH'])
    expect(receiptReviewReasons(null, { amount: '1', reference: 'x' })).toEqual([])
    expect(receiptReviewReasons(analysisOf({ canSubmit: false }), { amount: '1', reference: 'x' })).toEqual([])
  })
})
