import { describe, expect, it } from 'vitest'
import {
  canActivateInvestment,
  canCancelInvestment,
  canRecordInvestmentLoss,
  canRecordInvestmentReturn,
  filterInvestmentActions,
  investmentStatusColor,
  investmentStatusLabelKey,
  isTerminalInvestmentStatus,
  remainingInvestmentCapital,
} from './investmentHelpers'

describe('investmentStatusColor', () => {
  it('maps known statuses to chip colors', () => {
    expect(investmentStatusColor('PLANNED')).toBe('info')
    expect(investmentStatusColor('ACTIVE')).toBe('success')
    expect(investmentStatusColor('PARTIALLY_RETURNED')).toBe('warning')
    expect(investmentStatusColor('COMPLETED')).toBe('primary')
    expect(investmentStatusColor('CANCELLED')).toBe('secondary')
    expect(investmentStatusColor('LOSS_RECORDED')).toBe('error')
  })
})

describe('investmentStatusLabelKey', () => {
  it('builds i18n key', () => {
    expect(investmentStatusLabelKey('ACTIVE')).toBe('investments.status.ACTIVE')
  })
})

describe('canActivateInvestment', () => {
  it('allows admin on PLANNED only', () => {
    expect(canActivateInvestment('PLANNED', true)).toBe(true)
    expect(canActivateInvestment('PLANNED', false)).toBe(false)
    expect(canActivateInvestment('ACTIVE', true)).toBe(false)
  })
})

describe('canCancelInvestment', () => {
  it('allows admin on PLANNED or ACTIVE', () => {
    expect(canCancelInvestment('PLANNED', true)).toBe(true)
    expect(canCancelInvestment('ACTIVE', true)).toBe(true)
    expect(canCancelInvestment('COMPLETED', true)).toBe(false)
    expect(canCancelInvestment('PLANNED', false)).toBe(false)
  })
})

describe('canRecordInvestmentReturn / canRecordInvestmentLoss', () => {
  it('allows admin on ACTIVE or PARTIALLY_RETURNED', () => {
    expect(canRecordInvestmentReturn('ACTIVE', true)).toBe(true)
    expect(canRecordInvestmentReturn('PARTIALLY_RETURNED', true)).toBe(true)
    expect(canRecordInvestmentReturn('PLANNED', true)).toBe(false)
    expect(canRecordInvestmentLoss('ACTIVE', true)).toBe(true)
    expect(canRecordInvestmentLoss('PARTIALLY_RETURNED', false)).toBe(false)
  })
})

describe('filterInvestmentActions', () => {
  it('returns activate/cancel for planned admin', () => {
    expect(filterInvestmentActions('PLANNED', true)).toEqual(['activate', 'cancel'])
  })

  it('returns return/loss/cancel for active admin', () => {
    expect(filterInvestmentActions('ACTIVE', true)).toEqual([
      'cancel',
      'recordReturn',
      'recordLoss',
    ])
  })

  it('returns return/loss for partially returned admin', () => {
    expect(filterInvestmentActions('PARTIALLY_RETURNED', true)).toEqual([
      'recordReturn',
      'recordLoss',
    ])
  })

  it('returns empty for member', () => {
    expect(filterInvestmentActions('PLANNED', false)).toEqual([])
    expect(filterInvestmentActions('ACTIVE', false)).toEqual([])
  })

  it('returns empty for terminal statuses', () => {
    expect(filterInvestmentActions('COMPLETED', true)).toEqual([])
    expect(filterInvestmentActions('CANCELLED', true)).toEqual([])
    expect(filterInvestmentActions('LOSS_RECORDED', true)).toEqual([])
  })
})

describe('isTerminalInvestmentStatus', () => {
  it('detects terminal statuses', () => {
    expect(isTerminalInvestmentStatus('COMPLETED')).toBe(true)
    expect(isTerminalInvestmentStatus('CANCELLED')).toBe(true)
    expect(isTerminalInvestmentStatus('LOSS_RECORDED')).toBe(true)
    expect(isTerminalInvestmentStatus('ACTIVE')).toBe(false)
  })
})

describe('remainingInvestmentCapital', () => {
  it('parses remaining capital', () => {
    expect(remainingInvestmentCapital('1500.5')).toBe(1500.5)
    expect(remainingInvestmentCapital(null)).toBe(0)
  })
})
