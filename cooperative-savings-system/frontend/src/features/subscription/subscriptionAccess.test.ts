import { describe, expect, it } from 'vitest'
import type { CooperativeSubscription } from '@/shared/types/cooperative'
import {
  TRIAL_WARNING_DAYS,
  effectiveSubscriptionStatus,
  isActionNeeded,
  resolveSubscriptionAccess,
} from './subscriptionAccess'

const trial: CooperativeSubscription = {
  id: 's1',
  cooperativeId: 'coop-1',
  status: 'TRIAL',
  storedStatus: 'TRIAL',
  effectiveStatus: 'TRIAL',
  writeAllowed: true,
  usable: true,
  trialEndsAt: '2027-01-16T10:00:00Z',
  daysRemaining: 120,
}

describe('subscriptionAccess', () => {
  it('uses effectiveStatus for access decisions', () => {
    expect(effectiveSubscriptionStatus({ ...trial, effectiveStatus: 'EXPIRED', status: 'TRIAL' })).toBe(
      'EXPIRED',
    )
  })

  it('does not put SUPER_ADMIN into read-only mode', () => {
    const access = resolveSubscriptionAccess({
      subscription: { ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false },
      isPending: false,
      isSuperAdmin: true,
      hasCooperative: true,
    })
    expect(access.readOnly).toBe(false)
    expect(access.canWrite).toBe(true)
  })

  it('marks expired cooperatives read-only for members', () => {
    const access = resolveSubscriptionAccess({
      subscription: { ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false },
      isPending: false,
      isSuperAdmin: false,
      hasCooperative: true,
    })
    expect(access.readOnly).toBe(true)
    expect(access.canWrite).toBe(false)
  })

  it('treats missing subscription as NONE/restricted after load', () => {
    const access = resolveSubscriptionAccess({
      subscription: null,
      isPending: false,
      isSuperAdmin: false,
      hasCooperative: true,
    })
    expect(access.effectiveStatus).toBe('NONE')
    expect(access.readOnly).toBe(true)
  })

  it('flags trial nearing expiry as action needed', () => {
    expect(isActionNeeded('TRIAL', TRIAL_WARNING_DAYS)).toBe(true)
    expect(isActionNeeded('TRIAL', TRIAL_WARNING_DAYS + 1)).toBe(false)
    expect(isActionNeeded('EXPIRED')).toBe(true)
    expect(isActionNeeded('NONE')).toBe(true)
  })
})
