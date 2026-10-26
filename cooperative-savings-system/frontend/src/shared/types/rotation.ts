export type RotationCycleStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
export type RotationPeriodStatus = 'SCHEDULED' | 'CANCELLED'

/** Effective-dated split of contributions, in basis points (10000 = 100%). */
export interface RotationConfiguration {
  id: string
  effectiveYear: number
  effectiveMonth: number
  rotatingBps: number
  retainedBps: number
  /** Rotated RWF per share when the split was given in money. */
  rotatingUnitAmount?: number | string | null
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
  payoutMode?: RotationPayoutMode
  payoutPerShare?: number | string | null
  turnMode?: RotationTurnMode | null
  turns?: RotationTurn[]
  fundingCheck?: RotationFundingCheck | null
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
  rotatingBps?: number
  retainedBps?: number
  /** Rotated RWF per share (exact); the rest of the contribution per share is retained. */
  rotatingUnitAmount?: number
}

export interface RotationCycleCreateRequest {
  startYear: number
  startMonth: number
  recipientUserIds: string[]
}

// ------------------------------------------------------------------ share-based cycles

export type RotationPayoutMode = 'PERIOD_COLLECTION' | 'FIXED_PER_SHARE'
export type RotationTurnMode = 'ONE_SHARE_PER_TURN' | 'ALL_SHARES_TOGETHER' | 'GROUPED_BY_MONTH'
export type RotationTurnState = 'UPCOMING' | 'DUE' | 'PARTLY_PAID' | 'PAID'

export interface RotationTurn {
  id: string
  periodId: string
  year: number
  month: number
  memberUserId: string
  memberName?: string | null
  shares: number
  payoutAmount: number | string
  openingReceived: number | string
  paidOut: number | string
  committed: number | string
  outstanding: number | string
  state: RotationTurnState
}

export interface RotationFundingCheck {
  sharesWithTurns: number
  sharesHeldByMembers: number
  rotatedPerShareMonthly?: number | string | null
  expectedCollections?: number | string | null
  totalPayouts: number | string
  warnings: string[]
}

export interface ShareCycleTurnInput {
  monthIndex: number
  memberUserId: string
  shares: number
  openingReceivedAmount?: number
  openingReceivedDate?: string
}

export interface ShareCycleCreateRequest {
  startYear: number
  startMonth: number
  months: number
  payoutPerShare: number
  turnMode: RotationTurnMode
  turns: ShareCycleTurnInput[]
}

export interface TurnPayoutEligibility {
  turnId: string
  periodId: string
  periodYear: number
  periodMonth: number
  memberUserId: string
  memberName: string
  shares: number
  payoutAmount: number | string
  outstanding: number | string
  arrears: number | string
  canRequestPayout: boolean
  payoutBlockedReason?: string | null
  maxRequestable: number | string
}

export interface SharePool {
  collected: number | string
  reserveFree: number | string
  paidOut: number | string
  committed: number | string
  available: number | string
}

export interface RotationImportRequest {
  cutoverYear: number
  cutoverMonth: number
  rotatingUnitAmount?: number
  cycle: ShareCycleCreateRequest
  members: { memberUserId: string; rotatedPaid: number; received?: number }[]
  rotationCashAtCutover?: number
}

export interface RotationImportResponse {
  cycle: RotationCycle
  totalRotatedPaid: number | string
  totalReceived: number | string
  expectedRotationCash: number | string
  declaredRotationCash?: number | string | null
  difference?: number | string | null
  warnings: string[]
}
