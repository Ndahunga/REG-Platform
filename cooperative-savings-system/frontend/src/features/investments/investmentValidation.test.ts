import { describe, expect, it } from 'vitest'
import { allMessages, firstMessage, kigaliDay } from '@/test/schemaHelpers'
import {
  investmentCreateDefaults,
  investmentCreateSchema,
  investmentReturnDefaults,
  investmentReturnSchema,
} from './investmentFormSchemas'

describe('investmentCreateSchema', () => {
  const valid = { ...investmentCreateDefaults, name: 'Land plot', amount: '1000000' }

  it('accepts a valid investment with optional fields blank', async () => {
    expect(await firstMessage(investmentCreateSchema, valid)).toBeNull()
  })

  it.each([
    ['', 'Amount is required'],
    ['0', 'Amount must be greater than 0'],
    ['0.001', 'Amount must be at least 0.01'],
    ['-5', 'Amount cannot be negative'],
    ['1e6', 'Enter a valid amount'],
  ])('rejects the amount "%s"', async (amount, message) => {
    expect(await firstMessage(investmentCreateSchema, { ...valid, amount })).toBe(message)
  })

  it('requires a name', async () => {
    expect(await firstMessage(investmentCreateSchema, { ...valid, name: '' })).toBe('Name is required')
  })

  it('accepts an expected return of zero and rejects invalid amounts', async () => {
    expect(await firstMessage(investmentCreateSchema, { ...valid, expectedReturnAmount: '0' })).toBeNull()
    expect(await firstMessage(investmentCreateSchema, { ...valid, expectedReturnAmount: '-1' })).toBe(
      'Expected return amount cannot be negative',
    )
  })

  it('rejects an expected return date in the past (backend @FutureOrPresent) but allows today and later', async () => {
    expect(await firstMessage(investmentCreateSchema, { ...valid, expectedReturnDate: kigaliDay(-1) })).toBe(
      'Expected return date cannot be in the past',
    )
    expect(await firstMessage(investmentCreateSchema, { ...valid, expectedReturnDate: kigaliDay(0) })).toBeNull()
    expect(await firstMessage(investmentCreateSchema, { ...valid, expectedReturnDate: kigaliDay(90) })).toBeNull()
    expect(await firstMessage(investmentCreateSchema, { ...valid, expectedReturnDate: '2026-02-31' })).toBe(
      'Enter a valid expected return date',
    )
  })
})

describe('investmentReturnSchema', () => {
  const valid = { ...investmentReturnDefaults(), capitalPortion: '5000' }

  it('defaults the return date to today and accepts a valid return', async () => {
    expect(investmentReturnDefaults().returnDate).toBe(kigaliDay(0))
    expect(await firstMessage(investmentReturnSchema, valid)).toBeNull()
    expect(await firstMessage(investmentReturnSchema, { ...valid, capitalPortion: '', profitPortion: '250' })).toBeNull()
  })

  it('requires at least one positive portion and reports it on a visible field', async () => {
    const schemaErrors = async (value: object) => {
      try {
        await investmentReturnSchema.validate(value, { abortEarly: false })
        return []
      } catch (error) {
        return (error as { inner: { path: string; message: string }[] }).inner
      }
    }
    const errors = await schemaErrors({ ...valid, capitalPortion: '', profitPortion: '' })
    expect(errors).toEqual([
      expect.objectContaining({
        path: 'capitalPortion',
        message: 'Enter a capital and/or profit portion greater than 0',
      }),
    ])
    expect(await allMessages(investmentReturnSchema, { ...valid, capitalPortion: '0', profitPortion: '0' })).not.toEqual([])
  })

  it('rejects a future or missing return date', async () => {
    expect(await firstMessage(investmentReturnSchema, { ...valid, returnDate: kigaliDay(3) })).toBe(
      'Return date cannot be in the future',
    )
    expect(await firstMessage(investmentReturnSchema, { ...valid, returnDate: '' })).toBe('Return date is required')
  })

  it('rejects negative or over-precise portions', async () => {
    expect(await allMessages(investmentReturnSchema, { ...valid, capitalPortion: '-5' })).toContain(
      'Capital portion cannot be negative',
    )
    expect(await allMessages(investmentReturnSchema, { ...valid, profitPortion: '1.00001' })).toContain(
      'Use at most 4 decimal places',
    )
  })
})
