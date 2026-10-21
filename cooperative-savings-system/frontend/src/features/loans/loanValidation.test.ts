import { describe, expect, it } from 'vitest'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import {
  loanApproveSchema,
  loanRequestDefaults,
  loanRequestSchema,
  loanSettingsDefaults,
  loanSettingsSchema,
  repaymentSchema,
  toRepaymentPayload,
} from './loanFormSchemas'

const msg = async (schema: { validate: (v: unknown) => Promise<unknown> }, value: unknown) => {
  try {
    await schema.validate(value)
    return null
  } catch (error) {
    return (error as Error).message
  }
}

const allMessages = async (schema: { validate: (v: unknown, o?: object) => Promise<unknown> }, value: unknown) => {
  try {
    await schema.validate(value, { abortEarly: false })
    return []
  } catch (error) {
    return (error as { errors: string[] }).errors
  }
}

const request = (overrides = {}) => ({
  ...loanRequestDefaults,
  amount: '100000',
  purpose: 'Business',
  ...overrides,
})

describe('loanRequestSchema', () => {
  const schema = loanRequestSchema(false)

  it('accepts a valid request', async () => {
    expect(await msg(schema, request({ termMonths: '12' }))).toBeNull()
  })

  it.each([
    ['0', 'Amount must be greater than 0'],
    ['0.005', 'Amount must be at least 0.01'],
    ['1.23456', 'Use at most 4 decimal places'],
    ['0.0050', 'Amount must be at least 0.01'],
    ['abc', 'Enter a valid amount'],
    ['-1', 'Amount cannot be negative'],
  ])('rejects the amount %s', async (amount, message) => {
    expect(await msg(schema, request({ amount }))).toBe(message)
  })

  it.each(['0', '601', '1.5', 'abc'])('rejects the term "%s"', async (termMonths) => {
    expect(await msg(schema, request({ termMonths }))).toBe('Enter a valid term in months (1 to 600)')
  })

  it('requires a purpose when an officer is not choosing a member', async () => {
    expect(await msg(schema, request({ purpose: '' }))).toBe('Loan purpose is required')
  })

  it('requires a guarantor and amount only when guaranteed by a guarantor', async () => {
    expect(await msg(schema, request({ guaranteeMode: 'SELF' }))).toBeNull()
    const guarantor = request({ guaranteeMode: 'GUARANTOR', guarantorUserId: '', guaranteedAmount: '' })
    expect(await allMessages(schema, guarantor)).toEqual(
      expect.arrayContaining(['Select a guarantor', 'Enter a valid guaranteed amount']),
    )
    expect(await allMessages(schema, { ...guarantor, guarantorUserId: 'g1' })).toEqual([
      'Enter a valid guaranteed amount',
    ])
  })

  it('rejects a guaranteed amount above the requested loan amount', async () => {
    const base = request({ guaranteeMode: 'GUARANTOR', guarantorUserId: 'g1', amount: '100000' })
    expect(await msg(schema, { ...base, guaranteedAmount: '100001' })).toBe(
      'Guaranteed amount cannot exceed the requested loan amount',
    )
    expect(await msg(schema, { ...base, guaranteedAmount: '100000' })).toBeNull()
  })

  it('requires a member when an officer issues the loan', async () => {
    expect(await msg(loanRequestSchema(true), request({ memberUserId: '' }))).toBe('Select a member')
  })
})

describe('loanApproveSchema', () => {
  it.each(['0', '0.0000', '0.001', '-5'])('rejects the approved amount "%s"', async (approvedAmount) => {
    expect(await msg(loanApproveSchema, { approvedAmount, termMonths: '' })).not.toBeNull()
  })

  it.each(['0', '601'])('rejects the term "%s"', async (termMonths) => {
    expect(await msg(loanApproveSchema, { approvedAmount: '', termMonths })).toBe(
      'Enter a valid term in months (1 to 600)',
    )
  })

  it('keeps amount and term optional', async () => {
    expect(await msg(loanApproveSchema, { approvedAmount: '', termMonths: '' })).toBeNull()
    expect(await msg(loanApproveSchema, { approvedAmount: '50000.5', termMonths: '600' })).toBeNull()
  })
})

describe('repaymentSchema', () => {
  const valid = { amount: '5000', paymentDate: todayInKigaliIso(), paymentReference: '', notes: '' }

  it('accepts a valid repayment', async () => {
    expect(await msg(repaymentSchema, valid)).toBeNull()
  })

  it('requires a payment date (a blank date is always rejected by the backend)', async () => {
    expect(await msg(repaymentSchema, { ...valid, paymentDate: '' })).toBe('Payment date is required')
  })

  it('rejects a future payment date and an impossible date', async () => {
    expect(await msg(repaymentSchema, { ...valid, paymentDate: '2999-01-01' })).toBe(
      'Payment date cannot be in the future',
    )
    expect(await msg(repaymentSchema, { ...valid, paymentDate: '2026-02-30' })).toBe(
      'Enter a valid payment date',
    )
  })

  it('rejects a zero, negative or over-precise amount and long reference/notes', async () => {
    expect(await msg(repaymentSchema, { ...valid, amount: '0' })).toBe('Amount must be greater than 0')
    expect(await msg(repaymentSchema, { ...valid, amount: '-1' })).toBe('Amount cannot be negative')
    expect(await msg(repaymentSchema, { ...valid, amount: '1.00001' })).toBe('Use at most 4 decimal places')
    expect(await msg(repaymentSchema, { ...valid, paymentReference: 'r'.repeat(129) })).not.toBeNull()
    expect(await msg(repaymentSchema, { ...valid, notes: 'n'.repeat(2001) })).not.toBeNull()
  })

  it('always sends the validated date', () => {
    expect(toRepaymentPayload(valid).paymentDate).toBe(valid.paymentDate)
  })
})

describe('loanSettingsSchema limits', () => {
  const base = { ...loanSettingsDefaults }

  it('caps the maximum term and minimum membership at 600 months', async () => {
    expect(await msg(loanSettingsSchema, { ...base, maxTermMonths: '601' })).toBe(
      'Enter a valid term in months (1 to 600)',
    )
    expect(await msg(loanSettingsSchema, { ...base, minMembershipMonths: '601' })).toBe(
      'Enter a whole number of months between 0 and 600',
    )
    expect(await msg(loanSettingsSchema, { ...base, maxTermMonths: '600', minMembershipMonths: '600' })).toBeNull()
  })

  it('requires share tiers to be at least 0.0001% with at most 4 decimals', async () => {
    const tier = (minSharePercent: string, maxLoanAmount = '100000') => ({
      ...base,
      shareTiers: [{ minSharePercent, maxLoanAmount }],
    })
    expect(await msg(loanSettingsSchema, tier('0.00001'))).not.toBeNull()
    expect(await msg(loanSettingsSchema, tier('0'))).not.toBeNull()
    expect(await msg(loanSettingsSchema, tier('100.5'))).not.toBeNull()
    expect(await msg(loanSettingsSchema, tier('0.0001'))).toBeNull()
    expect(await msg(loanSettingsSchema, tier('10', '0.001'))).not.toBeNull()
  })
})
