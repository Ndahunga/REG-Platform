import { describe, expect, it } from 'vitest'
import {
  assertOfflineBlocksMutation,
  OFFLINE_FINANCE_MESSAGE,
} from './useFinancialSubmitGuard'

describe('assertOfflineBlocksMutation', () => {
  it('blocks mutating methods when offline', () => {
    expect(assertOfflineBlocksMutation('post', false)).toBe(OFFLINE_FINANCE_MESSAGE)
    expect(assertOfflineBlocksMutation('PUT', false)).toBe(OFFLINE_FINANCE_MESSAGE)
    expect(assertOfflineBlocksMutation('patch', false)).toBe(OFFLINE_FINANCE_MESSAGE)
    expect(assertOfflineBlocksMutation('DELETE', false)).toBe(OFFLINE_FINANCE_MESSAGE)
  })

  it('allows reads when offline and all methods when online', () => {
    expect(assertOfflineBlocksMutation('get', false)).toBeNull()
    expect(assertOfflineBlocksMutation('post', true)).toBeNull()
    expect(assertOfflineBlocksMutation('delete', true)).toBeNull()
  })
})
