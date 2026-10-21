import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approvePaymentReversal,
  declinePaymentReversal,
  fetchPayment,
  fetchPaymentEvents,
  fetchPaymentPosting,
  fetchPaymentReversals,
  requestPaymentReversal,
  withdrawPaymentReversal,
} from '@/shared/api/memberPayments'
import type { PaymentReversal } from '@/shared/types/memberPayment'
import { backendError, paymentOf, postingOf, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
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
const reversalsMock = vi.mocked(fetchPaymentReversals)
const requestMock = vi.mocked(requestPaymentReversal)
const approveMock = vi.mocked(approvePaymentReversal)
const declineMock = vi.mocked(declinePaymentReversal)
const withdrawMock = vi.mocked(withdrawPaymentReversal)

const officer = (id: string): TestUser => ({
  id,
  roles: ['ACCOUNTANT'],
  permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE', 'LOAN_WRITE'],
})

/** A President or Vice President: the independent fund authority that approves or declines a reversal. */
const approver = (id: string): TestUser => ({
  id,
  roles: ['PRESIDENT'],
  permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE', 'FUND_AUTHORIZE'],
})

const contributionOnly = (extra: Record<string, unknown> = {}) =>
  paymentOf({
    status: 'POSTED',
    postedAt: '2026-10-03T08:00:00Z',
    allocations: [{ id: 'a1', type: 'CONTRIBUTION', amount: '1500', periodYear: 2026, periodMonth: 11 }],
    amount: '1500',
    ...extra,
  } as never)

const reversal = (extra: Partial<PaymentReversal> = {}): PaymentReversal => ({
  id: 'r1',
  paymentId: 'p1',
  memberUserId: 'payer',
  paymentAmount: '1500',
  status: 'REQUESTED',
  reason: 'Paid to the wrong member',
  requestedBy: 'requester',
  requestedAt: '2026-10-04T08:00:00Z',
  takenBack: [],
  ...extra,
})

function renderDialog(user: TestUser) {
  return renderWithApp(<PaymentDetailDialog cooperativeId="c1" paymentId="p1" staff onClose={() => {}} />, { user })
}

describe('verified payments and contribution-only reversal', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchPaymentEvents).mockResolvedValue([])
    vi.mocked(fetchPaymentPosting).mockResolvedValue(postingOf({ status: 'POSTED' }))
    reversalsMock.mockResolvedValue([])
  })

  it('never offers rejection of a verified payment and says why', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'VERIFIED', verifiedBy: 'verifier', verifiedAt: '2026-10-02T08:00:00Z' } as never))
    vi.mocked(fetchPaymentPosting).mockResolvedValue(postingOf())
    renderDialog(officer('poster'))
    expect(await screen.findByTestId('payment-verified-note')).toHaveTextContent(/cannot be rejected/)
    expect(screen.getByTestId('payment-verified-note')).toHaveTextContent(/nothing is refunded or turned into member credit/)
    expect(screen.queryByRole('button', { name: 'Reject payment' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Post to accounts' })).toBeInTheDocument()
  })

  it('still offers rejection before verification', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW' }))
    vi.mocked(fetchPaymentPosting).mockResolvedValue(postingOf({ status: 'UNDER_REVIEW', verificationEvidenceRecorded: false }))
    renderDialog(officer('someone'))
    expect(await screen.findByRole('button', { name: 'Reject payment' })).toBeInTheDocument()
  })

  it('lets an officer request the reversal of a posted contribution-only payment, with a reason', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    requestMock.mockResolvedValue(reversal())
    renderDialog(officer('requester'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Request reversal' }))
    const dialog = await screen.findByRole('dialog', { name: 'Request reversal of this payment' })
    expect(within(dialog).getByText(/Nothing changes until a different officer approves/)).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Request reversal' }))
    expect(await within(dialog).findByText('A reason is required.')).toBeInTheDocument()
    expect(requestMock).not.toHaveBeenCalled()

    await user.type(within(dialog).getByRole('textbox', { name: /Why must this payment be reversed/ }), 'Paid to the wrong member')
    await user.click(within(dialog).getByRole('button', { name: 'Request reversal' }))
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith('c1', 'p1', 'Paid to the wrong member'))
  })

  it('offers the reversal of a payment that includes a loan repayment, with a note about what it gives back', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'POSTED', postedAt: '2026-10-03T08:00:00Z' }))
    renderDialog(officer('requester'))
    expect(await screen.findByTestId('payment-reversal-loan-note')).toHaveTextContent(/gives each loan repayment back to its loan/)
    expect(screen.getByRole('button', { name: 'Request reversal' })).toBeInTheDocument()
  })

  it('shows no reversal request to the payer', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    renderDialog(officer('payer'))
    await screen.findByTestId('payment-reversal-section')
    expect(screen.queryByRole('button', { name: 'Request reversal' })).not.toBeInTheDocument()
  })

  it('does not let the requester, the payer or the submitter approve their own reversal request', async () => {
    paymentMock.mockResolvedValue(contributionOnly({ submittedBy: 'clerk' }))
    reversalsMock.mockResolvedValue([reversal()])
    const { unmount } = renderDialog(approver('requester'))
    expect(await screen.findByTestId('payment-reversal-independence')).toHaveTextContent(/different officer must decide/)
    expect(screen.queryByRole('button', { name: 'Approve and reverse' })).not.toBeInTheDocument()
    unmount()

    renderDialog(approver('clerk'))
    expect(await screen.findByTestId('payment-reversal-independence')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve and reverse' })).not.toBeInTheDocument()
  })

  it('lets a different officer approve, then refetches from the backend', async () => {
    paymentMock.mockResolvedValueOnce(contributionOnly())
    reversalsMock.mockResolvedValue([reversal()])
    approveMock.mockResolvedValue(reversal({ status: 'EXECUTED' }))
    renderDialog(approver('approver'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Approve and reverse' }))
    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('c1', 'r1'))
    await waitFor(() => expect(paymentMock.mock.calls.length).toBeGreaterThan(1))
  })

  it('shows the backend reason when approval is refused because of a later change or a payout', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    reversalsMock.mockResolvedValue([reversal()])
    approveMock.mockRejectedValue(backendError(422, 'REVERSAL_BLOCKED_DISTRIBUTED'))
    renderDialog(approver('approver'))
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Approve and reverse' }))
    expect(await screen.findByTestId('payment-error')).toHaveTextContent(/rotation payout already disbursed or approved funds/)
  })

  it('declines with a reason', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    reversalsMock.mockResolvedValue([reversal()])
    declineMock.mockResolvedValue(reversal({ status: 'DECLINED', decisionNote: 'Correct as posted' }))
    renderDialog(approver('approver'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Decline' }))
    const dialog = await screen.findByRole('dialog', { name: 'Decline the reversal' })
    await user.type(within(dialog).getByRole('textbox', { name: /Reason for declining/ }), 'Correct as posted')
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))
    await waitFor(() => expect(declineMock).toHaveBeenCalledWith('c1', 'r1', 'Correct as posted'))
  })

  it('shows what an executed reversal took back, including the rotating and retained split', async () => {
    paymentMock.mockResolvedValue(contributionOnly({ status: 'REVERSED' }))
    reversalsMock.mockResolvedValue([
      reversal({
        status: 'EXECUTED',
        decidedBy: 'approver',
        takenBack: [
          {
            postingId: 'po1',
            contributionId: 'c1',
            periodYear: 2026,
            periodMonth: 11,
            amount: '1500',
            paidBefore: '1500',
            paidAfter: '0',
            rotatingAmount: '750',
            retainedAmount: '750',
          },
        ],
      }),
    ])
    renderDialog(officer('someone'))
    const taken = await screen.findByTestId('payment-reversal-takenback')
    expect(taken).toHaveTextContent(/2026-11/)
    expect(taken).toHaveTextContent(/rotating .*750.*retained .*750/)
    expect(screen.getByTestId('payment-reversal')).toHaveTextContent('Reversed')
    expect(screen.queryByRole('button', { name: 'Request reversal' })).not.toBeInTheDocument()
  })

  it('shows an officer without fund authority no approve or decline, only why', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    reversalsMock.mockResolvedValue([reversal()])
    renderDialog(officer('accountant'))
    expect(await screen.findByTestId('payment-reversal-needs-authority')).toHaveTextContent(/fund authority/)
    expect(screen.queryByRole('button', { name: 'Approve and reverse' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Decline' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw request' })).not.toBeInTheDocument()
  })

  it('lets the requester withdraw a pending request after a confirmation, then refetches', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    reversalsMock.mockResolvedValue([reversal({ requestedBy: 'requester' })])
    withdrawMock.mockResolvedValue(reversal({ status: 'WITHDRAWN', requestedBy: 'requester' }))
    renderDialog(officer('requester'))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Withdraw request' }))
    const dialog = await screen.findByRole('dialog', { name: 'Withdraw your reversal request' })
    expect(within(dialog).getByText(/Nothing changes: the payment stays posted/)).toBeInTheDocument()
    expect(withdrawMock).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw request' }))
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledWith('c1', 'r1'))
    await waitFor(() => expect(paymentMock.mock.calls.length).toBeGreaterThan(1))
  })

  it('offers withdrawal to nobody but the requester and never after a decision', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    reversalsMock.mockResolvedValue([reversal({ requestedBy: 'requester' })])
    const { unmount } = renderDialog(approver('someone-else'))
    await screen.findByTestId('payment-reversal')
    expect(screen.queryByRole('button', { name: 'Withdraw request' })).not.toBeInTheDocument()
    unmount()

    reversalsMock.mockResolvedValue([reversal({ requestedBy: 'requester', status: 'EXECUTED' })])
    renderDialog(officer('requester'))
    await screen.findByTestId('payment-reversal')
    expect(screen.queryByRole('button', { name: 'Withdraw request' })).not.toBeInTheDocument()
  })

  it('shows a withdrawn request as withdrawn', async () => {
    paymentMock.mockResolvedValue(contributionOnly())
    reversalsMock.mockResolvedValue([reversal({ status: 'WITHDRAWN', requestedBy: 'requester' })])
    renderDialog(officer('requester'))
    expect(await screen.findByTestId('payment-reversal')).toHaveTextContent('Withdrawn')
    expect(screen.getByRole('button', { name: 'Request reversal' })).toBeInTheDocument()
  })
})
