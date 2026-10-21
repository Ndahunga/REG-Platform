import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  completePaymentEvidence,
  fetchPayment,
  fetchPaymentEvents,
  fetchPaymentPosting,
  postPayment,
  rejectPayment,
  fetchPaymentReversals,
  retryPaymentPosting,
  startPaymentReview,
  verifyPayment,
} from '@/shared/api/memberPayments'
import { backendError, networkError, paymentOf, postingOf, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { PaymentDetailDialog } from './PaymentDetailDialog'

// the evidence sections load their own data; these tests are about the payment itself, so they get an empty answer
vi.mock('@/shared/api/paymentVerification', () => ({
  fetchVerificationCapabilities: vi.fn().mockResolvedValue(null),
  fetchVerificationOverview: vi.fn().mockResolvedValue({
    requiresTrustedConfirmation: false,
    automaticProviderConfirmation: 'UNAVAILABLE',
    current: null,
    history: [],
  }),
  fetchReceiptExtractions: vi.fn().mockResolvedValue([]),
  extractReceipt: vi.fn(),
  reviewReceiptExtraction: vi.fn(),
  recheckPayment: vi.fn(),
  confirmCashReceived: vi.fn(),
}))
vi.mock('@/shared/api/memberPayments', () => ({
  fetchPayment: vi.fn(),
  fetchPaymentEvents: vi.fn(),
  fetchPaymentPosting: vi.fn(),
  startPaymentReview: vi.fn(),
  verifyPayment: vi.fn(),
  completePaymentEvidence: vi.fn(),
  rejectPayment: vi.fn(),
  postPayment: vi.fn(),
  retryPaymentPosting: vi.fn(),
  fetchPaymentReversals: vi.fn(),
  requestPaymentReversal: vi.fn(),
  approvePaymentReversal: vi.fn(),
  declinePaymentReversal: vi.fn(),
  withdrawPaymentReversal: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const paymentMock = vi.mocked(fetchPayment)
const eventsMock = vi.mocked(fetchPaymentEvents)
const postingMock = vi.mocked(fetchPaymentPosting)
const reviewMock = vi.mocked(startPaymentReview)
const verifyMock = vi.mocked(verifyPayment)
const evidenceMock = vi.mocked(completePaymentEvidence)
const rejectMock = vi.mocked(rejectPayment)
const postMock = vi.mocked(postPayment)
const retryMock = vi.mocked(retryPaymentPosting)

const officer = (id = 'officer'): TestUser => ({
  id,
  roles: ['ACCOUNTANT'],
  permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE', 'LOAN_WRITE'],
})

function renderDialog(user: TestUser = officer()) {
  return renderWithApp(<PaymentDetailDialog cooperativeId="c1" paymentId="p1" staff onClose={() => {}} />, { user })
}

const verifiedPayment = (extra: Record<string, unknown> = {}) =>
  paymentOf({ status: 'VERIFIED', verifiedBy: 'verifier', verifiedAt: '2026-10-02T08:00:00Z', ...extra } as never)

describe('officer payment review, verification and posting', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchPaymentReversals).mockResolvedValue([])
    paymentMock.mockResolvedValue(paymentOf())
    eventsMock.mockResolvedValue([{ id: 'e1', toStatus: 'SUBMITTED', actorRole: 'Member', createdAt: '2026-10-01T08:00:00Z' }])
    postingMock.mockResolvedValue(postingOf({ status: 'SUBMITTED', verificationEvidenceRecorded: false, verificationMethod: null }))
  })

  // ------------------------------------------------------------ review and verification

  it('starts the review of a submitted payment and refreshes from the backend', async () => {
    reviewMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW' }))
    renderDialog()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Start review' }))
    await waitFor(() => expect(reviewMock).toHaveBeenCalledWith('c1', 'p1'))
    await waitFor(() => expect(paymentMock.mock.calls.length).toBeGreaterThan(1))
  })

  it('requires method, written basis, reference and an explicit confirmation before verifying', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW' }))
    verifyMock.mockResolvedValue(verifiedPayment())
    renderDialog()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Verify funds received' }))
    const dialog = await screen.findByRole('dialog', { name: 'Verify funds received' })

    // only methods that fit an MTN payment are offered; OCR or an uploaded image is not a method
    await user.click(within(dialog).getByRole('combobox', { name: 'How the funds were confirmed' }))
    expect(await screen.findByRole('option', { name: 'Mobile-money transaction reconciled' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Payment provider confirmation' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Cash receipt confirmed' })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /ocr|receipt image|upload/i })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'Mobile-money transaction reconciled' }))

    await user.click(within(dialog).getByRole('button', { name: 'Verify payment' }))
    expect(await within(dialog).findByText('Describe what you relied on to confirm the funds.')).toBeInTheDocument()
    expect(within(dialog).getByText('A reference is required for this confirmation method.')).toBeInTheDocument()
    expect(within(dialog).getByText('Confirm that you checked the funds before continuing.')).toBeInTheDocument()
    expect(verifyMock).not.toHaveBeenCalled()

    await user.type(within(dialog).getByRole('textbox', { name: /Confirmation basis/ }), 'Matched the MoMo statement line')
    await user.type(within(dialog).getByRole('textbox', { name: /Reference or reconciliation identifier/ }), 'MP-998')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Verify payment' }))
    await waitFor(() => expect(verifyMock).toHaveBeenCalledTimes(1))
    expect(verifyMock).toHaveBeenCalledWith('c1', 'p1', {
      method: 'MOBILE_MONEY_RECONCILED',
      basis: 'Matched the MoMo statement line',
      reference: 'MP-998',
    })
    // verification never triggers posting
    expect(postMock).not.toHaveBeenCalled()
    expect(retryMock).not.toHaveBeenCalled()
  })

  it('does not require a reference for a cash confirmation', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW', paymentMethod: 'CASH', transactionReference: null }))
    verifyMock.mockResolvedValue(verifiedPayment({ paymentMethod: 'CASH' }))
    renderDialog()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Verify funds received' }))
    const dialog = await screen.findByRole('dialog', { name: 'Verify funds received' })
    await user.type(within(dialog).getByRole('textbox', { name: /Confirmation basis/ }), 'Counted and receipted by the treasurer')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Verify payment' }))
    await waitFor(() => expect(verifyMock).toHaveBeenCalledTimes(1))
    expect(verifyMock.mock.calls[0][2]).toEqual({
      method: 'CASH_RECEIPT_CONFIRMED',
      basis: 'Counted and receipted by the treasurer',
      reference: undefined,
    })
  })

  it('shows the backend reason when verification is refused', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW' }))
    verifyMock.mockRejectedValue(backendError(409, 'VERIFICATION_EVIDENCE_EXISTS'))
    renderDialog()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Verify funds received' }))
    const dialog = await screen.findByRole('dialog', { name: 'Verify funds received' })
    await user.type(within(dialog).getByRole('textbox', { name: /Confirmation basis/ }), 'x')
    await user.type(within(dialog).getByRole('textbox', { name: /Reference or reconciliation identifier/ }), 'y')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Verify payment' }))
    expect(await within(dialog).findByTestId('payment-error')).toHaveTextContent(/already has different verification evidence/)
  })

  it('rejects a payment only with a reason', async () => {
    rejectMock.mockResolvedValue(paymentOf({ status: 'REJECTED' }))
    renderDialog()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Reject payment' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject payment' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject payment' }))
    expect(await within(dialog).findByText('Give a reason for rejecting this payment.')).toBeInTheDocument()
    expect(rejectMock).not.toHaveBeenCalled()
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Reference not found in the statement')
    await user.click(within(dialog).getByRole('button', { name: 'Reject payment' }))
    await waitFor(() => expect(rejectMock).toHaveBeenCalledWith('c1', 'p1', 'Reference not found in the statement'))
  })

  // ------------------------------------------------------------ separation of duties and permissions

  it('hides every action from the payer and the submitter and explains why', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW', submittedBy: 'someone' }))
    renderDialog({ ...officer('payer') })
    expect(await screen.findByTestId('payment-duty-note')).toHaveTextContent(/payer of this payment/)
    expect(screen.queryByRole('button', { name: 'Verify funds received' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject payment' })).not.toBeInTheDocument()
  })

  it('hides verification from the person who submitted the payment', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW', submittedBy: 'clerk' }))
    renderDialog(officer('clerk'))
    expect(await screen.findByTestId('payment-duty-note')).toHaveTextContent(/You submitted this payment/)
    expect(screen.queryByRole('button', { name: 'Verify funds received' })).not.toBeInTheDocument()
  })

  it('shows no actions when the officer lacks the write authority of an allocation type', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW' }))
    renderDialog({ id: 'officer', roles: ['SECRETARY'], permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE'] })
    expect(await screen.findByText(/do not have the permissions needed to handle this payment/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Verify funds received' })).not.toBeInTheDocument()
  })

  it('does not offer posting to the verifier or to the evidence recorder', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf({ evidenceCompletedBy: 'recorder' }))
    const { unmount } = renderDialog(officer('verifier'))
    expect(await screen.findByTestId('payment-duty-note')).toHaveTextContent(/You verified this payment, so a different officer must post it/)
    expect(screen.queryByRole('button', { name: 'Post to accounts' })).not.toBeInTheDocument()
    unmount()

    renderDialog(officer('recorder'))
    expect(await screen.findByTestId('payment-duty-note')).toHaveTextContent(/recorded this payment’s verification evidence/)
    expect(screen.queryByRole('button', { name: 'Post to accounts' })).not.toBeInTheDocument()
  })

  // ------------------------------------------------------------ verified but unposted

  it('shows a verified payment as confirmed funds that are not yet posted', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf())
    renderDialog(officer('poster'))
    expect(await screen.findByTestId('payment-status')).toHaveTextContent('Verified, not yet posted')
    expect(screen.getByTestId('payment-status-notice')).toHaveTextContent(/funds have been confirmed, but the allocations may not yet be posted/)
    expect(screen.queryByTestId('payment-posted-at')).not.toBeInTheDocument()
    expect(await screen.findByTestId('payment-evidence-state')).toHaveTextContent(/Funds confirmed by: Payment provider confirmation/)
    expect(screen.getByRole('button', { name: 'Post to accounts' })).toBeInTheDocument()
  })

  it('holds a verified payment without evidence: evidence first, no posting', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf({ verificationEvidenceRecorded: false, verificationMethod: null }))
    evidenceMock.mockResolvedValue(verifiedPayment())
    renderDialog(officer('poster'))
    expect(await screen.findByTestId('payment-evidence-state')).toHaveTextContent(/no recorded confirmation of the funds/)
    expect(screen.queryByRole('button', { name: 'Post to accounts' })).not.toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Record verification evidence' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record verification evidence' })
    await user.type(within(dialog).getByRole('textbox', { name: /Confirmation basis/ }), 'Checked the provider portal')
    await user.type(within(dialog).getByRole('textbox', { name: /Reference or reconciliation identifier/ }), 'PRV-7')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Save evidence' }))
    await waitFor(() => expect(evidenceMock).toHaveBeenCalledWith('c1', 'p1', expect.objectContaining({ reference: 'PRV-7' })))
    expect(postMock).not.toHaveBeenCalled()
  })

  // ------------------------------------------------------------ posting

  it('posts only after an explicit confirmation, once, and refreshes the payment from the backend', async () => {
    paymentMock.mockResolvedValueOnce(verifiedPayment())
    postingMock.mockResolvedValueOnce(postingOf())
    let resolvePost: (value: unknown) => void = () => {}
    postMock.mockReturnValue(new Promise((resolve) => (resolvePost = resolve)) as never)
    renderDialog(officer('poster'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Post to accounts' }))
    const dialog = await screen.findByRole('dialog', { name: 'Post payment to accounts' })
    expect(within(dialog).getByText(/applies every allocation of this payment/)).toBeInTheDocument()
    expect(within(dialog).getByTestId('payment-allocations')).toHaveTextContent(/Contribution 2026-09/)
    expect(within(dialog).getByTestId('payment-allocations')).toHaveTextContent(/Loan loan-aaa/)

    await user.click(within(dialog).getByRole('button', { name: 'Post payment' }))
    expect(await within(dialog).findByText('Confirm before posting.')).toBeInTheDocument()
    expect(postMock).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Post payment' }))
    const pending = await within(dialog).findByRole('button', { name: 'Posting…' })
    expect(pending).toBeDisabled()
    await user.click(pending)
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(postMock).toHaveBeenCalledWith('c1', 'p1')

    // the backend now reports POSTED; nothing is shown as posted before that
    paymentMock.mockResolvedValue(
      verifiedPayment({ status: 'POSTED', postedAt: '2026-10-03T08:00:00Z', postedBy: 'poster' }),
    )
    postingMock.mockResolvedValue(
      postingOf({
        status: 'POSTED',
        postings: [
          {
            allocationId: 'a2',
            type: 'LOAN_REPAYMENT',
            amount: '1500',
            attribution: 'NOT_APPLICABLE',
            loanId: 'loan-aaaa-1111',
            principalAmount: '1000',
            interestAmount: '400',
            penaltyAmount: '100',
          },
          {
            allocationId: 'a1',
            type: 'CONTRIBUTION',
            amount: '2000',
            periodYear: 2026,
            periodMonth: 9,
            attribution: 'ATTRIBUTED',
            rotatingBps: 7000,
            rotatingAmount: '1400',
            retainedAmount: '600',
          },
        ],
      }),
    )
    resolvePost({})
    expect(await screen.findByTestId('payment-posted-at')).toBeInTheDocument()
    expect(screen.getByTestId('payment-status')).toHaveTextContent('Posted')
    const results = screen.getByTestId('posting-results')
    expect(results).toHaveTextContent(/calculated by the loan system/)
    expect(results).toHaveTextContent(/rotating .*1[\s,]*400.* \(70%\), retained .*600/)
  })

  it('explains a posting failure with the real backend code and offers no automatic retry', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf())
    postMock.mockRejectedValue(backendError(422, 'OBLIGATION_NOT_FOUND'))
    renderDialog(officer('poster'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Post to accounts' }))
    const dialog = await screen.findByRole('dialog', { name: 'Post payment to accounts' })
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Post payment' }))
    expect(await within(dialog).findByTestId('payment-error')).toHaveTextContent(/has no recorded obligation.*never created from today’s settings/)
    // no automatic retry and no repost button inside the failed dialog
    expect(within(dialog).queryByRole('button', { name: /post payment|retry/i })).not.toBeInTheDocument()
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(retryMock).not.toHaveBeenCalled()
  })

  it('offers a manual retry only when the backend says the failed payment is retryable, after the officer confirms', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(
      postingOf({ postingAttempts: 1, retryable: true, lastPostingFailureCode: 'ALLOCATION_EXCEEDS_OUTSTANDING' }),
    )
    retryMock.mockResolvedValue(postingOf({ status: 'POSTED' }))
    renderDialog(officer('poster'))
    expect(await screen.findByTestId('posting-failure')).toHaveTextContent(/more than what is still outstanding/)
    expect(screen.getByTestId('posting-failure')).toHaveTextContent(/failed: 1/)
    expect(screen.queryByRole('button', { name: 'Post to accounts' })).not.toBeInTheDocument()
    expect(retryMock).not.toHaveBeenCalled()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Retry posting' }))
    const dialog = await screen.findByRole('dialog', { name: 'Retry posting' })
    await user.click(within(dialog).getByRole('button', { name: 'Retry posting' }))
    expect(await within(dialog).findByText('Confirm before posting.')).toBeInTheDocument()
    expect(retryMock).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Retry posting' }))
    await waitFor(() => expect(retryMock).toHaveBeenCalledTimes(1))
    expect(postMock).not.toHaveBeenCalled()
  })

  it('never offers a workaround for unresolved rotation attribution', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(
      postingOf({ postingAttempts: 2, retryable: true, lastPostingFailureCode: 'ROTATION_ATTRIBUTION_UNRESOLVED' }),
    )
    renderDialog(officer('poster'))
    const failure = await screen.findByTestId('payment-error-unresolved-attribution')
    expect(failure).toHaveTextContent(
      'The original contribution period’s rotation allocation or scheduled recipient cannot be established. The payment remains verified but has not been financially posted.',
    )
    expect(screen.getByTestId('payment-status')).toHaveTextContent('Verified, not yet posted')
    for (const forbidden of [/current recipient/i, /assign/i, /guess/i, /ignore/i, /force/i, /override/i, /skip/i]) {
      expect(screen.queryByRole('button', { name: forbidden })).not.toBeInTheDocument()
    }
  })

  it('shows the same explanation when posting returns the unresolved attribution error', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf())
    postMock.mockRejectedValue(backendError(422, 'ROTATION_ATTRIBUTION_UNRESOLVED'))
    renderDialog(officer('poster'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Post to accounts' }))
    const dialog = await screen.findByRole('dialog', { name: 'Post payment to accounts' })
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Post payment' }))
    expect(await within(dialog).findByTestId('payment-error-unresolved-attribution')).toHaveTextContent(/has not been financially posted/)
  })

  it('reports a concurrent financial conflict from the backend', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf())
    postMock.mockRejectedValue(backendError(409, 'PAYMENT_POSTING_CONFLICT'))
    renderDialog(officer('poster'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Post to accounts' }))
    const dialog = await screen.findByRole('dialog', { name: 'Post payment to accounts' })
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Post payment' }))
    expect(await within(dialog).findByTestId('payment-error')).toHaveTextContent(/conflicted with another change and was not applied/)
  })

  it('treats a lost connection as unknown: it refetches and does not repost', async () => {
    paymentMock.mockResolvedValue(verifiedPayment())
    postingMock.mockResolvedValue(postingOf())
    postMock.mockRejectedValue(networkError())
    renderDialog(officer('poster'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Post to accounts' }))
    const dialog = await screen.findByRole('dialog', { name: 'Post payment to accounts' })
    await user.click(within(dialog).getByRole('checkbox'))
    const before = paymentMock.mock.calls.length
    await user.click(within(dialog).getByRole('button', { name: 'Post payment' }))
    expect(await within(dialog).findByText(/connection was lost, so the result is unknown/)).toBeInTheDocument()
    await waitFor(() => expect(paymentMock.mock.calls.length).toBeGreaterThan(before))
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(within(dialog).queryByRole('button', { name: 'Post payment' })).not.toBeInTheDocument()
  })

  // ------------------------------------------------------------ evidence and history

  it('shows an uploaded receipt as not verified and lists the status history', async () => {
    paymentMock.mockResolvedValue(paymentOf({ evidenceFileKey: 'cooperatives/c1/receipt.pdf' }))
    eventsMock.mockResolvedValue([
      { id: 'e1', toStatus: 'SUBMITTED', actorRole: 'Member', createdAt: '2026-10-01T08:00:00Z' },
      { id: 'e2', fromStatus: 'SUBMITTED', toStatus: 'UNDER_REVIEW', actorRole: 'Accountant', createdAt: '2026-10-01T09:00:00Z' },
      { id: 'e3', fromStatus: 'VERIFIED', toStatus: 'VERIFIED', actorRole: 'Accountant', comment: 'POSTING_FAILED:OBLIGATION_NOT_FOUND', createdAt: '2026-10-02T09:00:00Z' },
    ])
    renderDialog()
    expect(await screen.findByText('View receipt')).toBeInTheDocument()
    expect(screen.getAllByText('Receipt uploaded. It has not been verified.').length).toBeGreaterThan(0)
    const history = await screen.findByTestId('payment-events')
    expect(history).toHaveTextContent('Pending review → Under review')
    expect(history).toHaveTextContent(/a posting attempt failed/)
  })
})
