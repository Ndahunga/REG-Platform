export type CooperativeStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'ARCHIVED'
export type CooperativeOnboardingState = 'COMPLETE' | 'AWAITING_PRESIDENT'
/** How a Saving Scheme operates. A legacy scheme that has not been classified yet has no value (null). */
export type SchemeOperatingModel = 'ACCUMULATING' | 'ROTATING' | 'HYBRID'
export type SubscriptionInitialization = 'START_TRIAL' | 'NONE' | 'ACTIVE_MANUAL'
export type SubscriptionStatus = 'NONE' | 'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'EXPIRED' | 'CANCELED'

export const COOPERATIVE_STATUSES: CooperativeStatus[] = [
  'ACTIVE',
  'INACTIVE',
  'SUSPENDED',
  'ARCHIVED',
]

/** Compact shape from GET /cooperatives/mine (selector). */
export interface CooperativeSummary {
  id: string
  name: string
  status: CooperativeStatus
  currency: string
  logoUrl?: string | null
  operatingModel?: SchemeOperatingModel | null
}

export interface Cooperative {
  id: string
  name: string
  description?: string | null
  registrationNumber?: string | null
  contactEmail?: string | null
  contactPhone?: string | null
  address?: string | null
  currency: string
  financialYearStartMonth: number
  monthlyContributionAmount: string | number
  contributionDueDay: number
  registrationDate?: string | null
  status: CooperativeStatus
  onboardingState?: CooperativeOnboardingState
  operatingModel?: SchemeOperatingModel | null
  logoUrl?: string | null
  logoFileKey?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface AssignPresidentRequest {
  userId?: string
  username?: string
  email?: string
  firstName?: string
  lastName?: string
  phone?: string
  temporaryPassword?: string
}

export interface CooperativeCreateRequest {
  name: string
  description?: string
  registrationNumber?: string
  contactEmail?: string
  contactPhone?: string
  address?: string
  currency: string
  financialYearStartMonth: number
  monthlyContributionAmount: string | number
  contributionDueDay: number
  registrationDate?: string
  /** Required for every new Saving Scheme; there is no default. */
  operatingModel: SchemeOperatingModel
  subscriptionInitialization?: SubscriptionInitialization
  president?: AssignPresidentRequest
}

export type CooperativeUpdateRequest = Omit<
  CooperativeCreateRequest,
  'subscriptionInitialization' | 'president' | 'operatingModel'
>

/** One-time classification of a legacy scheme; `confirmed` must be true. */
export interface OperatingModelClassificationRequest {
  operatingModel: SchemeOperatingModel
  confirmed: true
}

export interface CooperativeStatusUpdateRequest {
  status: CooperativeStatus
}

export interface CooperativeSubscription {
  id: string
  cooperativeId: string
  status: SubscriptionStatus
  storedStatus?: SubscriptionStatus | null
  effectiveStatus?: SubscriptionStatus | null
  usable?: boolean
  writeAllowed?: boolean
  billingCycle?: string | null
  trialStartedAt?: string | null
  trialEndsAt?: string | null
  currentPeriodStartedAt?: string | null
  currentPeriodEndsAt?: string | null
  pastDueUntil?: string | null
  canceledAt?: string | null
  daysRemaining?: number | null
  createdAt?: string
  updatedAt?: string
}

export function mapCooperativeSummary(raw: CooperativeSummary): CooperativeSummary {
  return {
    id: String(raw.id),
    name: raw.name,
    status: raw.status,
    currency: raw.currency || 'RWF',
    logoUrl: raw.logoUrl ?? null,
    operatingModel: raw.operatingModel ?? null,
  }
}

export function isOnboardingIncomplete(
  cooperative: Pick<Cooperative, 'onboardingState'> | null | undefined,
): boolean {
  return cooperative?.onboardingState === 'AWAITING_PRESIDENT'
}

export function isOperatingModelConfigured(
  cooperative: Pick<Cooperative, 'operatingModel'> | null | undefined,
): boolean {
  return Boolean(cooperative?.operatingModel)
}
