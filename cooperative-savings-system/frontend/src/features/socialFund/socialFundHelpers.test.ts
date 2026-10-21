import { describe, expect, it } from 'vitest'
import {
  canApproveSocialContribution,
  canApproveSocialDisbursement,
  canCancelSocialDisbursement,
  canRejectSocialContribution,
  canRejectSocialDisbursement,
  filterSocialContributionActions,
  filterSocialDisbursementActions,
  pendingSocialApprovalsTotal,
  socialStatusColor,
} from './socialFundHelpers'

describe('socialStatusColor', () => {
  it('maps known statuses to chip colors', () => {
    expect(socialStatusColor('PENDING')).toBe('info')
    expect(socialStatusColor('APPROVED')).toBe('success')
    expect(socialStatusColor('REJECTED')).toBe('error')
    expect(socialStatusColor('CANCELLED')).toBe('secondary')
  })
})

describe('canApproveSocialContribution', () => {
  it('allows admin on PENDING only', () => {
    expect(canApproveSocialContribution('PENDING', true)).toBe(true)
    expect(canApproveSocialContribution('PENDING', false)).toBe(false)
    expect(canApproveSocialContribution('APPROVED', true)).toBe(false)
    expect(canApproveSocialContribution('REJECTED', true)).toBe(false)
  })
})

describe('canRejectSocialContribution', () => {
  it('allows admin on PENDING only', () => {
    expect(canRejectSocialContribution('PENDING', true)).toBe(true)
    expect(canRejectSocialContribution('APPROVED', true)).toBe(false)
    expect(canRejectSocialContribution('PENDING', false)).toBe(false)
  })
})

describe('canApproveSocialDisbursement / canReject / canCancel', () => {
  it('allows admin on PENDING only', () => {
    expect(canApproveSocialDisbursement('PENDING', true)).toBe(true)
    expect(canApproveSocialDisbursement('APPROVED', true)).toBe(false)
    expect(canRejectSocialDisbursement('PENDING', true)).toBe(true)
    expect(canCancelSocialDisbursement('PENDING', true)).toBe(true)
    expect(canCancelSocialDisbursement('CANCELLED', true)).toBe(false)
    expect(canCancelSocialDisbursement('PENDING', false)).toBe(false)
  })
})

describe('filterSocialContributionActions', () => {
  it('returns approve/reject for pending admin', () => {
    expect(filterSocialContributionActions('PENDING', true)).toEqual(['approve', 'reject'])
  })

  it('returns empty for member on pending', () => {
    expect(filterSocialContributionActions('PENDING', false)).toEqual([])
  })
})

describe('filterSocialDisbursementActions', () => {
  it('returns approve/reject/cancel for pending admin', () => {
    expect(filterSocialDisbursementActions('PENDING', true)).toEqual([
      'approve',
      'reject',
      'cancel',
    ])
  })
})

describe('pendingSocialApprovalsTotal', () => {
  it('sums pending counts', () => {
    expect(pendingSocialApprovalsTotal(2, 3)).toBe(5)
    expect(pendingSocialApprovalsTotal(null, undefined)).toBe(0)
  })
})
