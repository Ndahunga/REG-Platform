import { describe, expect, it } from 'vitest'
import { PUBLIC_SUBSCRIPTION_PRICING } from './publicPricing'

describe('PUBLIC_SUBSCRIPTION_PRICING', () => {
  it('matches OuWealth public subscription amounts', () => {
    expect(PUBLIC_SUBSCRIPTION_PRICING.currency).toBe('RWF')
    expect(PUBLIC_SUBSCRIPTION_PRICING.trialMonths).toBe(4)
    expect(PUBLIC_SUBSCRIPTION_PRICING.monthlyAmount).toBe(2000)
    expect(PUBLIC_SUBSCRIPTION_PRICING.annualAmount).toBe(18000)
    expect(PUBLIC_SUBSCRIPTION_PRICING.annualListPrice).toBe(24000)
    expect(PUBLIC_SUBSCRIPTION_PRICING.annualDiscountPercent).toBe(25)
    expect(PUBLIC_SUBSCRIPTION_PRICING.annualSavings).toBe(6000)
    expect(PUBLIC_SUBSCRIPTION_PRICING.annualEquivalentMonthly).toBe(1500)
  })

  it('keeps annual savings and equivalent month consistent with list vs offer', () => {
    const { monthlyAmount, annualAmount, annualListPrice, annualSavings, annualEquivalentMonthly } =
      PUBLIC_SUBSCRIPTION_PRICING
    expect(annualListPrice - annualAmount).toBe(annualSavings)
    expect(monthlyAmount * 12).toBe(annualListPrice)
    expect(annualAmount / 12).toBe(annualEquivalentMonthly)
  })
})
