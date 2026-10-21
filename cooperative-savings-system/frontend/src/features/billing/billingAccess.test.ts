import { describe, expect, it } from 'vitest'
import {
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
  ROLE_VICE_PRESIDENT,
} from '@/shared/types/auth'
import { billingCtaKey, canManageBilling } from './billingAccess'

describe('canManageBilling', () => {
  it('allows president, accountant, and super admin', () => {
    expect(canManageBilling([ROLE_PRESIDENT])).toBe(true)
    expect(canManageBilling([ROLE_VICE_PRESIDENT])).toBe(true)
    expect(canManageBilling([ROLE_ACCOUNTANT])).toBe(true)
    expect(canManageBilling([ROLE_SUPER_ADMIN])).toBe(true)
  })

  it('denies member, secretary, and loan officer', () => {
    expect(canManageBilling([ROLE_MEMBER])).toBe(false)
    expect(canManageBilling([ROLE_SECRETARY])).toBe(false)
    expect(canManageBilling([ROLE_LOAN_OFFICER])).toBe(false)
  })
})

describe('billingCtaKey', () => {
  it('maps subscription status to dashboard actions', () => {
    expect(billingCtaKey('TRIAL')).toBe('subscription.dashboard.viewPlans')
    expect(billingCtaKey('ACTIVE')).toBe('subscription.dashboard.manage')
    expect(billingCtaKey('PAST_DUE')).toBe('subscription.dashboard.review')
    expect(billingCtaKey('EXPIRED')).toBe('subscription.dashboard.renew')
    expect(billingCtaKey('NONE')).toBe('subscription.dashboard.choosePlan')
  })
})
