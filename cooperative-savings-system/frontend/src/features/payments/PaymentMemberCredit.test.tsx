import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchContributions, fetchMyContributions } from '@/shared/api/contributions'
import { fetchLoans, fetchMyLoans } from '@/shared/api/loans'
import { applyCredit, fetchCredits, fetchPaymentAccounts, submitPayment } from '@/shared/api/memberPayments'
import { pageOf, paymentOf, renderWithApp } from '@/test/paymentTestHarness'
import { MemberCreditPanel } from './MemberCreditPanel'
import { PaymentSubmitPanel } from './PaymentSubmitPanel'

vi.mock('@/shared/api/contributions', () => ({ fetchMyContributions: vi.fn(), fetchContributions: vi.fn() }))
vi.mock('@/shared/api/loans', () => ({ fetchMyLoans: vi.fn(), fetchLoans: vi.fn() }))
vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))
vi.mock('@/shared/api/memberPayments', () => ({
  submitPayment: vi.fn(),
  analyzeReceipt: vi.fn(),
  fetchPaymentAccounts: vi.fn(),
  fetchCredits: vi.fn(),
  applyCredit: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const MEMBER = { id: 'payer', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }
const OFFICER = { id: 'o', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE', 'LOAN_WRITE'] }

const contribution = {
  id: 'c9',
  memberUserId: 'payer',
  year: 2026,
  month: 9,
  expectedAmount: '2000',
  paidAmount: '500',
  outstandingAmount: '1500',
  status: 'PARTIALLY_PAID',
}
const loan = {
  id: 'loan-aaaa-1111',
  memberUserId: 'payer',
  status: 'ACTIVE',
  outstandingPrincipal: '9000',
  outstandingInterest: '0',
  outstandingPenalty: '0',
  totalRepaidPrincipal: '0',
  totalRepaidInterest: '0',
  totalRepaidPenalty: '0',
}

describe('excess money is held as credit, never silently absorbed', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMyContributions).mockResolvedValue(pageOf([contribution]) as never)
    vi.mocked(fetchMyLoans).mockResolvedValue(pageOf([loan]) as never)
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([])
    vi.mocked(fetchCredits).mockResolvedValue({ balances: [], totalHeld: '0' })
    vi.mocked(submitPayment).mockResolvedValue(paymentOf())
  })

  async function ready() {
    await screen.findByText('Add contribution')
  }

  it('offers to hold the part of the payment that nothing is due for, and sends it as an explicit credit', async () => {
    renderWithApp(<PaymentSubmitPanel cooperativeId="c1" />, { user: MEMBER })
    await ready()
    const user = userEvent.setup()
    await user.click(screen.getByRole('combobox', { name: 'Contribution period' }))
    await user.click(await screen.findByRole('option', { name: /2026-09/ }))
    await user.click(screen.getByRole('button', { name: 'Add contribution' }))
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '2000')
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')

    const offer = await screen.findByTestId('payment-credit-offer')
    expect(offer).toHaveTextContent(/not income for the Ikimina/)
    await user.click(within(offer).getByRole('button', { name: /Hold .*500.* as credit/ }))
    expect(screen.queryByTestId('payment-credit-offer')).not.toBeInTheDocument()
    const lines = screen.getAllByTestId('payment-allocation-draft')
    expect(lines).toHaveLength(2)
    expect(lines[1]).toHaveTextContent('Held credit: Keep as credit (decide later)')

    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    await waitFor(() => expect(submitPayment).toHaveBeenCalledTimes(1))
    expect(vi.mocked(submitPayment).mock.calls[0][1].allocations).toEqual([
      { type: 'CONTRIBUTION', amount: '1500', periodYear: 2026, periodMonth: 9 },
      { type: 'MEMBER_CREDIT', amount: '500', creditPurpose: 'UNAPPLIED_CREDIT', loanId: undefined },
    ])
  })

  it('a loan prepayment must name its loan before the excess can be held', async () => {
    renderWithApp(<PaymentSubmitPanel cooperativeId="c1" />, { user: MEMBER })
    await ready()
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /Total amount paid/ }), '700')
    const offer = await screen.findByTestId('payment-credit-offer')
    await user.click(within(offer).getByRole('combobox', { name: 'What is the extra for?' }))
    await user.click(await screen.findByRole('option', { name: 'Prepayment of a loan' }))
    const hold = within(offer).getByRole('button', { name: /Hold .*700.* as credit/ })
    expect(hold).toBeDisabled()
    await user.click(within(offer).getByRole('combobox', { name: 'Which loan?' }))
    await user.click(await screen.findByRole('option', { name: /loan-aaa/ }))
    expect(hold).toBeEnabled()
    await user.click(hold)
    await user.type(screen.getByRole('textbox', { name: /Transaction reference/ }), 'TX1')
    await user.click(screen.getByRole('button', { name: 'Submit payment for verification' }))
    await waitFor(() => expect(submitPayment).toHaveBeenCalledTimes(1))
    expect(vi.mocked(submitPayment).mock.calls[0][1].allocations).toEqual([
      { type: 'MEMBER_CREDIT', amount: '700', creditPurpose: 'LOAN_PREPAYMENT', loanId: 'loan-aaaa-1111' },
    ])
  })

  it('shows the member their held credit by purpose', async () => {
    vi.mocked(fetchCredits).mockResolvedValue({
      balances: [{ memberUserId: 'payer', memberName: 'Payer', purpose: 'ADVANCE_CONTRIBUTION', loanId: null, balance: '20000' }],
      totalHeld: '20000',
    })
    renderWithApp(<PaymentSubmitPanel cooperativeId="c1" />, { user: MEMBER })
    expect(await screen.findByTestId('payment-my-credit')).toHaveTextContent(/Advance for future contributions: .*20,000/)
  })
})

describe('MemberCreditPanel (officers)', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchCredits).mockResolvedValue({
      balances: [
        { memberUserId: 'm1', memberName: 'Mary Member', purpose: 'ADVANCE_CONTRIBUTION', loanId: null, balance: '20000' },
        { memberUserId: 'm2', memberName: 'Paul Member', purpose: 'LOAN_PREPAYMENT', loanId: 'loan-aaaa-1111', balance: '5000' },
      ],
      totalHeld: '25000',
    })
    vi.mocked(fetchContributions).mockResolvedValue(
      pageOf([{ ...contribution, memberUserId: 'm1', outstandingAmount: '30000', expectedAmount: '30000', paidAmount: '0', status: 'PENDING' }]) as never,
    )
    vi.mocked(fetchLoans).mockResolvedValue(pageOf([{ ...loan, memberUserId: 'm2' }]) as never)
    vi.mocked(applyCredit).mockResolvedValue()
  })

  it('lists what is held for each member and the total, and says it is not income', async () => {
    renderWithApp(<MemberCreditPanel cooperativeId="c1" />, { user: OFFICER })
    const rows = await screen.findAllByTestId('member-credit-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Mary Member')
    expect(rows[0]).toHaveTextContent(/Advance for future contributions/)
    expect(screen.getByTestId('member-credit-total')).toHaveTextContent(/25,000/)
    expect(screen.getByText(/not income and not group funds until an officer applies it/)).toBeInTheDocument()
  })

  it('applies an advance credit to a contribution period with an idempotency key', async () => {
    renderWithApp(<MemberCreditPanel cooperativeId="c1" />, { user: OFFICER })
    const user = userEvent.setup()
    const rows = await screen.findAllByTestId('member-credit-row')
    await user.click(within(rows[0]).getByRole('button', { name: 'Apply' }))
    const dialog = await screen.findByRole('dialog')
    // an advance contribution can only go to a contribution
    expect(within(dialog).queryByText('A loan repayment')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('combobox', { name: 'Contribution period' }))
    await user.click(await screen.findByRole('option', { name: /2026-09/ }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Amount to apply' }), '10000')
    await user.click(within(dialog).getByRole('button', { name: 'Apply credit' }))
    await waitFor(() => expect(applyCredit).toHaveBeenCalledTimes(1))
    const [, payload, key] = vi.mocked(applyCredit).mock.calls[0]
    expect(payload).toMatchObject({
      memberUserId: 'm1',
      purpose: 'ADVANCE_CONTRIBUTION',
      targetType: 'CONTRIBUTION',
      periodYear: 2026,
      periodMonth: 9,
      amount: '10000',
    })
    expect(typeof key).toBe('string')
    expect(key.length).toBeGreaterThan(8)
  })

  it('refuses an amount larger than what is held before asking the server', async () => {
    renderWithApp(<MemberCreditPanel cooperativeId="c1" />, { user: OFFICER })
    const user = userEvent.setup()
    const rows = await screen.findAllByTestId('member-credit-row')
    await user.click(within(rows[0]).getByRole('button', { name: 'Apply' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('combobox', { name: 'Contribution period' }))
    await user.click(await screen.findByRole('option', { name: /2026-09/ }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Amount to apply' }), '20001')
    await user.click(within(dialog).getByRole('button', { name: 'Apply credit' }))
    expect(applyCredit).not.toHaveBeenCalled()
  })

  it('a loan-prepayment credit can only be applied to its own loan', async () => {
    renderWithApp(<MemberCreditPanel cooperativeId="c1" />, { user: OFFICER })
    const user = userEvent.setup()
    const rows = await screen.findAllByTestId('member-credit-row')
    await user.click(within(rows[1]).getByRole('button', { name: 'Apply' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByText('A contribution period')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('combobox', { name: 'Loan' }))
    await user.click(await screen.findByRole('option', { name: /loan-aaa/ }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Amount to apply' }), '5000')
    await user.click(within(dialog).getByRole('button', { name: 'Apply credit' }))
    await waitFor(() => expect(applyCredit).toHaveBeenCalledTimes(1))
    expect(vi.mocked(applyCredit).mock.calls[0][1]).toMatchObject({
      purpose: 'LOAN_PREPAYMENT',
      bucketLoanId: 'loan-aaaa-1111',
      targetType: 'LOAN_REPAYMENT',
      loanId: 'loan-aaaa-1111',
      amount: '5000',
    })
  })
})
