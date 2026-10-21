import { describe, expect, it } from 'vitest'
import {
  canCancelPayout,
  canConfirmPayout,
  canMarkPaidPayout,
  filterPayoutActions,
  formatPayoutPercentage,
  isTerminalPayoutStatus,
  payoutStatusColor,
  payoutStatusLabelKey,
  sumPayoutAmounts,
} from './payoutHelpers'

describe('payoutStatusColor', () => {
  it('maps known statuses to chip colors', () => {
    expect(payoutStatusColor('DRAFT')).toBe('default')
    expect(payoutStatusColor('PREVIEWED')).toBe('info')
    expect(payoutStatusColor('CONFIRMED')).toBe('warning')
    expect(payoutStatusColor('PAID')).toBe('success')
    expect(payoutStatusColor('CANCELLED')).toBe('secondary')
    expect(payoutStatusColor('PENDING')).toBe('info')
  })
})

describe('payoutStatusLabelKey', () => {
  it('builds i18n key', () => {
    expect(payoutStatusLabelKey('PREVIEWED')).toBe('payouts.status.PREVIEWED')
  })
})

describe('canConfirmPayout', () => {
  it('allows admin on PREVIEWED only', () => {
    expect(canConfirmPayout('PREVIEWED', true)).toBe(true)
    expect(canConfirmPayout('PREVIEWED', false)).toBe(false)
    expect(canConfirmPayout('CONFIRMED', true)).toBe(false)
    expect(canConfirmPayout('DRAFT', true)).toBe(false)
  })
})

describe('canMarkPaidPayout', () => {
  it('allows admin on CONFIRMED only', () => {
    expect(canMarkPaidPayout('CONFIRMED', true)).toBe(true)
    expect(canMarkPaidPayout('CONFIRMED', false)).toBe(false)
    expect(canMarkPaidPayout('PREVIEWED', true)).toBe(false)
    expect(canMarkPaidPayout('PAID', true)).toBe(false)
  })
})

describe('canCancelPayout', () => {
  it('allows admin on DRAFT or PREVIEWED', () => {
    expect(canCancelPayout('DRAFT', true)).toBe(true)
    expect(canCancelPayout('PREVIEWED', true)).toBe(true)
    expect(canCancelPayout('CONFIRMED', true)).toBe(false)
    expect(canCancelPayout('PREVIEWED', false)).toBe(false)
  })
})

describe('filterPayoutActions', () => {
  it('returns confirm/cancel for previewed admin', () => {
    expect(filterPayoutActions('PREVIEWED', true)).toEqual(['confirm', 'cancel'])
  })

  it('returns markPaid for confirmed admin', () => {
    expect(filterPayoutActions('CONFIRMED', true)).toEqual(['markPaid'])
  })

  it('returns cancel for draft admin', () => {
    expect(filterPayoutActions('DRAFT', true)).toEqual(['cancel'])
  })

  it('returns empty for member', () => {
    expect(filterPayoutActions('PREVIEWED', false)).toEqual([])
    expect(filterPayoutActions('CONFIRMED', false)).toEqual([])
  })

  it('returns empty for terminal statuses', () => {
    expect(filterPayoutActions('PAID', true)).toEqual([])
    expect(filterPayoutActions('CANCELLED', true)).toEqual([])
  })
})

describe('isTerminalPayoutStatus', () => {
  it('detects terminal statuses', () => {
    expect(isTerminalPayoutStatus('PAID')).toBe(true)
    expect(isTerminalPayoutStatus('CANCELLED')).toBe(true)
    expect(isTerminalPayoutStatus('CONFIRMED')).toBe(false)
  })
})

describe('formatPayoutPercentage', () => {
  it('formats percentage without trailing zeros', () => {
    expect(formatPayoutPercentage('12.5000')).toBe('12.5%')
    expect(formatPayoutPercentage(100)).toBe('100%')
    expect(formatPayoutPercentage(null)).toBe('—')
  })
})

describe('sumPayoutAmounts', () => {
  it('sums line payout amounts', () => {
    expect(
      sumPayoutAmounts([{ payoutAmount: '100' }, { payoutAmount: 50.5 }, { payoutAmount: null }]),
    ).toBe(150.5)
    expect(sumPayoutAmounts([])).toBe(0)
  })
})
