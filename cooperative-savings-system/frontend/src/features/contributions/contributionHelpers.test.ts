import { describe, expect, it } from 'vitest'
import {
  computeOutstandingAmount,
  contributionStatusColor,
  contributionStatusLabelKey,
  contributionTabsForUser,
  deriveContributionStatus,
  isNonNegativeMoney,
  moneyToScaledInt,
} from './contributionHelpers'

describe('contributionStatusColor', () => {
  it('maps known statuses to chip colors', () => {
    expect(contributionStatusColor('PAID')).toBe('success')
    expect(contributionStatusColor('PARTIALLY_PAID')).toBe('warning')
    expect(contributionStatusColor('PENDING')).toBe('info')
    expect(contributionStatusColor('CANCELLED')).toBe('error')
  })
})

describe('contributionStatusLabelKey', () => {
  it('builds i18n key', () => {
    expect(contributionStatusLabelKey('PAID')).toBe('contributions.status.PAID')
  })
})

describe('computeOutstandingAmount', () => {
  it('computes max(expected - paid, 0) without float drift', () => {
    expect(computeOutstandingAmount('1000', '250')).toBe('750')
    expect(computeOutstandingAmount('1000', '1000')).toBe('0')
    expect(computeOutstandingAmount('1000', '1200')).toBe('0')
    expect(computeOutstandingAmount('0.3', '0.1')).toBe('0.2')
  })
})

describe('deriveContributionStatus', () => {
  it('derives status from amounts', () => {
    expect(deriveContributionStatus('1000', '0')).toBe('PENDING')
    expect(deriveContributionStatus('1000', '400')).toBe('PARTIALLY_PAID')
    expect(deriveContributionStatus('1000', '1000')).toBe('PAID')
  })

  it('preserves waived/cancelled', () => {
    expect(deriveContributionStatus('1000', '0', 'WAIVED')).toBe('WAIVED')
  })
})

describe('contributionTabsForUser', () => {
  it('gives officers member self-submit plus operations tabs', () => {
    expect(contributionTabsForUser(true, false)).toEqual([
      'monthly',
      'submit',
      'approvals',
      'history',
      'special',
    ])
  })

  it('hides personal submit for the platform super admin', () => {
    expect(contributionTabsForUser(true, true)).toEqual([
      'monthly',
      'approvals',
      'history',
      'special',
    ])
  })

  it('keeps member self-service tabs for ordinary members', () => {
    expect(contributionTabsForUser(false, false)).toEqual(['submit', 'history', 'special'])
  })

  it('adds a dedicated Share Purchase Approvals tab right after approvals for share reviewers', () => {
    expect(contributionTabsForUser(true, false, true)).toEqual([
      'monthly',
      'submit',
      'approvals',
      'share-approvals',
      'history',
      'special',
    ])
    expect(contributionTabsForUser(true, true, true)).toEqual([
      'monthly',
      'approvals',
      'share-approvals',
      'history',
      'special',
    ])
  })

  it('never offers the share approvals tab to users who cannot review shares', () => {
    expect(contributionTabsForUser(true, false, false)).not.toContain('share-approvals')
    expect(contributionTabsForUser(true, false)).not.toContain('share-approvals')
    expect(contributionTabsForUser(false, false, true)).toEqual(['submit', 'history', 'special'])
  })
})

describe('isNonNegativeMoney', () => {
  it('accepts non-negative decimals', () => {
    expect(isNonNegativeMoney('0')).toBe(true)
    expect(isNonNegativeMoney('12.50')).toBe(true)
    expect(isNonNegativeMoney('-1')).toBe(false)
    expect(isNonNegativeMoney('abc')).toBe(false)
  })
})

describe('moneyToScaledInt', () => {
  it('scales decimals to integer minor units', () => {
    expect(moneyToScaledInt('1.5', 4)).toBe(15000n)
    expect(moneyToScaledInt('-2', 4)).toBe(-20000n)
  })
})
