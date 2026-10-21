import { BILLING_MANAGER_ROLES, hasAnyRole } from '@/shared/types/auth'
import type { BillingPlanQuote } from '@/shared/types/billing'
import type { SubscriptionStatus } from '@/shared/types/cooperative'

export function canManageBilling(roles: string[] | undefined): boolean {
  return hasAnyRole(roles, BILLING_MANAGER_ROLES)
}

export function billingCtaKey(status: SubscriptionStatus): string {
  switch (status) {
    case 'TRIAL':
      return 'subscription.dashboard.viewPlans'
    case 'ACTIVE':
      return 'subscription.dashboard.manage'
    case 'PAST_DUE':
      return 'subscription.dashboard.review'
    case 'EXPIRED':
    case 'CANCELED':
      return 'subscription.dashboard.renew'
    default:
      return 'subscription.dashboard.choosePlan'
  }
}

export function subscriptionStatusChipColor(
  status: SubscriptionStatus,
): 'default' | 'info' | 'success' | 'warning' | 'error' {
  switch (status) {
    case 'TRIAL':
      return 'info'
    case 'ACTIVE':
      return 'success'
    case 'PAST_DUE':
      return 'warning'
    case 'EXPIRED':
      return 'error'
    case 'CANCELED':
      return 'default'
    default:
      return 'warning'
  }
}

export function paymentStatusChipColor(
  status: string,
): 'default' | 'info' | 'success' | 'warning' | 'error' {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'PENDING':
      return 'info'
    case 'FAILED':
      return 'error'
    default:
      return 'default'
  }
}

export function equivalentMonthlyAmount(plan: BillingPlanQuote): string | number | null {
  if (!plan.periodMonths || plan.periodMonths <= 1) return null
  const amount = Number(plan.amount)
  if (!Number.isFinite(amount)) return null
  return amount / plan.periodMonths
}
