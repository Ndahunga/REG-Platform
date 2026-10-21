import { describe, expect, it } from 'vitest'
import { firstMessage, kigaliDay } from '@/test/schemaHelpers'
import { payoutPreviewDefaults, payoutPreviewSchema, toPayoutPreviewPayload } from './payoutFormSchemas'

describe('payoutPreviewSchema', () => {
  const valid = payoutPreviewDefaults()

  it('defaults the period to this year up to today (Kigali) and accepts it', async () => {
    expect(valid.periodTo).toBe(kigaliDay(0))
    expect(valid.periodFrom).toBe(`${kigaliDay(0).slice(0, 4)}-01-01`)
    expect(await firstMessage(payoutPreviewSchema, valid)).toBeNull()
  })

  it('treats a blank pool as "use the available fund" and sends nothing', async () => {
    expect(await firstMessage(payoutPreviewSchema, { ...valid, payoutPoolAmount: '' })).toBeNull()
    expect(toPayoutPreviewPayload({ ...valid, payoutPoolAmount: '' }).payoutPoolAmount).toBeUndefined()
  })

  it.each([
    ['0', 'Payout pool must be greater than 0'],
    ['0.0000', 'Payout pool must be greater than 0'],
    ['0.001', 'Payout pool must be at least 0.01'],
    ['-1', 'Payout pool cannot be negative'],
    ['abc', 'Enter a valid amount'],
    ['1.23456', 'Use at most 4 decimal places'],
  ])('rejects a pool of "%s" (the backend requires at least 0.01)', async (payoutPoolAmount, message) => {
    expect(await firstMessage(payoutPreviewSchema, { ...valid, payoutPoolAmount })).toBe(message)
  })

  it('accepts a positive pool and sends it', async () => {
    expect(await firstMessage(payoutPreviewSchema, { ...valid, payoutPoolAmount: '250000' })).toBeNull()
    expect(toPayoutPreviewPayload({ ...valid, payoutPoolAmount: '250000' }).payoutPoolAmount).toBe('250000')
  })

  it('requires both period dates, in order, and at least one contribution source', async () => {
    expect(await firstMessage(payoutPreviewSchema, { ...valid, periodFrom: '' })).toBe(
      'Period start date is required',
    )
    expect(await firstMessage(payoutPreviewSchema, { ...valid, periodFrom: kigaliDay(0), periodTo: kigaliDay(-10) })).toBe(
      'End date must be on or after start date',
    )
    expect(
      await firstMessage(payoutPreviewSchema, { ...valid, includeRegular: false, includeSpecial: false }),
    ).toBe('Select regular and/or special contributions')
  })
})
