import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchMyPayments,
  fetchPayment,
  fetchPaymentEvents,
  fetchPaymentPosting,
  fetchPaymentReviewQueue,
  fetchPayments,
} from '@/shared/api/memberPayments'
import { pageOf, paymentOf, renderWithApp, summaryOf, type TestUser } from '@/test/paymentTestHarness'
import { PaymentListPanel } from './PaymentListPanel'

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
  fetchMyPayments: vi.fn(),
  fetchPaymentReviewQueue: vi.fn(),
  fetchPayments: vi.fn(),
  fetchPayment: vi.fn(),
  fetchPaymentEvents: vi.fn(),
  fetchPaymentPosting: vi.fn(),
  startPaymentReview: vi.fn(),
  verifyPayment: vi.fn(),
  completePaymentEvidence: vi.fn(),
  rejectPayment: vi.fn(),
  postPayment: vi.fn(),
  retryPaymentPosting: vi.fn(),
  fetchPaymentReversals: vi.fn().mockResolvedValue([]),
  requestPaymentReversal: vi.fn(),
  approvePaymentReversal: vi.fn(),
  declinePaymentReversal: vi.fn(),
  withdrawPaymentReversal: vi.fn(),
}))

const mineMock = vi.mocked(fetchMyPayments)
const queueMock = vi.mocked(fetchPaymentReviewQueue)
const allMock = vi.mocked(fetchPayments)
const paymentMock = vi.mocked(fetchPayment)
const eventsMock = vi.mocked(fetchPaymentEvents)
const postingMock = vi.mocked(fetchPaymentPosting)

const MEMBER: TestUser = { id: 'payer', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }
const OFFICER: TestUser = { id: 'officer', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE', 'LOAN_WRITE'] }

describe('member payment history', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mineMock.mockResolvedValue(
      pageOf([
        summaryOf({ id: 'p1', status: 'SUBMITTED' }),
        summaryOf({ id: 'p2', status: 'VERIFIED', allocationTypes: ['CONTRIBUTION'] }),
        summaryOf({ id: 'p3', status: 'POSTED', allocationTypes: ['LOAN_REPAYMENT'] }),
        summaryOf({ id: 'p4', status: 'REJECTED' }),
        summaryOf({ id: 'p5', status: 'UNDER_REVIEW' }),
        summaryOf({ id: 'p6', status: 'REVERSED' }),
      ]) as never,
    )
    eventsMock.mockResolvedValue([{ id: 'e1', toStatus: 'SUBMITTED', actorRole: 'Member', createdAt: '2026-10-01T08:00:00Z' }])
  })

  it('lists the member’s own payments, scoped to the selected scheme, with every status distinguished', async () => {
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    await screen.findAllByTestId('payment-status')
    expect(mineMock).toHaveBeenCalledWith('c1', { page: 0, size: 20, status: undefined })
    const statuses = screen.getAllByTestId('payment-status').map((n) => n.textContent)
    expect(statuses).toEqual(
      expect.arrayContaining(['Pending review', 'Under review', 'Verified, not yet posted', 'Posted', 'Rejected', 'Reversed']),
    )
    // a verified payment is never labelled posted
    const verifiedChip = screen.getAllByTestId('payment-status').find((n) => n.textContent === 'Verified, not yet posted')
    expect(verifiedChip).toBeDefined()
    expect(allMock).not.toHaveBeenCalled()
    expect(queueMock).not.toHaveBeenCalled()
  })

  it('filters by status through the backend', async () => {
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    await screen.findAllByTestId('payment-status')
    const user = userEvent.setup()
    await user.click(screen.getByRole('combobox', { name: 'Status' }))
    await user.click(await screen.findByRole('option', { name: 'Posted' }))
    await waitFor(() => expect(mineMock).toHaveBeenLastCalledWith('c1', { page: 0, size: 20, status: 'POSTED' }))
  })

  it('explains a verified payment without implying a balance changed', async () => {
    paymentMock.mockResolvedValue(
      paymentOf({ status: 'VERIFIED', verifiedAt: '2026-10-02T08:00:00Z', allocations: [{ id: 'a1', type: 'CONTRIBUTION', amount: '2000', periodYear: 2026, periodMonth: 9 }] }),
    )
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    const row = (await screen.findAllByText('Verified, not yet posted'))[0]
    const user = userEvent.setup()
    await user.click(row)
    const detail = await screen.findByTestId('payment-detail')
    expect(within(detail).getByTestId('payment-status-notice')).toHaveTextContent(
      /funds have been confirmed, but the allocations may not yet be posted to your financial accounts/,
    )
    expect(within(detail).getByTestId('payment-verified-at')).toBeInTheDocument()
    expect(within(detail).queryByTestId('payment-posted-at')).not.toBeInTheDocument()
    expect(within(detail).getByTestId('payment-allocations')).toHaveTextContent(/Contribution 2026-09/)
    expect(within(detail).getByTestId('payment-events')).toBeInTheDocument()
  })

  it('shows posted only when the backend says posted, and never gives the member officer actions or posting data', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'POSTED', postedAt: '2026-10-03T08:00:00Z', verifiedAt: '2026-10-02T08:00:00Z' }))
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    const user = userEvent.setup()
    await user.click((await screen.findAllByText('Posted'))[0])
    const detail = await screen.findByTestId('payment-detail')
    expect(within(detail).getByTestId('payment-status-notice')).toHaveTextContent(/The payment was posted/)
    expect(within(detail).getByTestId('payment-posted-at')).toBeInTheDocument()
    expect(within(detail).queryByTestId('payment-actions')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /verify|post to accounts|retry posting|reject/i })).not.toBeInTheDocument()
    expect(postingMock).not.toHaveBeenCalled()
  })

  it('shows the rejection reason and an empty state', async () => {
    paymentMock.mockResolvedValue(paymentOf({ status: 'REJECTED', rejectionReason: 'Reference not found' }))
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    const user = userEvent.setup()
    await user.click((await screen.findAllByText('Rejected'))[0])
    expect(await screen.findByText('Rejection reason: Reference not found')).toBeInTheDocument()
  })

  it('shows an empty state when the member has no payments', async () => {
    mineMock.mockResolvedValue(pageOf([]) as never)
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    expect(await screen.findByText('You have not submitted any payment yet.')).toBeInTheDocument()
  })

  it('shows an error state with a retry when the list cannot be loaded', async () => {
    mineMock.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(pageOf([summaryOf()]) as never)
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="mine" />, { user: MEMBER })
    const retry = await screen.findByRole('button', { name: /retry|try again/i })
    await userEvent.setup().click(retry)
    expect(await screen.findByTestId('payment-status')).toBeInTheDocument()
  })
})

describe('officer payment lists', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    queueMock.mockResolvedValue(pageOf([summaryOf({ id: 'p1', status: 'SUBMITTED' })]) as never)
    allMock.mockResolvedValue(pageOf([summaryOf({ id: 'p2', status: 'VERIFIED' })]) as never)
    paymentMock.mockResolvedValue(paymentOf({ status: 'SUBMITTED' }))
    eventsMock.mockResolvedValue([])
    postingMock.mockResolvedValue({
      paymentId: 'p1',
      status: 'SUBMITTED',
      amount: '3500',
      verificationEvidenceRecorded: false,
      postingAttempts: 0,
      retryable: false,
      postings: [],
    })
  })

  it('lists the review queue with member names and opens the officer detail', async () => {
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="review" />, { user: OFFICER })
    expect(await screen.findByText('Pat Payer')).toBeInTheDocument()
    expect(queueMock).toHaveBeenCalledWith('c1', { page: 0, size: 20 })
    await userEvent.setup().click(screen.getByText('Pat Payer'))
    await waitFor(() => expect(postingMock).toHaveBeenCalledWith('c1', 'p1'))
    expect(mineMock).not.toHaveBeenCalled()
  })

  it('lists verified-but-unposted and posted payments through the status filter', async () => {
    const { unmount } = renderWithApp(<PaymentListPanel cooperativeId="c1" source="verified" />, { user: OFFICER })
    await screen.findByText('Pat Payer')
    expect(allMock).toHaveBeenCalledWith('c1', { page: 0, size: 20, status: 'VERIFIED' })
    unmount()
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="posted" />, { user: OFFICER })
    await waitFor(() => expect(allMock).toHaveBeenLastCalledWith('c1', { page: 0, size: 20, status: 'POSTED' }))
  })

  it('shows empty states per view', async () => {
    queueMock.mockResolvedValue(pageOf([]) as never)
    renderWithApp(<PaymentListPanel cooperativeId="c1" source="review" />, { user: OFFICER })
    expect(await screen.findByText('No payments are waiting for review.')).toBeInTheDocument()
  })
})
