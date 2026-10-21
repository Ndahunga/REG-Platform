import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  approveRotationPayout,
  cancelRotationPayout,
  confirmRotationDisbursement,
  fetchEntitlements,
  fetchModelSummary,
  fetchMyEntitlements,
  fetchMyRotationPayouts,
  fetchRecoveries,
  fetchRotationPayoutEvents,
  fetchRotationPayouts,
  rejectRotationPayout,
  requestRotationPayout,
} from './rotationPayouts'

vi.mock('./client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }))

const getMock = vi.mocked(apiClient.get)
const postMock = vi.mocked(apiClient.post)
const ok = (data: unknown) => ({ data: { success: true, message: 'ok', data, timestamp: 't' } })

describe('rotation payouts API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue(ok([]))
    postMock.mockResolvedValue(ok({ id: 'po1' }))
  })

  it('reads entitlements, recoveries and payouts from scheme-scoped paths', async () => {
    await fetchEntitlements('c1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/entitlements')
    await fetchMyEntitlements('c1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/entitlements/mine')
    await fetchRecoveries('c1', 'per1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/entitlements/per1/recoveries')
    await fetchRotationPayouts('c1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts')
    await fetchMyRotationPayouts('c1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts/mine')
    await fetchRotationPayoutEvents('c1', 'po1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts/po1/events')
  })

  it('requests a payout with the exact body and an idempotency key', async () => {
    await requestRotationPayout('c1', { rotationPeriodId: 'per1', amount: '300' }, 'key-1')
    expect(postMock).toHaveBeenLastCalledWith(
      '/cooperatives/c1/rotation/payouts',
      { rotationPeriodId: 'per1', amount: '300' },
      { headers: { 'Idempotency-Key': 'key-1' } },
    )
  })

  it('has separate approve, reject, cancel and confirm-disbursement calls', async () => {
    await approveRotationPayout('c1', 'po1')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts/po1/approve')
    await rejectRotationPayout('c1', 'po1', 'No')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts/po1/reject', { reason: 'No' })
    await cancelRotationPayout('c1', 'po1', 'Not needed')
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts/po1/cancel', { reason: 'Not needed' })
    await confirmRotationDisbursement('c1', 'po1', { method: 'BANK_TRANSFER', reference: 'BK-1', note: 'ok' })
    expect(postMock).toHaveBeenLastCalledWith('/cooperatives/c1/rotation/payouts/po1/confirm-disbursement', {
      method: 'BANK_TRANSFER',
      reference: 'BK-1',
      note: 'ok',
    })
  })

  it('approving never confirms a disbursement', async () => {
    await approveRotationPayout('c1', 'po1')
    expect(postMock).toHaveBeenCalledTimes(1)
    expect(String(postMock.mock.calls[0][0])).not.toContain('confirm-disbursement')
  })

  it('reads the model summary from the reports path', async () => {
    getMock.mockResolvedValue(ok({ operatingModel: 'HYBRID' }))
    await fetchModelSummary('c1')
    expect(getMock).toHaveBeenLastCalledWith('/cooperatives/c1/reports/model-summary')
  })
})
