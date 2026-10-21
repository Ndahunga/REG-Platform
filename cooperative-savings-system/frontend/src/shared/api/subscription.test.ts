import { describe, expect, it, vi } from 'vitest'
import { fetchSubscription, cooperativeSubscriptionQueryKey } from './subscription'

vi.mock('./cooperatives', () => ({
  fetchCooperativeSubscription: vi.fn(async (id: string) => ({
    id: 's1',
    cooperativeId: id,
    status: 'TRIAL',
    effectiveStatus: 'TRIAL',
    writeAllowed: true,
  })),
}))

describe('subscription API', () => {
  it('loads subscription by cooperative id', async () => {
    await expect(fetchSubscription('coop-1')).resolves.toMatchObject({
      cooperativeId: 'coop-1',
      effectiveStatus: 'TRIAL',
    })
    expect(cooperativeSubscriptionQueryKey('coop-1')[1]).toBe('coop-1')
  })
})
