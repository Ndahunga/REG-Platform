import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMyContributions } from '@/shared/api/contributions'
import { fetchMyLoans } from '@/shared/api/loans'
import { fetchCredits, fetchPaymentAccounts, submitPayment } from '@/shared/api/memberPayments'
import { backendError, networkError, pageOf, paymentOf, renderWithApp } from '@/test/paymentTestHarness'
import { PaymentSubmitPanel } from './PaymentSubmitPanel'

vi.mock('@/shared/api/contributions', () => ({ fetchMyContributions: vi.fn() }))
vi.mock('@/shared/api/loans', () => ({ fetchMyLoans: vi.fn() }))
vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))
vi.mock('@/shared/api/memberPayments', () => ({
  submitPayment: vi.fn(),
  analyzeReceipt: vi.fn(),
  fetchPaymentAccounts: vi.fn(),
  fetchCredits: vi.fn(),
}))
// The subscription/offline guard is covered elsewhere; a plain button keeps these tests on the payment rules.
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const contributionsMock = vi.mocked(fetchMyContributions)
const loansMock = vi.mocked(fetchMyLoans)
const submitMock = vi.mocked(submitPayment)

const MEMBER = { id: 'payer', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }

const contribution = (month: number, extra: Record<string, unknown> = {}) => ({
  id: `c${month}`,
  memberUserId: 'payer',
  year: 2026,
  month,
  expectedAmount: '2000',
  paidAmount: '500',
  outstandingAmount: '1500',
  status: 'PARTIALLY_PAID',
  ...extra,
})
const loan = (id: string, principal: string, extra: Record<string, unknown> = {}) => ({
  id,
  memberUserId: 'payer',
  status: 'ACTIVE',
  outstandingPrincipal: principal,
  outstandingInterest: '0',
  outstandingPenalty: '0',
  totalRepaidPrincipal: '1000',
  totalRepaidInterest: '100',
  totalRepaidPenalty: '0',
  ...extra,
})

function renderPanel() {
  return renderWithApp(<PaymentSubmitPanel cooperativeId="c1" />, { user: MEMBER })
}

async function choose(user: ReturnType<typeof userEvent.setup>, label: string, optionText: RegExp) {
  await user.click(screen.getByRole('combobox', { name: label }))
  await user.click(await screen.findByRole('option', { name: optionText }))
}

async function ready() {
  await screen.findByText('Add contribution')
}

describe('PaymentSubmitPanel', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    contributionsMock.mockResolvedValue(
      pageOf([
        contribution(9, { dueDate: '2026-09-10', overdue: true, overdueAmount: '1500' }),
        contribution(10, { operatingModel: 'HYBRID', rotatingBps: 7000, retainedBps: 3000, expectedRotatingAmount: '1400', expectedRetainedAmount: '600' }),
        contribution(8, { status: 'WAIVED' }),
        contribution(7, { paidAmount: '2000', outstandingAmount: '0', status: 'PAID' }),
      ]) as never,
    )
    loansMock.mockResolvedValue(
      pageOf([loan('loan-aaaa-1111', '9000'), loan('loan-bbbb-2222', '4000'), loan('loan-cccc-3333', '0', { status: 'CLOSED' })]) as never,
    )
    submitMock.mockResolvedValue(paymentOf())
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([])
    vi.mocked(fetchCredits).mockResolvedValue({ balances: [], totalHeld: '0' })
  })

  it('shows what a payment is, the legacy single-month path, and only open obligations', async () => {
    renderPanel()
    await ready()
    expect(screen.getByText(/Your contribution and loan balances change only after officers verify/)).toBeInTheDocument()
    expect(screen.getByTestId('payment-legacy-note')).toHaveTextContent(/never submit the same transfer in both places/)
    const user = userEvent.setup()
    await user.click(screen.getByRole('combobox', { name: 'Contribution period' }))
    expect(await screen.findByRole('option', { name: /2026-09/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /2026-10/ })).toBeInTheDocument()
    // waived and fully paid periods cannot be chosen, and nothing can create a historical obligation
    expect(screen.queryByRole('option', { name: /2026-08/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /2026-07/ })).not.toBeInTheDocument()
  })

  it('offers only repayable loans and no member-credit allocation', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await user.click(screen.getByRole('combobox', { name: 'Loan' }))
    expect(await screen.findByRole('option', { name: /loan-aaa/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /loan-bbb/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /loan-ccc/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/Member credit/i)).not.toBeInTheDocument()
  })

  it('shows due date, overdue amount and the expected hybrid split labelled as expected', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await choose(user, 'Contribution period', /2026-10/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    const drafts = screen.getAllByTestId('payment-allocation-draft')
    expect(drafts).toHaveLength(2)
    expect(within(drafts[0]).getByTestId('contribution-due-date')).toHaveTextContent('2026-09-10')
    expect(within(drafts[0]).getByTestId('contribution-overdue')).toBeInTheDocument()
    expect(within(drafts[0]).getByText(/Expected .*2[\s,]*000.* paid .*500.* outstanding .*1[\s,]*500/)).toBeInTheDocument()
    expect(within(drafts[1]).getByTestId('contribution-expected-split')).toHaveTextContent(/Expected \(not received\)/)
  })

  it('blocks a total that does not match the allocations and explains it', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '2000')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    expect(screen.getByTestId('payment-allocation-total')).toHaveTextContent(/Allocated .*1[\s,]*500.* of .*2[\s,]*000/)
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    expect(await screen.findByText(/allocations total .*1[\s,]*500.* but the payment is .*2[\s,]*000/i)).toBeInTheDocument()
    expect(submitMock).not.toHaveBeenCalled()
  })

  it('blocks submission without allocations, with an invalid amount, or above what is outstanding', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1000')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    expect(await screen.findByText(/Add at least one contribution period or loan repayment\./, { selector: '[role="alert"]' })).toBeInTheDocument()

    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    const amount = screen.getByRole('textbox', { name: 'Amount to allocate' })
    await user.clear(amount)
    await user.type(amount, '1500.5')
    expect(await screen.findByText('This is more than what is still outstanding.')).toBeInTheDocument()
    await user.clear(amount)
    await user.type(amount, 'abc')
    expect(await screen.findByText(/Enter a positive amount with at most 4 decimals/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    expect(submitMock).not.toHaveBeenCalled()
  })

  it('submits a contribution-only payment with the exact backend payload and an idempotency key', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), ' TX1 ')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))

    await waitFor(() => expect(submitMock).toHaveBeenCalledTimes(1))
    const [cooperativeId, payload, key] = submitMock.mock.calls[0]
    expect(cooperativeId).toBe('c1')
    expect(payload).toMatchObject({
      amount: '1500',
      paymentMethod: 'MTN_MOMO',
      transactionReference: 'TX1',
      allocations: [{ type: 'CONTRIBUTION', amount: '1500', periodYear: 2026, periodMonth: 9 }],
    })
    expect(payload.evidenceFileKey).toBeUndefined()
    expect(typeof key).toBe('string')
    expect(key.length).toBeGreaterThan(8)
    // nothing about principal, interest or penalty is calculated or sent from the browser
    expect(JSON.stringify(payload)).not.toMatch(/principal|interest|penalty/i)
  })

  it('submits a loan-only payment', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Loan', /loan-aaa/)
    await user.click(screen.getByRole('button', { name: 'Add loan repayment' }))
    const amount = screen.getByRole('textbox', { name: 'Amount to allocate' })
    await user.clear(amount)
    await user.type(amount, '5000')
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '5000')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX2')
    expect(screen.getByText(/loan system decides how this amount is split/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    await waitFor(() => expect(submitMock).toHaveBeenCalledTimes(1))
    expect(submitMock.mock.calls[0][1].allocations).toEqual([{ type: 'LOAN_REPAYMENT', amount: '5000', loanId: 'loan-aaaa-1111' }])
  })

  it('submits a mixed payment over several contribution periods and several loans', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await choose(user, 'Contribution period', /2026-10/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await choose(user, 'Loan', /loan-aaa/)
    await user.click(screen.getByRole('button', { name: 'Add loan repayment' }))
    await choose(user, 'Loan', /loan-bbb/)
    await user.click(screen.getByRole('button', { name: 'Add loan repayment' }))
    // prefilled with each obligation's outstanding amount: 1500 + 1500 + 9000 + 4000 = 16000
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '16000')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX3')
    expect(screen.getByTestId('payment-allocation-total')).toHaveTextContent(/Allocated .*16[\s,]*000.* of .*16[\s,]*000/)
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    await waitFor(() => expect(submitMock).toHaveBeenCalledTimes(1))
    const allocations = submitMock.mock.calls[0][1].allocations
    expect(allocations).toHaveLength(4)
    expect(allocations.map((a) => a.type)).toEqual(['CONTRIBUTION', 'CONTRIBUTION', 'LOAN_REPAYMENT', 'LOAN_REPAYMENT'])
    expect(submitMock.mock.calls[0][1].amount).toBe('16000')
  })

  it('requires a reference except for cash', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    expect(await screen.findByText('A transaction reference is required for this payment method.')).toBeInTheDocument()
    expect(submitMock).not.toHaveBeenCalled()
    await choose(user, 'Payment method', /Cash/)
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    await waitFor(() => expect(submitMock).toHaveBeenCalledTimes(1))
    expect(submitMock.mock.calls[0][1].transactionReference).toBeUndefined()
  })

  it('shows the accepted payment as pending review and never as posted', async () => {
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    const accepted = await screen.findByTestId('payment-accepted')
    expect(within(accepted).getByTestId('payment-status')).toHaveTextContent('Pending review')
    expect(within(accepted).getByTestId('payment-status-notice')).toHaveTextContent(/Nothing has changed in your contributions or loans yet/)
    expect(accepted).not.toHaveTextContent(/posted/i)
  })

  it('disables the button while submitting', async () => {
    submitMock.mockReturnValue(new Promise(() => {}))
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    const pending = await screen.findByRole('button', { name: 'Submitting…' })
    expect(pending).toBeDisabled()
    fireEvent.click(pending)
    expect(submitMock).toHaveBeenCalledTimes(1)
  })

  it('keeps the form and shows the backend reason when submission fails', async () => {
    submitMock.mockRejectedValue(backendError(409, 'DUPLICATE_PAYMENT_REFERENCE'))
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    expect(await screen.findByTestId('payment-error')).toHaveTextContent(/already used by a payment in this Saving Scheme/)
    expect(screen.getAllByTestId('payment-allocation-draft')).toHaveLength(1)
    expect(screen.getByRole('textbox', { name: /Total amount paid/ })).toHaveValue('1,500')
  })

  it('replays the same idempotency key when the same payment is resubmitted after a lost connection', async () => {
    submitMock.mockRejectedValueOnce(networkError()).mockResolvedValueOnce(paymentOf())
    renderPanel()
    await ready()
    const user = userEvent.setup()
    await choose(user, 'Contribution period', /2026-09/)
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '1500')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    expect(await screen.findByText(/connection was lost, so the result is unknown/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    await waitFor(() => expect(submitMock).toHaveBeenCalledTimes(2))
    expect(submitMock.mock.calls[1][2]).toBe(submitMock.mock.calls[0][2])
  })

  it('shows an error state when the obligations cannot be loaded', async () => {
    contributionsMock.mockRejectedValue(new Error('boom'))
    renderPanel()
    expect(await screen.findByText('Your contribution and loan obligations could not be loaded.')).toBeInTheDocument()
  })
})
