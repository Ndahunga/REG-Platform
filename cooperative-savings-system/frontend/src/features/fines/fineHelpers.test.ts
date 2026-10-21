import { describe, expect, it } from 'vitest'
import {
  canApproveFinePayment,
  canCancelFine,
  canRejectFinePayment,
  canSubmitFinePayment,
  canWaiveFine,
  filterFinePaymentActions,
  finePaymentStatusColor,
  fineStatusColor,
  fineStatusLabelKey,
  isTerminalFineStatus,
  outstandingFineAmount,
} from './fineHelpers'

describe('fineStatusColor', () => {
  it('maps known statuses to chip colors', () => {
    expect(fineStatusColor('UNPAID')).toBe('error')
    expect(fineStatusColor('PARTIALLY_PAID')).toBe('warning')
    expect(fineStatusColor('PAID')).toBe('success')
    expect(fineStatusColor('WAIVED')).toBe('default')
    expect(fineStatusColor('CANCELLED')).toBe('secondary')
  })
})

describe('finePaymentStatusColor', () => {
  it('maps payment statuses', () => {
    expect(finePaymentStatusColor('PENDING')).toBe('info')
    expect(finePaymentStatusColor('APPROVED')).toBe('success')
    expect(finePaymentStatusColor('REJECTED')).toBe('error')
  })
})

describe('fineStatusLabelKey', () => {
  it('builds i18n key', () => {
    expect(fineStatusLabelKey('UNPAID')).toBe('fines.status.UNPAID')
  })
})

describe('canApproveFinePayment', () => {
  it('allows admin on PENDING only', () => {
    expect(canApproveFinePayment('PENDING', true)).toBe(true)
    expect(canApproveFinePayment('PENDING', false)).toBe(false)
    expect(canApproveFinePayment('APPROVED', true)).toBe(false)
    expect(canApproveFinePayment('REJECTED', true)).toBe(false)
  })
})

describe('canRejectFinePayment', () => {
  it('allows admin on PENDING only', () => {
    expect(canRejectFinePayment('PENDING', true)).toBe(true)
    expect(canRejectFinePayment('APPROVED', true)).toBe(false)
    expect(canRejectFinePayment('PENDING', false)).toBe(false)
  })
})

describe('canWaiveFine / canCancelFine', () => {
  it('allows admin on unpaid or partially paid', () => {
    expect(canWaiveFine('UNPAID', true)).toBe(true)
    expect(canWaiveFine('PARTIALLY_PAID', true)).toBe(true)
    expect(canWaiveFine('PAID', true)).toBe(false)
    expect(canCancelFine('UNPAID', true)).toBe(true)
    expect(canCancelFine('WAIVED', true)).toBe(false)
    expect(canCancelFine('UNPAID', false)).toBe(false)
  })
})

describe('canSubmitFinePayment', () => {
  it('allows unpaid or partially paid', () => {
    expect(canSubmitFinePayment('UNPAID')).toBe(true)
    expect(canSubmitFinePayment('PARTIALLY_PAID')).toBe(true)
    expect(canSubmitFinePayment('PAID')).toBe(false)
  })
})

describe('filterFinePaymentActions', () => {
  it('returns approve/reject for pending admin', () => {
    expect(filterFinePaymentActions('PENDING', true)).toEqual(['approve', 'reject'])
  })

  it('returns empty for member on pending', () => {
    expect(filterFinePaymentActions('PENDING', false)).toEqual([])
  })
})

describe('isTerminalFineStatus', () => {
  it('detects terminal statuses', () => {
    expect(isTerminalFineStatus('PAID')).toBe(true)
    expect(isTerminalFineStatus('WAIVED')).toBe(true)
    expect(isTerminalFineStatus('CANCELLED')).toBe(true)
    expect(isTerminalFineStatus('UNPAID')).toBe(false)
  })
})

describe('outstandingFineAmount', () => {
  it('parses outstanding amount', () => {
    expect(outstandingFineAmount('1500')).toBe(1500)
    expect(outstandingFineAmount(null)).toBe(0)
    expect(outstandingFineAmount(undefined)).toBe(0)
  })
})
