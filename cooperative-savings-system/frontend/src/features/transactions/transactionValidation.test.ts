import { describe, expect, it } from 'vitest'
import { firstMessage, kigaliDay } from '@/test/schemaHelpers'
import { transactionCreateDefaults, transactionCreateSchema } from './transactionFormSchemas'

describe('transactionCreateSchema', () => {
  const valid = { ...transactionCreateDefaults(), category: 'GENERAL_EXPENSE' as const, amount: '15000' }

  it('defaults the transaction date to today (Kigali) and accepts a valid transaction', async () => {
    expect(transactionCreateDefaults().transactionDate).toBe(kigaliDay(0))
    expect(await firstMessage(transactionCreateSchema, valid)).toBeNull()
  })

  it.each([
    ['', 'Amount is required'],
    ['0', 'Amount must be greater than 0'],
    ['0.001', 'Amount must be at least 0.01'],
    ['-100', 'Amount cannot be negative'],
    ['12abc', 'Enter a valid amount'],
    ['1.23456', 'Use at most 4 decimal places'],
  ])('rejects the amount "%s"', async (amount, message) => {
    expect(await firstMessage(transactionCreateSchema, { ...valid, amount })).toBe(message)
  })

  it('requires a category', async () => {
    expect(await firstMessage(transactionCreateSchema, { ...valid, category: '' })).toBe('Select a category')
  })

  it('requires a real, non-future transaction date (backend @PastOrPresent)', async () => {
    expect(await firstMessage(transactionCreateSchema, { ...valid, transactionDate: '' })).toBe(
      'Transaction date is required',
    )
    expect(await firstMessage(transactionCreateSchema, { ...valid, transactionDate: kigaliDay(2) })).toBe(
      'Transaction date cannot be in the future',
    )
    expect(await firstMessage(transactionCreateSchema, { ...valid, transactionDate: '2026-13-01' })).toBe(
      'Enter a valid transaction date',
    )
  })

  it('requires credit/debit only for an adjustment', async () => {
    expect(await firstMessage(transactionCreateSchema, { ...valid, category: 'ADJUSTMENT', ledgerEffect: '' })).toBe(
      'Select credit or debit',
    )
    expect(await firstMessage(transactionCreateSchema, { ...valid, category: 'ADJUSTMENT', ledgerEffect: 'DEBIT' })).toBeNull()
  })

  it('limits reference, description and notes', async () => {
    expect(await firstMessage(transactionCreateSchema, { ...valid, reference: 'r'.repeat(129) })).not.toBeNull()
    expect(await firstMessage(transactionCreateSchema, { ...valid, description: 'd'.repeat(2001) })).not.toBeNull()
    expect(await firstMessage(transactionCreateSchema, { ...valid, notes: 'n'.repeat(2001) })).not.toBeNull()
  })
})
