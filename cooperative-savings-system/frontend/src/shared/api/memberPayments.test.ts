import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  approvePaymentReversal,
  completePaymentEvidence,
  declinePaymentReversal,
  fetchPaymentReversals,
  requestPaymentReversal,
  withdrawPaymentReversal,
  fetchMyPayments,
  fetchPayment,
  fetchPaymentEvents,
  fetchPaymentPosting,
  fetchPaymentReviewQueue,
  fetchPayments,
  postPayment,
  rejectPayment,
  retryPaymentPosting,
  startPaymentReview,
  submitPayment,
  verifyPayment,
} from './memberPayments'

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

const getMock = vi.mocked(apiClient.get)
const postMock = vi.mocked(apiClient.post)
const ok = (data: unknown) => ({ data: { success: true, message: 'ok', data, timestamp: 't' } })

describe('member payments API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue(ok({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true }))
    postMock.mockResolvedValue(ok({ id: 'p1' }))
  })

  it('submits the exact backend payload with an Idempotency-Key, scoped to the scheme', async () => {
    const payload = {
      amount: '3500',
      paymentDate: '2026-10-01',
      paymentMethod: 'MTN_MOMO' as const,
      transactionReference: 'TX1',
      allocations: [
        { type: 'CONTRIBUTION' as const, amount: '2000', periodYear: 2026, periodMonth: 9 },
        { type: 'LOAN_REPAYMENT' as const, amount: '1500', loanId: 'loan-1' },
      ],
    }
    await submitPayment('coop-1', payload, 'key-1')
    expect(postMock).toHaveBeenCalledWith('/cooperatives/coop-1/payments', payload, {
      headers: { 'Idempotency-Key': 'key-1' },
    })
  })

  it('lists with only the supported filters and always in the scheme path', async () => {
    await fetchMyPayments('coop-1', { status: 'POSTED', page: 1, size: 20 })
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/coop-1/payments/mine', {
      params: { status: 'POSTED', page: 1, size: 20 },
    })
    await fetchPaymentReviewQueue('coop-2', { page: 0, size: 20 })
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/coop-2/payments/review-queue', { params: { page: 0, size: 20 } })
    await fetchPayments('coop-3', { status: 'VERIFIED', memberUserId: 'u1' })
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/coop-3/payments', {
      params: { status: 'VERIFIED', memberUserId: 'u1' },
    })
  })

  it('reads a payment, its events and its posting state from the payment path', async () => {
    getMock.mockResolvedValue(ok({ id: 'p1' }))
    await fetchPayment('c', 'p1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1')
    getMock.mockResolvedValue(ok([]))
    expect(await fetchPaymentEvents('c', 'p1')).toEqual([])
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/events')
    getMock.mockResolvedValue(ok({ paymentId: 'p1', postings: [] }))
    await fetchPaymentPosting('c', 'p1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/posting')
  })

  it('sends verification evidence in the V33 shape and never anything else', async () => {
    const evidence = { method: 'MOBILE_MONEY_RECONCILED' as const, basis: 'Matched the MoMo statement', reference: 'MP123' }
    await verifyPayment('c', 'p1', evidence)
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/verify', evidence)
    await completePaymentEvidence('c', 'p1', evidence)
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/verification-evidence', evidence)
  })

  it('has separate verify and post calls and a manual retry', async () => {
    await startPaymentReview('c', 'p1')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/start-review')
    await rejectPayment('c', 'p1', 'Not found in the statement')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/reject', { reason: 'Not found in the statement' })
    await postPayment('c', 'p1')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/post')
    await retryPaymentPosting('c', 'p1')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/retry-posting')
  })

  it('verifying never triggers posting', async () => {
    await verifyPayment('c', 'p1', { method: 'CASH_RECEIPT_CONFIRMED', basis: 'Counted' })
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(String(postMock.mock.calls[0][0])).not.toContain('/post')
  })

  it('requests, lists, approves and declines contribution-only reversals through scheme-scoped paths', async () => {
    await requestPaymentReversal('c', 'p1', 'Paid to the wrong member')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/reversals', { reason: 'Paid to the wrong member' })
    getMock.mockResolvedValue(ok([]))
    expect(await fetchPaymentReversals('c', 'p1')).toEqual([])
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/p1/reversals')
    await approvePaymentReversal('c', 'r1')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/reversals/r1/approve')
    await declinePaymentReversal('c', 'r1', 'Correct as posted')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/reversals/r1/decline', { reason: 'Correct as posted' })
  })

  it('withdraws a pending reversal through its own call and never approves it', async () => {
    await withdrawPaymentReversal('c', 'r1')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/reversals/r1/withdraw', {})
    await withdrawPaymentReversal('c', 'r1', 'Checked')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c/payments/reversals/r1/withdraw', { note: 'Checked' })
    expect(postMock.mock.calls.every((call) => !String(call[0]).includes('/approve'))).toBe(true)
  })

  it('requesting a reversal never approves it', async () => {
    await requestPaymentReversal('c', 'p1', 'Because')
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(String(postMock.mock.calls[0][0])).not.toContain('/approve')
  })
})
