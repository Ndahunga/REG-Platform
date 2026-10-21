import type { CooperativeSubscription, SubscriptionStatus } from '@/shared/types/cooperative'

export const TRIAL_WARNING_DAYS = 7
export const SUBSCRIPTION_TIME_ZONE = 'Africa/Kigali'

export function effectiveSubscriptionStatus(
  subscription: CooperativeSubscription | null | undefined,
): SubscriptionStatus {
  return subscription?.effectiveStatus ?? subscription?.storedStatus ?? subscription?.status ?? 'NONE'
}

export function formatSubscriptionDate(iso?: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'long',
    timeZone: SUBSCRIPTION_TIME_ZONE,
  }).format(date)
}

export function accessUntilIso(
  subscription: CooperativeSubscription | null | undefined,
): string | null {
  const status = effectiveSubscriptionStatus(subscription)
  if (status === 'TRIAL') return subscription?.trialEndsAt ?? null
  if (status === 'ACTIVE') return subscription?.currentPeriodEndsAt ?? null
  if (status === 'PAST_DUE') return subscription?.pastDueUntil ?? null
  if (status === 'CANCELED') return subscription?.currentPeriodEndsAt ?? null
  return null
}

export function isActionNeeded(status: SubscriptionStatus, daysRemaining?: number | null): boolean {
  if (status === 'EXPIRED' || status === 'NONE' || status === 'PAST_DUE') return true
  if (status === 'CANCELED') return true
  if (status === 'TRIAL' && daysRemaining != null && daysRemaining <= TRIAL_WARNING_DAYS) return true
  return false
}

export function resolveSubscriptionAccess(options: {
  subscription: CooperativeSubscription | null | undefined
  isPending: boolean
  isSuperAdmin: boolean
  hasCooperative: boolean
}): {
  effectiveStatus: SubscriptionStatus
  canWrite: boolean
  readOnly: boolean
  daysRemaining: number | null
} {
  const { subscription, isPending, isSuperAdmin, hasCooperative } = options
  const effectiveStatus = effectiveSubscriptionStatus(subscription)
  if (isSuperAdmin || !hasCooperative) {
    return {
      effectiveStatus,
      canWrite: true,
      readOnly: false,
      daysRemaining: subscription?.daysRemaining ?? null,
    }
  }
  const writeAllowed = isPending ? true : Boolean(subscription?.writeAllowed)
  return {
    effectiveStatus,
    canWrite: writeAllowed,
    readOnly: !isPending && !writeAllowed,
    daysRemaining: subscription?.daysRemaining ?? null,
  }
}
