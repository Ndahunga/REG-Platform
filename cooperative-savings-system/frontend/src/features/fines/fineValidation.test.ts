import { describe, expect, it } from 'vitest'
import { allMessages, firstMessage, kigaliDay } from '@/test/schemaHelpers'
import {
  finePaymentSchema,
  fineIssueDefaults,
  fineIssueSchema,
  toFineCreatePayload,
} from './fineFormSchemas'

const fixed = (overrides = {}) => ({
  ...fineIssueDefaults,
  memberUserId: 'm1',
  calculationMode: 'FIXED' as const,
  amount: '5000',
  reason: 'Late payment',
  ...overrides,
})

const progressive = (overrides = {}) => ({
  ...fineIssueDefaults,
  memberUserId: 'm1',
  calculationMode: 'PROGRESSIVE' as const,
  baseAmount: '1000',
  reason: 'Late payment',
  ...overrides,
})

describe('fineIssueSchema', () => {
  it('accepts valid fixed and progressive fines', async () => {
    expect(await firstMessage(fineIssueSchema, fixed())).toBeNull()
    expect(await firstMessage(fineIssueSchema, progressive({ dailyIncrement: '100', overdueDays: '3' }))).toBeNull()
  })

  it.each([
    ['', 'Amount is required'],
    ['0', 'Amount must be at least 0.01'],
    ['0.001', 'Amount must be at least 0.01'],
    ['abc', 'Enter a valid amount'],
    ['1.23456', 'Use at most 4 decimal places'],
  ])('rejects a fixed amount of "%s"', async (amount, message) => {
    expect(await firstMessage(fineIssueSchema, fixed({ amount }))).toBe(message)
  })

  it('requires a member and a reason', async () => {
    expect(await firstMessage(fineIssueSchema, fixed({ memberUserId: '' }))).toBe('Select a member')
    expect(await firstMessage(fineIssueSchema, fixed({ reason: '' }))).toBe('Reason is required')
  })

  it('rejects a progressive fine that adds up to zero', async () => {
    const zeroes = [
      { baseAmount: '0', dailyIncrement: '0', overdueDays: '5' },
      { baseAmount: '0', dailyIncrement: '100', overdueDays: '0' },
      { baseAmount: '0', dailyIncrement: '100', overdueDays: '' },
    ]
    for (const overrides of zeroes) {
      expect(await allMessages(fineIssueSchema, progressive(overrides))).toContain(
        'The fine total must be greater than 0',
      )
    }
    expect(await firstMessage(fineIssueSchema, progressive({ baseAmount: '0', dailyIncrement: '100', overdueDays: '2' }))).toBeNull()
  })

  it('leaves a zero base with a blank increment to the backend (it uses the cooperative setting)', async () => {
    expect(await firstMessage(fineIssueSchema, progressive({ baseAmount: '0', dailyIncrement: '' }))).toBeNull()
  })

  it('validates overdue days as a whole number', async () => {
    for (const overdueDays of ['-1', '1.5', 'abc', '100001']) {
      expect(await firstMessage(fineIssueSchema, progressive({ overdueDays }))).not.toBeNull()
    }
  })

  it('rejects a future issued date and a due date before the issued date', async () => {
    expect(await firstMessage(fineIssueSchema, fixed({ issuedDate: kigaliDay(3) }))).toBe(
      'Issued date cannot be in the future',
    )
    expect(
      await firstMessage(fineIssueSchema, fixed({ issuedDate: kigaliDay(-1), dueDate: kigaliDay(-5) })),
    ).toBe('Due date must be on or after the issued date')
    expect(
      await firstMessage(fineIssueSchema, fixed({ issuedDate: kigaliDay(-1), dueDate: kigaliDay(10) })),
    ).toBeNull()
  })

  it('keeps both dates optional', async () => {
    expect(await firstMessage(fineIssueSchema, fixed({ issuedDate: '', dueDate: '' }))).toBeNull()
    expect(toFineCreatePayload(fixed()).issuedDate).toBeUndefined()
  })
})

describe('finePaymentSchema', () => {
  const payment = (overrides = {}) => ({
    amount: '1000',
    paymentDate: kigaliDay(0),
    paymentMethod: 'CASH' as const,
    paymentMethodDetail: '',
    paymentReference: '',
    notes: '',
    evidenceFileKey: '',
    ...overrides,
  })

  it('accepts a valid payment up to the outstanding amount', async () => {
    expect(await firstMessage(finePaymentSchema(5000), payment())).toBeNull()
    expect(await firstMessage(finePaymentSchema(1000), payment())).toBeNull()
  })

  it('rejects zero, over-precise and above-outstanding amounts', async () => {
    expect(await firstMessage(finePaymentSchema(5000), payment({ amount: '0' }))).toBe('Amount must be greater than 0')
    expect(await firstMessage(finePaymentSchema(5000), payment({ amount: '0.001' }))).toBe('Amount must be at least 0.01')
    expect(await firstMessage(finePaymentSchema(500), payment({ amount: '1000' }))).toBe('Amount cannot exceed outstanding')
  })

  it('requires a real, non-future payment date', async () => {
    expect(await firstMessage(finePaymentSchema(5000), payment({ paymentDate: '' }))).toBe('Payment date is required')
    expect(await firstMessage(finePaymentSchema(5000), payment({ paymentDate: kigaliDay(2) }))).toBe(
      'Payment date cannot be in the future',
    )
  })
})
