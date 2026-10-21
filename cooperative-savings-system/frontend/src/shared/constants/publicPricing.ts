/**
 * Public-safe OuWealth Community subscription pricing.
 *
 * Source of truth for the marketing site and other unauthenticated surfaces.
 * Values align with product billing (monthly RWF 2,000 / annual RWF 18,000 with
 * 25% off the RWF 24,000 list year) and the signup trial messaging (4 months free).
 *
 * Authenticated BillingPage continues to load live plan amounts from the API.
 * Do not put payment secrets or provider details here.
 */
export const PUBLIC_SUBSCRIPTION_PRICING = {
  currency: 'RWF',
  trialMonths: 4,
  monthlyAmount: 2000,
  annualAmount: 18000,
  annualListPrice: 24000,
  annualDiscountPercent: 25,
  annualSavings: 6000,
  annualEquivalentMonthly: 1500,
} as const

export type PublicSubscriptionPricing = typeof PUBLIC_SUBSCRIPTION_PRICING
