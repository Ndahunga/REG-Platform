export type RotationCycleStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
export type RotationPeriodStatus = 'SCHEDULED' | 'CANCELLED'

/** Effective-dated split of contributions, in basis points (10000 = 100%). */
export interface RotationConfiguration {
  id: string
  effectiveYear: number
  effectiveMonth: number
  rotatingBps: number
  retainedBps: number
  createdBy?: string
  createdAt?: string
  /** True when the configuration has not taken effect and nothing was built on it (the backend decides). */
  canDelete?: boolean
  /** ROTATION_CONFIG_IN_FORCE or ROTATION_CONFIG_IN_USE when it is history. */
  deletionBlockedReason?: string | null
}

export interface RotationPeriod {
  id: string
  cycleId: string
  sequenceNumber: number
  year: number
  month: number
  recipientUserId: string
  recipientName?: string | null
  status: RotationPeriodStatus
  /** True once the month has begun: the recipient can no longer be changed. */
  started: boolean
}

export interface RotationCycle {
  id: string
  cycleNumber: number
  startYear: number
  startMonth: number
  periodCount: number
  status: RotationCycleStatus
  createdAt?: string
  activatedAt?: string | null
  cancelledAt?: string | null
  cancelReason?: string | null
  periods: RotationPeriod[]
}

export interface RotationRecipientChange {
  id: string
  rotationPeriodId: string
  previousRecipientUserId: string
  previousRecipientName?: string | null
  newRecipientUserId: string
  newRecipientName?: string | null
  reason: string
  changedBy?: string
  changedAt?: string
}

export interface RotationConfigurationRequest {
  effectiveYear: number
  effectiveMonth: number
  rotatingBps: number
  retainedBps: number
}

export interface RotationCycleCreateRequest {
  startYear: number
  startMonth: number
  recipientUserIds: string[]
}
