import { describe, expect, it } from 'vitest'
import { firstMessage, kigaliDay } from '@/test/schemaHelpers'
import {
  campaignFormDefaults,
  campaignFormSchema,
  specialSubmitDefaults,
  specialSubmitSchema,
} from './contributionFormSchemas'

describe('specialSubmitSchema', () => {
  const valid = { ...specialSubmitDefaults, amount: '2500', contributionDate: kigaliDay(0) }

  it('accepts a valid submission', async () => {
    expect(await firstMessage(specialSubmitSchema, valid)).toBeNull()
    expect(await firstMessage(specialSubmitSchema, { ...valid, contributionDate: '' })).toBeNull()
  })

  it.each([
    ['', 'Amount is required'],
    ['0', 'Amount must be greater than 0'],
    ['0.001', 'Amount must be at least 0.01'],
    ['-10', 'Amount cannot be negative'],
    ['abc', 'Enter a valid amount'],
  ])('rejects the amount "%s" (the backend requires at least 0.01)', async (amount, message) => {
    expect(await firstMessage(specialSubmitSchema, { ...valid, amount })).toBe(message)
  })

  it('rejects a future date', async () => {
    expect(await firstMessage(specialSubmitSchema, { ...valid, contributionDate: kigaliDay(2) })).toBe(
      'Contribution date cannot be in the future',
    )
  })

  it('limits reference and notes', async () => {
    expect(await firstMessage(specialSubmitSchema, { ...valid, paymentReference: 'r'.repeat(129) })).not.toBeNull()
    expect(await firstMessage(specialSubmitSchema, { ...valid, notes: 'n'.repeat(2001) })).not.toBeNull()
  })
})

describe('campaignFormSchema', () => {
  const valid = { ...campaignFormDefaults, name: 'School fees' }

  it('accepts a campaign with only a name', async () => {
    expect(await firstMessage(campaignFormSchema, valid)).toBeNull()
  })

  it('requires a name', async () => {
    expect(await firstMessage(campaignFormSchema, { ...valid, name: '  ' })).toBe('Name is required')
  })

  it('accepts zero for suggested and target amounts but rejects invalid ones', async () => {
    expect(await firstMessage(campaignFormSchema, { ...valid, suggestedAmount: '0', targetAmount: '0' })).toBeNull()
    expect(await firstMessage(campaignFormSchema, { ...valid, suggestedAmount: '-1' })).toBe(
      'Suggested amount cannot be negative',
    )
    expect(await firstMessage(campaignFormSchema, { ...valid, targetAmount: '1.00001' })).toBe(
      'Use at most 4 decimal places',
    )
    expect(await firstMessage(campaignFormSchema, { ...valid, targetAmount: 'lots' })).toBe('Enter a valid amount')
  })

  it('requires the end date to be on or after the start date, allowing future dates', async () => {
    const start = kigaliDay(5)
    expect(await firstMessage(campaignFormSchema, { ...valid, startDate: start, endDate: kigaliDay(2) })).toBe(
      'End date must be on or after the start date',
    )
    expect(await firstMessage(campaignFormSchema, { ...valid, startDate: start, endDate: start })).toBeNull()
    expect(await firstMessage(campaignFormSchema, { ...valid, startDate: start, endDate: kigaliDay(30) })).toBeNull()
  })

  it('rejects an impossible date', async () => {
    expect(await firstMessage(campaignFormSchema, { ...valid, startDate: '2026-02-31' })).toBe(
      'Enter a valid start date',
    )
  })
})
