import type { PaymentMethod } from './memberPayment'

/** Rotating entitlements and payouts (backend Phase 2). Every figure is derived from money that was really collected. */

export type RotationPeriodStatus = 'SCHEDULED' | 'CANCELLED'

export interface PeriodEntitlement {
  periodId: string
  cycleId: string
  year: number
  month: number
  recipientUserId: string
  recipientName: string
  periodStatus: RotationPeriodStatus
  /** Expected rotating contributions: never cash. */
  expectedRotating: string | number
  /** Rotating funds actually collected on the period's obligations. */
  collectedRotating: string | number
  /** Rotating money paid through unverified paths: in the ledger as cash, owed to nobody until reconciled. */
  unreconciledRotating: string | number
  /** Expected minus collected: a contribution shortfall, not money anybody is owed. */
  rotatingArrears: string | number
  distributed: string | number
  /** Held by a payout awaiting approval or disbursement. */
  committed: string | number
  /** Collected minus distributed: what the recipient is still owed out of real money. */
  outstandingEntitlement: string | number
  payableEntitlement: string | number
}

export interface LateRecovery {
  postingId: string
  paymentId: string
  paymentDate: string
  amount: string | number
  rotatingAmount?: string | number | null
  late: boolean
  reversed: boolean
}

export type RotationPayoutStatus = 'REQUESTED' | 'APPROVED' | 'DISBURSED' | 'REJECTED' | 'CANCELLED'

export interface RotationPayout {
  id: string
  rotationPeriodId: string
  periodYear: number
  periodMonth: number
  recipientUserId: string
  recipientName: string
  amount: string | number
  entitlementAtRequest: string | number
  availableAtRequest: string | number
  /** True when the payout is less than the entitlement (the rest stays owed to the same recipient). */
  partial: boolean
  remainingAfterPayout: string | number
  status: RotationPayoutStatus
  notes?: string | null
  requestedBy: string
  requestedAt: string
  approvedBy?: string | null
  approvedAt?: string | null
  rejectionReason?: string | null
  cancelReason?: string | null
  disbursedBy?: string | null
  disbursedAt?: string | null
  disbursementMethod?: PaymentMethod | null
  disbursementReference?: string | null
  disbursementNote?: string | null
  /** What the signed-in user may do with this payout now, decided by the backend. */
  actions?: PayoutActions
}

/** The backend's answer to "may I approve, confirm or cancel this payout?", with a stable reason code when not. */
export interface PayoutActions {
  canApprove: boolean
  approveBlockedReason?: string | null
  canConfirmDisbursement: boolean
  confirmBlockedReason?: string | null
  canCancel: boolean
  cancelBlockedReason?: string | null
}

/** Whether a payout can be requested for a period now, and the stable reason when not. */
export interface PeriodPayoutEligibility {
  periodId: string
  periodYear: number
  periodMonth: number
  recipientUserId: string
  canRequestPayout: boolean
  payoutBlockedReason?: string | null
  maxRequestable: string | number
}

export interface RotationPayoutEvent {
  id: string
  fromStatus?: RotationPayoutStatus | null
  toStatus: RotationPayoutStatus
  actorRole?: string | null
  actorUserId?: string | null
  comment?: string | null
  createdAt: string
}

export interface PayoutRequestBody {
  rotationPeriodId: string
  amount?: string
  notes?: string
}

export interface DisbursementBody {
  method: PaymentMethod
  reference: string
  note?: string
}

export interface ModelSummary {
  cooperativeId: string
  currency: string
  operatingModel: 'ACCUMULATING' | 'ROTATING' | 'HYBRID' | null
  expectedContributions: string | number
  postedCollections: string | number
  contributionArrears: string | number
  outstandingContributions: string | number
  retainedCollections: string | number
  /** Paid money in a rotating or hybrid scheme whose character cannot be established. Neither retained nor owed until resolved. */
  unclassifiedCollections: string | number
  /** Money known to be rotating whose original period is unresolved: held, not payable, not member capital. */
  unresolvedRotatingCollections: string | number
  rotatingCollected: string | number
  /** Rotating money paid through unverified paths; not eligible for payout until reconciled. */
  rotatingRequiringReconciliation: string | number
  rotatingExpected: string | number
  rotatingArrears: string | number
  recipientEntitlementsOwed: string | number
  /** Owed on verified money plus unreconciled rotating money: everything set aside for recipients. */
  totalRotatingLiabilities: string | number
  payoutsDisbursed: string | number
  payoutsOpen: string | number
  availableFunds: string | number
  availableAfterEntitlements: string | number
}

/** The rotating pool of the scheme (backend arithmetic: nothing here is computed on the client). */
export interface RotationPoolSummary {
  openingPool: string | number
  otherReceipts: string | number
  expenses: string | number
  collectedPool: string | number
  previousPayouts: string | number
  committedPayouts: string | number
  availablePool: string | number
  /** Reserve not yet covering any period's shortfall. */
  unusedReserve: string | number
  /** Reserve currently covering payouts above the verified collections of their own periods. */
  reserveInUse: string | number
}

export type PoolEntryType = 'OPENING' | 'TOP_UP' | 'EXPENSE'

export interface RotationPoolEntry {
  id: string
  type: PoolEntryType
  amount: string | number
  effectiveDate: string
  reason: string
  reference?: string | null
  recordedBy: string
  createdAt: string
}

export interface PoolEntryBody {
  type: PoolEntryType
  amount: string
  reason: string
}

/** What one member has paid in, been paid out and still has to contribute in a rotating scheme. */
export interface MemberObligation {
  memberUserId: string
  memberName: string
  recipientPeriods: { periodId: string; year: number; month: number; status: string }[]
  paidContributions: string | number
  paidRotating: string | number
  verifiedRotating: string | number
  payoutReceived: string | number
  remainingScheduled: string | number
  overdue: string | number
  nextDeadline?: string | null
  /** Received ahead of contributing. A view of the same money: never added to remaining or overdue. Not a loan. */
  rotationalAdvance: string | number
  periodsWithoutObligation: number
  paidOutAndStillContributing: boolean
}
