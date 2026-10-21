import { describe, expect, it } from 'vitest'
import {
  canShowApprove,
  canShowDisburse,
  canShowReject,
  canShowRepayment,
  canShowWriteOff,
  filterLoanActions,
  isTerminalLoanStatus,
  loanStatusColor,
  loanStatusLabelKey,
  outstandingTotal,
} from './loanHelpers'

describe('loanStatusColor', () => {
  it('maps known statuses to chip colors', () => {
    expect(loanStatusColor('PENDING')).toBe('info')
    expect(loanStatusColor('AWAITING_SECOND_APPROVAL')).toBe('warning')
    expect(loanStatusColor('APPROVED')).toBe('primary')
    expect(loanStatusColor('ACTIVE')).toBe('success')
    expect(loanStatusColor('OVERDUE')).toBe('warning')
    expect(loanStatusColor('REJECTED')).toBe('error')
    expect(loanStatusColor('CLOSED')).toBe('secondary')
    expect(loanStatusColor('WRITTEN_OFF')).toBe('default')
  })
})

describe('loanStatusLabelKey', () => {
  it('builds i18n key', () => {
    expect(loanStatusLabelKey('PENDING')).toBe('loans.status.PENDING')
  })
})

describe('canShowApprove', () => {
  it('allows admin on PENDING only', () => {
    expect(canShowApprove('PENDING', true)).toBe(true)
    expect(canShowApprove('AWAITING_SECOND_APPROVAL', true)).toBe(true)
    expect(canShowApprove('PENDING', false)).toBe(false)
    expect(canShowApprove('APPROVED', true)).toBe(false)
  })
})

describe('canShowReject', () => {
  it('allows admin on PENDING only', () => {
    expect(canShowReject('PENDING', true)).toBe(true)
    expect(canShowReject('ACTIVE', true)).toBe(false)
  })
})

describe('canShowDisburse', () => {
  it('allows admin on APPROVED only', () => {
    expect(canShowDisburse('APPROVED', true)).toBe(true)
    expect(canShowDisburse('PENDING', true)).toBe(false)
    expect(canShowDisburse('APPROVED', false)).toBe(false)
  })
})

describe('canShowWriteOff / canShowRepayment', () => {
  it('allows admin on ACTIVE or OVERDUE', () => {
    expect(canShowWriteOff('ACTIVE', true)).toBe(true)
    expect(canShowWriteOff('OVERDUE', true)).toBe(true)
    expect(canShowWriteOff('CLOSED', true)).toBe(false)
    expect(canShowRepayment('ACTIVE', true)).toBe(true)
    expect(canShowRepayment('OVERDUE', false)).toBe(false)
  })
})

describe('filterLoanActions', () => {
  it('returns approve/reject for pending admin', () => {
    expect(filterLoanActions('PENDING', true)).toEqual(['approve', 'reject'])
  })

  it('returns empty for member on pending', () => {
    expect(filterLoanActions('PENDING', false)).toEqual([])
  })
})

describe('isTerminalLoanStatus', () => {
  it('detects terminal statuses', () => {
    expect(isTerminalLoanStatus('CLOSED')).toBe(true)
    expect(isTerminalLoanStatus('REJECTED')).toBe(true)
    expect(isTerminalLoanStatus('ACTIVE')).toBe(false)
  })
})

describe('outstandingTotal', () => {
  it('sums principal and interest', () => {
    expect(outstandingTotal('1000', '50')).toBe(1050)
    expect(outstandingTotal(null, undefined)).toBe(0)
  })
})
