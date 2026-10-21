import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchMyPayments,
  fetchPayment,
  fetchPaymentEvents,
  fetchPaymentPosting,
  withdrawPayment,
} from '@/shared/api/memberPayments'
import { backendError, pageOf, paymentOf, renderWithApp, summaryOf, type TestUser } from '@/test/paymentTestHarness'
import { PaymentListPanel } from './PaymentListPanel'

vi.mock('@/shared/api/paymentVerification', () => ({
  fetchVerificationCapabilities: vi.fn().mockResolvedValue(null),
  fetchVerificationOverview: vi.fn().mockResolvedValue({
    requiresTrustedConfirmation: false,
    automaticProviderConfirmation: 'UNAVAILABLE',
    current: null,
    history: [],
  }),
  fetchReceiptExtractions: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/shared/api/memberPayments', () => ({
  fetchMyPayments: vi.fn(),
  fetchPayment: vi.fn(),
  fetchPaymentEvents: vi.fn(),
  fetchPaymentPosting: vi.fn(),
  fetchPaymentReversals: vi.fn().mockResolvedValue([]),
  withdrawPayment: vi.fn(),
}))

const PAYER: TestUser = { id: 'payer', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }
const OTHER: TestUser = { id: 'someone-else', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }

describe('withdrawing a payment', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMyPayments).mockResolvedValue(pageOf([summaryOf({ id: 'p1', status: 'SUBMITTED' })]) as never)
    vi.mocked(fetchPaymentEvents).mockResolvedValue([])
    vi.mocked(fetchPaymentPosting).mockResolvedValue(undefined as never)
  })

  async function openDetail(user: TestUser, label = 'Pending review') {
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user })
    const events = userEvent.setup()
    await events.click((await screen.findAllByText(label))[0])
    return { events, detail: await screen.findByTestId('payment-detail') }
  }

  it('lets the payer withdraw a payment nobody has started to review, after saying what that means', async () => {
    vi.mocked(fetchPayment).mockResolvedValue(paymentOf({ status: 'SUBMITTED' }))
    vi.mocked(withdrawPayment).mockResolvedValue(paymentOf({ status: 'REJECTED' }))
    const { events, detail } = await openDetail(PAYER)
    await events.click(within(detail).getByRole('button', { name: 'Withdraw payment' }))
    const dialog = await screen.findByRole('dialog', { name: 'Withdraw this payment?' })
    expect(within(dialog).getByText(/nothing was posted/)).toBeInTheDocument()
    expect(withdrawPayment).not.toHaveBeenCalled()
    await events.click(within(dialog).getByRole('button', { name: 'Withdraw Payment' }))
    await waitFor(() => expect(withdrawPayment).toHaveBeenCalledWith('c1', 'p1'))
  })

  it('shows the backend refusal when the payment was picked up for review in the meantime', async () => {
    vi.mocked(fetchPayment).mockResolvedValue(paymentOf({ status: 'SUBMITTED' }))
    vi.mocked(withdrawPayment).mockRejectedValue(backendError(422, 'PAYMENT_CANNOT_BE_WITHDRAWN'))
    const { events, detail } = await openDetail(PAYER)
    await events.click(within(detail).getByRole('button', { name: 'Withdraw payment' }))
    const dialog = await screen.findByRole('dialog', { name: 'Withdraw this payment?' })
    await events.click(within(dialog).getByRole('button', { name: 'Withdraw Payment' }))
    expect(await within(dialog).findByTestId('payment-error')).toHaveTextContent(/Only a payment that nobody has started to review can be withdrawn/)
  })

  it('offers no withdrawal once the payment is under review, verified or posted, or to anyone but the payer', async () => {
    vi.mocked(fetchMyPayments).mockResolvedValue(pageOf([summaryOf({ id: 'p1', status: 'UNDER_REVIEW' })]) as never)
    vi.mocked(fetchPayment).mockResolvedValue(paymentOf({ status: 'UNDER_REVIEW' }))
    const { detail } = await openDetail(PAYER, 'Under review')
    expect(within(detail).queryByRole('button', { name: 'Withdraw payment' })).not.toBeInTheDocument()
  })

  it('never shows the withdraw action to a member who did not submit the payment', async () => {
    vi.mocked(fetchPayment).mockResolvedValue(paymentOf({ status: 'SUBMITTED', memberUserId: 'payer' }))
    const { detail } = await openDetail(OTHER)
    expect(within(detail).queryByRole('button', { name: 'Withdraw payment' })).not.toBeInTheDocument()
  })
})
