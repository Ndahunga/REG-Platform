import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  billingPlansQueryKey,
  fetchBillingPlans,
  fetchSubscriptionPayment,
  fetchSubscriptionPayments,
  startBillingCheckout,
} from './billing'

vi.mock('./client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const getMock = vi.mocked(apiClient.get)
const postMock = vi.mocked(apiClient.post)

describe('billing API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads plans keyed by cooperative id', async () => {
    getMock.mockResolvedValue({
      data: {
        success: true,
        message: 'ok',
        data: {
          currency: 'RWF',
          trialMonths: 4,
          plans: [
            {
              billingCycle: 'MONTHLY',
              listPrice: '2000.0000',
              amount: '2000.0000',
              discountPercent: 0,
              savings: '0',
              periodMonths: 1,
            },
          ],
        },
      },
    })
    await expect(fetchBillingPlans('coop-1')).resolves.toMatchObject({
      currency: 'RWF',
      trialMonths: 4,
    })
    expect(getMock).toHaveBeenCalledWith('/cooperatives/coop-1/billing/plans')
    expect(billingPlansQueryKey('coop-1')[1]).toBe('coop-1')
  })

  it('loads payment history for the requested cooperative', async () => {
    getMock.mockResolvedValue({
      data: {
        success: true,
        message: 'ok',
        data: {
          content: [
            {
              id: 'pay-1',
              billingCycle: 'MONTHLY',
              paymentChannel: 'CARD',
              status: 'FAILED',
              currency: 'RWF',
              amount: '2000.0000',
              initiatedAt: '2026-09-01T00:00:00Z',
            },
          ],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          first: true,
          last: true,
        },
      },
    })
    const page = await fetchSubscriptionPayments('coop-1', { page: 0, size: 20 })
    expect(page.content[0]?.id).toBe('pay-1')
    expect(getMock).toHaveBeenCalledWith('/cooperatives/coop-1/billing/payments', {
      params: { page: 0, size: 20 },
    })
  })

  it('posts checkout without an amount and returns the pending payment', async () => {
    postMock.mockResolvedValue({
      data: {
        success: true,
        data: {
          paymentId: 'pay-1',
          status: 'PENDING',
          billingCycle: 'ANNUAL',
          paymentChannel: 'MTN_MOMO',
          amount: '18000.0000',
          currency: 'RWF',
          message: 'Payment request sent. Approve the payment on your phone.',
        },
      },
    })
    await expect(
      startBillingCheckout('coop-1', {
        billingCycle: 'ANNUAL',
        paymentChannel: 'MTN_MOMO',
        payerPhoneNumber: '0781234567',
      }),
    ).resolves.toMatchObject({ paymentId: 'pay-1', status: 'PENDING' })
    expect(postMock).toHaveBeenCalledWith('/cooperatives/coop-1/billing/checkout', {
      billingCycle: 'ANNUAL',
      paymentChannel: 'MTN_MOMO',
      payerPhoneNumber: '0781234567',
    })
    const sent = postMock.mock.calls[0]?.[1] as Record<string, unknown>
    expect(sent).not.toHaveProperty('amount')
    expect(sent).not.toHaveProperty('price')
    expect(sent).not.toHaveProperty('discount')
  })

  it('posts CARD checkout without card fields and keeps checkoutUrl', async () => {
    postMock.mockResolvedValue({
      data: {
        success: true,
        data: {
          paymentId: 'pay-card',
          status: 'PENDING',
          billingCycle: 'MONTHLY',
          paymentChannel: 'CARD',
          amount: '2000.0000',
          currency: 'RWF',
          checkoutUrl: 'https://checkout.flutterwave.com/pay/x',
          message: 'Continue to secure card payment.',
        },
      },
    })
    await expect(
      startBillingCheckout('coop-1', { billingCycle: 'MONTHLY', paymentChannel: 'CARD' }),
    ).resolves.toMatchObject({
      paymentId: 'pay-card',
      checkoutUrl: 'https://checkout.flutterwave.com/pay/x',
    })
    const sent = postMock.mock.calls[0]?.[1] as Record<string, unknown>
    expect(sent).not.toHaveProperty('cardNumber')
    expect(sent).not.toHaveProperty('cvv')
    expect(sent).not.toHaveProperty('amount')
  })

  it('parses payment id from Flutterwave tx_ref hint', async () => {
    const { paymentIdFromTxRef } = await import('./billing')
    expect(paymentIdFromTxRef('ouwealth-sub-11111111-1111-4111-8111-111111111111')).toBe(
      '11111111-1111-4111-8111-111111111111',
    )
    expect(paymentIdFromTxRef('other-ref')).toBeNull()
  })

  it('loads a single payment for status refresh', async () => {
    getMock.mockResolvedValue({
      data: {
        success: true,
        data: {
          id: 'pay-1',
          billingCycle: 'MONTHLY',
          paymentChannel: 'MTN_MOMO',
          status: 'PENDING',
          currency: 'RWF',
          amount: '2000.0000',
          initiatedAt: '2026-09-01T00:00:00Z',
        },
      },
    })
    const page = await fetchSubscriptionPayment('coop-1', 'pay-1')
    expect(page.id).toBe('pay-1')
    expect(page.status).toBe('PENDING')
    expect(getMock).toHaveBeenCalledWith('/cooperatives/coop-1/billing/payments/pay-1')
  })
})
