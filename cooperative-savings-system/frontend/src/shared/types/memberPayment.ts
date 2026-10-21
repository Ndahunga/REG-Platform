/**
 * Member payments (backend V28/V33). A payment is a claim until it is verified and then posted: only POSTED means the
 * allocations reached the member's contribution and loan accounts. Every value here mirrors the backend contract.
 */

export type PaymentStatus =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'VERIFIED'
  | 'POSTED'
  | 'REJECTED'
  | 'REVERSED'

export const PAYMENT_STATUSES: PaymentStatus[] = [
  'SUBMITTED',
  'UNDER_REVIEW',
  'VERIFIED',
  'POSTED',
  'REJECTED',
  'REVERSED',
]

export type PaymentMethod = 'MTN_MOMO' | 'AIRTEL_MONEY' | 'BANK_TRANSFER' | 'CASH' | 'OTHER'

export const PAYMENT_METHODS: PaymentMethod[] = ['MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'CASH', 'OTHER']

/** MEMBER_CREDIT exists in the backend enum but is rejected for new payments and can never be posted. */
export type PaymentAllocationType = 'CONTRIBUTION' | 'LOAN_REPAYMENT' | 'MEMBER_CREDIT'

/** Why a member's excess money is held. */
export type CreditPurpose = 'ADVANCE_CONTRIBUTION' | 'LOAN_PREPAYMENT' | 'UNAPPLIED_CREDIT'

export const CREDIT_PURPOSES: CreditPurpose[] = ['ADVANCE_CONTRIBUTION', 'LOAN_PREPAYMENT', 'UNAPPLIED_CREDIT']

export type VerificationMethod =
  | 'CASH_RECEIPT_CONFIRMED'
  | 'MOBILE_MONEY_RECONCILED'
  | 'BANK_RECONCILED'
  | 'PROVIDER_CONFIRMED'

export const VERIFICATION_METHODS: VerificationMethod[] = [
  'CASH_RECEIPT_CONFIRMED',
  'MOBILE_MONEY_RECONCILED',
  'BANK_RECONCILED',
  'PROVIDER_CONFIRMED',
]

export type PostingAttribution = 'ATTRIBUTED' | 'ACCUMULATING' | 'UNATTRIBUTED' | 'NOT_APPLICABLE'

export interface PaymentAllocation {
  id: string
  type: PaymentAllocationType
  amount: string | number
  periodYear?: number | null
  periodMonth?: number | null
  loanId?: string | null
  creditPurpose?: CreditPurpose | null
  principalAmount?: string | number | null
  interestAmount?: string | number | null
  penaltyAmount?: string | number | null
}

/** A row of a payment list. */
export interface PaymentSummary {
  id: string
  memberUserId: string
  /** Only filled for staff views. */
  memberName?: string | null
  amount: string | number
  currency: string
  paymentDate: string
  paymentMethod: PaymentMethod
  transactionReference?: string | null
  status: PaymentStatus
  submittedAt: string
  allocationTypes: PaymentAllocationType[]
}

/** Payment detail. Staff see everything the backend returns; the payer's view omits verifier and poster identities. */
export interface MemberPayment {
  id: string
  cooperativeId: string
  memberUserId: string
  amount: string | number
  allocatedAmount: string | number
  currency: string
  paymentDate: string
  paymentMethod: PaymentMethod
  transactionReference?: string | null
  evidenceFileKey?: string | null
  notes?: string | null
  status: PaymentStatus
  submittedBy?: string | null
  submittedAt: string
  verifiedBy?: string | null
  verifiedAt?: string | null
  postedBy?: string | null
  postedAt?: string | null
  rejectionReason?: string | null
  reversalReason?: string | null
  /** True when the payer asked for a manual review of a receipt that could not be confirmed as clear. */
  receiptReviewRequested?: boolean
  allocations: PaymentAllocation[]
}

export interface PaymentEvent {
  id: string
  fromStatus?: PaymentStatus | null
  toStatus: PaymentStatus
  actorRole?: string | null
  actorUserId?: string | null
  comment?: string | null
  createdAt: string
}

export interface PostingLine {
  allocationId: string
  type: PaymentAllocationType
  amount: string | number
  contributionId?: string | null
  periodYear?: number | null
  periodMonth?: number | null
  paidBefore?: string | number | null
  paidAfter?: string | number | null
  ledgerEntryId?: string | null
  attribution: PostingAttribution
  operatingModel?: string | null
  rotatingBps?: number | null
  retainedBps?: number | null
  rotatingAmount?: string | number | null
  retainedAmount?: string | number | null
  rotationPeriodId?: string | null
  originalRecipientUserId?: string | null
  loanId?: string | null
  loanRepaymentId?: string | null
  principalAmount?: string | number | null
  interestAmount?: string | number | null
  penaltyAmount?: string | number | null
}

/** Staff view of the posting state and the immutable per-allocation results. */
export interface PaymentPosting {
  paymentId: string
  status: PaymentStatus
  amount: string | number
  verificationMethod?: VerificationMethod | null
  verificationEvidenceRecorded: boolean
  verifiedBy?: string | null
  verifiedAt?: string | null
  evidenceCompletedBy?: string | null
  evidenceCompletedAt?: string | null
  postedBy?: string | null
  postedAt?: string | null
  postingAttempts: number
  lastPostingFailureCode?: string | null
  lastPostingFailedAt?: string | null
  /** True when the payment is VERIFIED, has evidence and a failed attempt: a manual retry is the next step. */
  retryable: boolean
  postings: PostingLine[]
}

export interface PaymentAllocationRequest {
  type: 'CONTRIBUTION' | 'LOAN_REPAYMENT' | 'MEMBER_CREDIT'
  amount: string
  periodYear?: number
  periodMonth?: number
  loanId?: string
  creditPurpose?: CreditPurpose
}

export interface PaymentSubmitRequest {
  amount: string
  paymentDate: string
  paymentMethod: PaymentMethod
  transactionReference?: string
  evidenceFileKey?: string
  notes?: string
  /** The payer asks for a manual review of a receipt that could not be confirmed as clear. */
  manualReview?: boolean
  allocations: PaymentAllocationRequest[]
}

export type ReceiptClaimStatus = 'SUCCESS_INDICATED' | 'FAILED_INDICATED' | 'PENDING_INDICATED' | 'UNKNOWN'

export type ReceiptRecommendedAction = 'CONTINUE' | 'REPLACE_RECEIPT' | 'WAIT_FOR_FINAL_RECEIPT' | 'REPLACE_OR_REQUEST_REVIEW'

export type RecipientMatch = 'MATCH' | 'MISMATCH' | 'UNKNOWN'

/**
 * What the system read from a receipt before the payment was submitted. A claim made by an image: it never confirms,
 * verifies or posts anything.
 */
export interface ReceiptAnalysis {
  analysisId: string
  reading: 'COMPLETED' | 'FAILED' | 'UNAVAILABLE' | 'UNSUPPORTED'
  receiptStatus: ReceiptClaimStatus | null
  recommendedAction: ReceiptRecommendedAction
  canSubmit: boolean
  manualReviewNeeded: boolean
  reviewReasons: string[]
  extractedAmount: string | number | null
  currency: string | null
  extractedReference: string | null
  referenceAmbiguous: boolean
  referenceAlreadyUsed: boolean
  extractedDate: string | null
  apparentSender: string | null
  apparentRecipient: string | null
  recipientMatch: RecipientMatch
  confidence: string | number | null
  confidenceLevel: 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'
  amountAmbiguous: boolean
  amountCandidates: Array<string | number>
}

export type PaymentAccountChannel = 'MTN_MOMO' | 'AIRTEL_MONEY' | 'BANK_TRANSFER' | 'OTHER'

export interface PaymentAccount {
  id: string
  channel: PaymentAccountChannel
  accountIdentifier: string
  masked: boolean
  label: string | null
  active: boolean
  createdAt: string
  /** True when nothing was ever submitted or read while the account was registered (the backend decides). */
  canDelete?: boolean
  /** PAYMENT_ACCOUNT_IN_USE when it can only be deactivated. */
  deletionBlockedReason?: string | null
}

/** The confirmation evidence required to verify a payment (or to complete it for a legacy VERIFIED payment). */
export interface PaymentVerifyRequest {
  method: VerificationMethod
  basis: string
  reference?: string
}

export interface PaymentListQuery {
  status?: PaymentStatus
  memberUserId?: string
  page?: number
  size?: number
}

export type PaymentReversalStatus = 'REQUESTED' | 'EXECUTED' | 'DECLINED' | 'WITHDRAWN'

/** What a reversal took back from one posted contribution allocation. */
export interface ReversalTakenBack {
  postingId: string
  contributionId: string
  periodYear: number
  periodMonth: number
  amount: string | number
  paidBefore: string | number
  paidAfter: string | number
  rotatingAmount?: string | number | null
  retainedAmount?: string | number | null
  rotationPeriodId?: string | null
  originalRecipientUserId?: string | null
}

/** A request to reverse a posted contribution-only payment and the independent decision on it. */
export interface PaymentReversal {
  id: string
  paymentId: string
  memberUserId: string
  paymentAmount: string | number
  status: PaymentReversalStatus
  reason: string
  requestedBy: string
  requestedAt: string
  decidedBy?: string | null
  decidedAt?: string | null
  decisionNote?: string | null
  withdrawnAt?: string | null
  withdrawalNote?: string | null
  takenBack: ReversalTakenBack[]
  /** What the reversal gave back to loans (a loan repayment inside the payment). */
  loansGivenBack?: ReversalLoanGivenBack[]
  /** What the reversal took back from the credit the payment had created. */
  creditsTakenBack?: ReversalCreditTakenBack[]
}

export interface ReversalLoanGivenBack {
  postingId: string
  loanId: string
  loanRepaymentId: string
  principalAmount: string | number
  interestAmount: string | number
  penaltyAmount: string | number
  outstandingPrincipalBefore: string | number
  outstandingPrincipalAfter: string | number
  outstandingInterestBefore: string | number
  outstandingInterestAfter: string | number
  loanStatusBefore: string
  loanStatusAfter: string
}

export interface ReversalCreditTakenBack {
  journalEntryId: string
  reversesEntryId: string
  purpose: CreditPurpose
  loanId?: string | null
  amount: string | number
}

/** One bucket of a member's held credit: a purpose (and the loan, for a loan prepayment) and what is left in it. */
export interface CreditBucket {
  memberUserId: string
  memberName: string | null
  purpose: CreditPurpose
  loanId: string | null
  balance: string | number
}

export interface CreditOverview {
  balances: CreditBucket[]
  totalHeld: string | number
}

export interface CreditApplyRequest {
  memberUserId: string
  purpose: CreditPurpose
  bucketLoanId?: string
  targetType: 'CONTRIBUTION' | 'LOAN_REPAYMENT'
  periodYear?: number
  periodMonth?: number
  loanId?: string
  amount: string
  note?: string
}
