import { describe, expect, it } from 'vitest'
import { firstMessage, kigaliDay } from '@/test/schemaHelpers'
import {
  socialContributionDefaults,
  socialContributionSchema,
  socialDisbursementDefaults,
  socialDisbursementSchema,
  socialFundSettingsSchema,
  toSocialFundSettingsPayload,
} from './socialFundFormSchemas'

describe('socialContributionSchema', () => {
  const valid = { ...socialContributionDefaults, amount: '1000', contributionDate: kigaliDay(0) }

  it('accepts a valid contribution', async () => {
    expect(await firstMessage(socialContributionSchema, valid)).toBeNull()
  })

  it.each([
    ['', 'Amount is required'],
    ['0', 'Amount must be greater than 0'],
    ['0.005', 'Amount must be at least 0.01'],
    ['abc', 'Enter a valid amount'],
    ['1.23456', 'Use at most 4 decimal places'],
    ['1000000000000000', 'Amount is too large'],
  ])('rejects the amount "%s"', async (amount, message) => {
    expect(await firstMessage(socialContributionSchema, { ...valid, amount })).toBe(message)
  })

  it('requires a real, non-future date', async () => {
    expect(await firstMessage(socialContributionSchema, { ...valid, contributionDate: '' })).toBe(
      'Contribution date is required',
    )
    expect(await firstMessage(socialContributionSchema, { ...valid, contributionDate: kigaliDay(1) })).toBe(
      'Contribution date cannot be in the future',
    )
  })
})

describe('socialDisbursementSchema', () => {
  const valid = {
    ...socialDisbursementDefaults,
    beneficiaryMemberUserId: 'm1',
    amount: '20000',
    reason: 'Funeral support',
    disbursementDate: kigaliDay(0),
  }

  it('accepts a valid disbursement', async () => {
    expect(await firstMessage(socialDisbursementSchema, valid)).toBeNull()
  })

  it('requires a beneficiary, reason and a valid amount', async () => {
    expect(await firstMessage(socialDisbursementSchema, { ...valid, beneficiaryMemberUserId: '' })).toBe(
      'Select a beneficiary',
    )
    expect(await firstMessage(socialDisbursementSchema, { ...valid, reason: '' })).toBe('Reason is required')
    expect(await firstMessage(socialDisbursementSchema, { ...valid, amount: '0' })).toBe(
      'Amount must be greater than 0',
    )
    expect(await firstMessage(socialDisbursementSchema, { ...valid, amount: '0.001' })).toBe(
      'Amount must be at least 0.01',
    )
  })

  it('rejects a future disbursement date', async () => {
    expect(await firstMessage(socialDisbursementSchema, { ...valid, disbursementDate: kigaliDay(4) })).toBe(
      'Disbursement date cannot be in the future',
    )
  })
})

describe('socialFundSettingsSchema', () => {
  it('requires the suggested amount instead of silently sending null (the backend rejects null)', async () => {
    expect(await firstMessage(socialFundSettingsSchema, { suggestedContributionAmount: '', enabled: true })).toBe(
      'Suggested amount is required',
    )
  })

  it('accepts zero ("no suggestion") and positive amounts, rejects negatives and over-precise ones', async () => {
    expect(await firstMessage(socialFundSettingsSchema, { suggestedContributionAmount: '0', enabled: true })).toBeNull()
    expect(await firstMessage(socialFundSettingsSchema, { suggestedContributionAmount: '500.5', enabled: false })).toBeNull()
    expect(await firstMessage(socialFundSettingsSchema, { suggestedContributionAmount: '-1', enabled: true })).toBe(
      'Suggested amount cannot be negative',
    )
    expect(await firstMessage(socialFundSettingsSchema, { suggestedContributionAmount: '1.00001', enabled: true })).toBe(
      'Use at most 4 decimal places',
    )
  })

  it('never sends a null amount for a valid form', () => {
    expect(toSocialFundSettingsPayload({ suggestedContributionAmount: '0', enabled: true }).suggestedContributionAmount).toBe('0')
  })
})
