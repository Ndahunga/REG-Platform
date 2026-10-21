import { describe, expect, it } from 'vitest'
import {
  canApproveTransaction,
  canRejectTransaction,
  filterTransactionActions,
  isApprovedTransaction,
  matchesTransactionBucket,
  transactionCategoryColor,
  transactionStatusColor,
} from './transactionHelpers'

describe('transactionStatusColor', () => {
  it('maps statuses', () => {
    expect(transactionStatusColor('PENDING')).toBe('info')
    expect(transactionStatusColor('APPROVED')).toBe('success')
    expect(transactionStatusColor('REJECTED')).toBe('error')
  })
})

describe('transactionCategoryColor', () => {
  it('maps categories', () => {
    expect(transactionCategoryColor('OTHER_INCOME')).toBe('success')
    expect(transactionCategoryColor('GENERAL_EXPENSE')).toBe('warning')
    expect(transactionCategoryColor('INTEREST_EXPENSE')).toBe('error')
    expect(transactionCategoryColor('ADJUSTMENT')).toBe('secondary')
  })
})

describe('canApproveTransaction / canRejectTransaction', () => {
  it('allows admin on PENDING only', () => {
    expect(canApproveTransaction('PENDING', true)).toBe(true)
    expect(canRejectTransaction('PENDING', true)).toBe(true)
    expect(canApproveTransaction('APPROVED', true)).toBe(false)
    expect(canApproveTransaction('PENDING', false)).toBe(false)
  })
})

describe('filterTransactionActions', () => {
  it('returns approve/reject for pending admin', () => {
    expect(filterTransactionActions('PENDING', true)).toEqual(['approve', 'reject'])
  })

  it('returns empty for approved or members', () => {
    expect(filterTransactionActions('APPROVED', true)).toEqual([])
    expect(filterTransactionActions('PENDING', false)).toEqual([])
  })
})

describe('isApprovedTransaction', () => {
  it('detects approved', () => {
    expect(isApprovedTransaction('APPROVED')).toBe(true)
    expect(isApprovedTransaction('PENDING')).toBe(false)
  })
})

describe('matchesTransactionBucket', () => {
  it('filters income and expenses', () => {
    expect(matchesTransactionBucket('OTHER_INCOME', 'income')).toBe(true)
    expect(matchesTransactionBucket('GENERAL_EXPENSE', 'income')).toBe(false)
    expect(matchesTransactionBucket('INTEREST_EXPENSE', 'expenses')).toBe(true)
    expect(matchesTransactionBucket('ADJUSTMENT', 'all')).toBe(true)
  })
})
