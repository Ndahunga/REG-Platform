import { SUBSCRIPTION_TIME_ZONE } from './subscriptionAccess'

const STORAGE_PREFIX = 'ouwealth.trialExpiryNotice'

/** Calendar day (YYYY-MM-DD) in the subscription time zone, matching how the server counts days remaining. */
export function subscriptionDayKey(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: SUBSCRIPTION_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
}

function storageKey(cooperativeId: string, trialEndsAt: string | null | undefined): string {
  return `${STORAGE_PREFIX}.${cooperativeId}.${trialEndsAt ?? 'unknown'}`
}

/** True when the user already dismissed this scheme's trial notice today. Storage failures mean "not dismissed". */
export function isTrialNoticeDismissedToday(
  cooperativeId: string,
  trialEndsAt: string | null | undefined,
  now: Date = new Date(),
): boolean {
  try {
    return window.localStorage.getItem(storageKey(cooperativeId, trialEndsAt)) === subscriptionDayKey(now)
  } catch {
    return false
  }
}

export function dismissTrialNoticeForToday(
  cooperativeId: string,
  trialEndsAt: string | null | undefined,
  now: Date = new Date(),
): void {
  try {
    window.localStorage.setItem(storageKey(cooperativeId, trialEndsAt), subscriptionDayKey(now))
  } catch {
    // Dismissal is a convenience only; the in-memory state still hides the notice for this session.
  }
}
