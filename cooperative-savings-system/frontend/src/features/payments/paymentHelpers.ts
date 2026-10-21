import axios from 'axios'
import type { Loan } from '@/shared/types/loan'
import type { Contribution } from '@/shared/types/contribution'
import type {
  CreditPurpose,
  MemberPayment,
  PaymentAllocationType,
  PaymentPosting,
  PaymentStatus,
  VerificationMethod,
  PaymentMethod,
} from '@/shared/types/memberPayment'
import { PERMISSION_CONTRIBUTION_WRITE, PERMISSION_LOAN_WRITE, hasPermission } from '@/shared/types/auth'

// ---------------------------------------------------------------- money (advisory only; the backend revalidates)

const UNIT = 10_000

/** Whole ten-thousandths, so sums are exact. Null for anything that is not a plain non-negative decimal. */
export function toUnits(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null
  const text = String(value).trim()
  if (!/^\d+(\.\d{1,4})?$/.test(text)) return null
  return Math.round(Number(text) * UNIT)
}

export function fromUnits(units: number): string {
  const whole = Math.trunc(units / UNIT)
  const fraction = Math.abs(units % UNIT)
  return fraction === 0 ? String(whole) : `${whole}.${String(fraction).padStart(4, '0').replace(/0+$/, '')}`
}

/** A positive amount with at most four decimals, as the backend accepts it. */
export function isPositiveAmount(value: string): boolean {
  const units = toUnits(value)
  return units !== null && units > 0
}

// ---------------------------------------------------------------- allocation drafts and validation

export interface AllocationDraft {
  key: string
  type: PaymentAllocationType
  amount: string
  periodYear?: number
  periodMonth?: number
  loanId?: string
  /** MEMBER_CREDIT only: why the excess is held. */
  creditPurpose?: CreditPurpose
  /** The outstanding amount the API reported when the line was chosen; null when unknown. */
  maxUnits: number | null
}

export type SubmissionProblem =
  | { code: 'NO_ALLOCATIONS' }
  | { code: 'INVALID_TOTAL' }
  | { code: 'INVALID_ALLOCATION_AMOUNT'; key: string }
  | { code: 'EXCEEDS_OUTSTANDING'; key: string }
  | { code: 'DUPLICATE_TARGET'; key: string }
  | { code: 'TOTAL_MISMATCH'; allocatedUnits: number; totalUnits: number }

export function allocationTargetKey(draft: AllocationDraft): string {
  if (draft.type === 'CONTRIBUTION') return `C:${draft.periodYear}-${draft.periodMonth}`
  // a loan prepayment and a repayment of the same loan are the same target: a payment can name a loan only once
  if (draft.type === 'LOAN_REPAYMENT' || draft.creditPurpose === 'LOAN_PREPAYMENT') return `L:${draft.loanId}`
  return `K:${draft.creditPurpose}`
}

export function allocatedUnits(drafts: AllocationDraft[]): number {
  return drafts.reduce((sum, d) => sum + (toUnits(d.amount) ?? 0), 0)
}

/**
 * Advisory validation of a payment before it is sent. Total = sum of allocations; each line positive and within the
 * outstanding amount the API reported. Held credit has no outstanding cap: it is excess money, classified explicitly.
 */
export function validateSubmission(totalAmount: string, drafts: AllocationDraft[]): SubmissionProblem[] {
  const problems: SubmissionProblem[] = []
  if (drafts.length === 0) problems.push({ code: 'NO_ALLOCATIONS' })
  const total = toUnits(totalAmount)
  if (total === null || total <= 0) problems.push({ code: 'INVALID_TOTAL' })
  const seen = new Set<string>()
  for (const draft of drafts) {
    const units = toUnits(draft.amount)
    if (units === null || units <= 0) {
      problems.push({ code: 'INVALID_ALLOCATION_AMOUNT', key: draft.key })
      continue
    }
    if (draft.maxUnits !== null && units > draft.maxUnits) {
      problems.push({ code: 'EXCEEDS_OUTSTANDING', key: draft.key })
    }
    const target = allocationTargetKey(draft)
    if (seen.has(target)) problems.push({ code: 'DUPLICATE_TARGET', key: draft.key })
    seen.add(target)
  }
  const allocated = allocatedUnits(drafts)
  if (drafts.length > 0 && total !== null && total > 0 && allocated !== total) {
    problems.push({ code: 'TOTAL_MISMATCH', allocatedUnits: allocated, totalUnits: total })
  }
  return problems
}

/** What a contribution row still needs. Never negative. */
export function contributionOutstandingUnits(contribution: Contribution): number {
  const expected = toUnits(contribution.expectedAmount) ?? 0
  const paid = toUnits(contribution.paidAmount) ?? 0
  return Math.max(expected - paid, 0)
}

/** Principal + interest + penalty still owed on a loan, as the API reports them. */
export function loanOutstandingUnits(loan: Loan): number {
  return (
    (toUnits(loan.outstandingPrincipal) ?? 0) +
    (toUnits(loan.outstandingInterest) ?? 0) +
    (toUnits(loan.outstandingPenalty) ?? 0)
  )
}

export function isOpenContribution(contribution: Contribution): boolean {
  return (
    contribution.status !== 'WAIVED' &&
    contribution.status !== 'CANCELLED' &&
    contributionOutstandingUnits(contribution) > 0
  )
}

export function isRepayableLoan(loan: Loan): boolean {
  return (loan.status === 'ACTIVE' || loan.status === 'OVERDUE') && loanOutstandingUnits(loan) > 0
}

// ---------------------------------------------------------------- status

export function paymentStatusColor(status: PaymentStatus): 'default' | 'info' | 'warning' | 'success' | 'error' {
  switch (status) {
    case 'SUBMITTED':
      return 'info'
    case 'UNDER_REVIEW':
      return 'warning'
    case 'VERIFIED':
      return 'warning'
    case 'POSTED':
      return 'success'
    case 'REJECTED':
      return 'error'
    case 'REVERSED':
      return 'default'
  }
}

export const verificationMethodLabelKey = (method: VerificationMethod) => `payments.verification.methods.${method}`

/** Which verification methods can confirm a payment made this way (mirrors the backend rule). */
export function verificationMethodsFor(paymentMethod: PaymentMethod): VerificationMethod[] {
  switch (paymentMethod) {
    case 'CASH':
      return ['CASH_RECEIPT_CONFIRMED']
    case 'MTN_MOMO':
    case 'AIRTEL_MONEY':
      return ['MOBILE_MONEY_RECONCILED', 'PROVIDER_CONFIRMED']
    case 'BANK_TRANSFER':
      return ['BANK_RECONCILED', 'PROVIDER_CONFIRMED']
    case 'OTHER':
      return ['MOBILE_MONEY_RECONCILED', 'BANK_RECONCILED', 'PROVIDER_CONFIRMED', 'CASH_RECEIPT_CONFIRMED']
  }
}

/** Only cash may omit the external reference. */
export function verificationNeedsReference(method: VerificationMethod): boolean {
  return method !== 'CASH_RECEIPT_CONFIRMED'
}

// ---------------------------------------------------------------- permissions and separation of duties

export type PermissionUser = { id?: string; roles?: string[]; permissions?: string[] } | null | undefined

/** The write authority the backend requires for each allocation type of a payment. */
export function requiredAuthorities(types: PaymentAllocationType[]): string[] {
  const needed = new Set<string>()
  for (const type of types) {
    needed.add(type === 'LOAN_REPAYMENT' ? PERMISSION_LOAN_WRITE : PERMISSION_CONTRIBUTION_WRITE)
  }
  return [...needed]
}

/** True when the user holds the write authority of every allocation type (the backend re-checks all of it). */
export function holdsAuthorities(user: PermissionUser, types: PaymentAllocationType[]): boolean {
  return requiredAuthorities(types).every((permission) => hasPermission(user, permission))
}

export type DutyBlocker = 'PAYER' | 'SUBMITTER' | 'VERIFIER' | 'EVIDENCE_RECORDER'

/** Why the signed-in user must not verify (payer, submitter) or post (also verifier, evidence recorder) this payment. */
export function dutyBlockers(
  action: 'verify' | 'post',
  userId: string | undefined,
  payment: Pick<MemberPayment, 'memberUserId' | 'submittedBy' | 'verifiedBy'>,
  posting?: Pick<PaymentPosting, 'verifiedBy' | 'evidenceCompletedBy'> | null,
): DutyBlocker[] {
  if (!userId) return []
  const blockers: DutyBlocker[] = []
  if (payment.memberUserId === userId) blockers.push('PAYER')
  if (payment.submittedBy && payment.submittedBy === userId) blockers.push('SUBMITTER')
  if (action === 'post') {
    const verifier = posting?.verifiedBy ?? payment.verifiedBy
    if (verifier && verifier === userId) blockers.push('VERIFIER')
    if (posting?.evidenceCompletedBy && posting.evidenceCompletedBy === userId) blockers.push('EVIDENCE_RECORDER')
  }
  return blockers
}

// ---------------------------------------------------------------- backend error codes

export function apiErrorCode(error: unknown): string | null {
  if (axios.isAxiosError(error)) {
    const code = (error.response?.data as { code?: unknown } | undefined)?.code
    return typeof code === 'string' && code ? code : null
  }
  return null
}

export function apiErrorStatus(error: unknown): number | null {
  return axios.isAxiosError(error) ? (error.response?.status ?? null) : null
}

/** A request that never got an answer (timeout, dropped connection): the outcome of a financial action is unknown. */
export function isIndeterminateFailure(error: unknown): boolean {
  return axios.isAxiosError(error) && !error.response
}

/**
 * Codes the backend actually returns for payment, verification and posting problems (see PaymentPostingService,
 * MemberPaymentService and the loan engine). Anything else is shown with the backend's own message.
 */
export const POSTING_ERROR_CODES = [
  'PAYMENT_ACCOUNT_IN_USE',
  'PAYMENT_CANNOT_BE_WITHDRAWN',
  'POOL_EXCEEDS_AVAILABLE_FUNDS',
  'POOL_OPENING_EXISTS',
  'POOL_OPENING_TOO_LATE',
  'POOL_EXPENSE_EXCEEDS_RESERVE',
  'POOL_REASON_REQUIRED',
  'POOL_DATE_IN_FUTURE',
  'POOL_ENTRY_CONFLICT',
  'POOL_ENTRY_TYPE_REQUIRED',
  'ROTATION_POOL_NOT_APPLICABLE',
  'MIGRATION_STRATEGY_CONFLICT',
  'MIGRATION_STRATEGY_LOCKED',
  'MIGRATION_NEEDS_REVIEW',
  'MIGRATION_CUTOVER_REQUIRED',
  'MIGRATION_CUTOVER_MISMATCH',
  'OPENING_BALANCES_FIRST',
  'CREDIT_INSUFFICIENT',
  'CREDIT_PURPOSE_MISMATCH',
  'INVALID_CREDIT_APPLICATION',
  'MEMBER_NOT_ACTIVE',
  'RECEIPT_INDICATES_FAILURE',
  'RECEIPT_INDICATES_PENDING',
  'RECEIPT_NEEDS_REVIEW',
  'PAYMENT_NOT_VERIFIED',
  'PAYMENT_EVIDENCE_MISSING',
  'ALLOCATION_TOTAL_MISMATCH',
  'ALLOCATION_EXCEEDS_OUTSTANDING',
  'MEMBER_CREDIT_UNSUPPORTED',
  'CONTRIBUTION_PERIOD_CLOSED',
  'OBLIGATION_NOT_FOUND',
  'LOAN_NOT_FOUND',
  'LOAN_ALLOCATION_INCOMPLETE',
  'LOAN_BREAKDOWN_MISMATCH',
  'REPAYMENT_EXCEEDS_OUTSTANDING',
  'ROTATION_ATTRIBUTION_UNRESOLVED',
  'PAYMENT_POSTING_CONFLICT',
  'POSTING_CONFLICT',
  'POSTING_TOTAL_MISMATCH',
  'PAYER_NOT_ACTIVE_MEMBER',
  'PAYMENT_ALREADY_APPLIED',
  'NOTHING_TO_RETRY',
  'INVALID_ALLOCATION',
  'INVALID_PAYMENT_TRANSITION',
  'VERIFICATION_EVIDENCE_REQUIRED',
  'VERIFICATION_REFERENCE_REQUIRED',
  'VERIFICATION_METHOD_MISMATCH',
  'VERIFICATION_EVIDENCE_EXISTS',
  'EVIDENCE_COMPLETION_REQUIRED',
  'DUPLICATE_PAYMENT_REFERENCE',
  'IDEMPOTENCY_KEY_REUSED',
  'CONCURRENT_UPDATE',
  'REVERSAL_BLOCKED_LATER_REPAYMENT',
  'REVERSAL_BLOCKED_PENALTY',
  'REVERSAL_BLOCKED_CREDIT_APPLIED',
  'REVERSAL_BLOCKED_LOAN_STATE',
  'REPAYMENT_ALREADY_REVERSED',
  'CREDIT_ALREADY_REVERSED',
  'VERIFIED_PAYMENT_CANNOT_BE_REJECTED',
  'PAYMENT_NOT_POSTED',
  'REVERSAL_ALREADY_REQUESTED',
  'REVERSAL_NOT_OPEN',
  'REVERSAL_NOTHING_POSTED',
  'REVERSAL_BLOCKED_LATER_CHANGES',
  'REVERSAL_BLOCKED_DISTRIBUTED',
  'ROTATION_DISTRIBUTION_CONSISTENCY',
  'REASON_REQUIRED',
  'ROTATION_PAYOUT_NOT_APPLICABLE',
  'ROTATION_PERIOD_NOT_PAYABLE',
  'RECIPIENT_NOT_ACTIVE',
  'PAYOUT_ALREADY_OPEN',
  'NO_ENTITLEMENT',
  'INSUFFICIENT_AVAILABLE_FUNDS',
  'PAYOUT_EXCEEDS_ENTITLEMENT',
  'PAYOUT_EXCEEDS_AVAILABLE',
  'INVALID_PAYOUT_TRANSITION',
  'PAYOUT_ALREADY_DISBURSED',
  'DISBURSEMENT_REFERENCE_REQUIRED',
  'RECEIPT_NOT_UPLOADED',
  'RECEIPT_TOO_LARGE',
  'RECEIPT_UNREADABLE',
  'TRUSTED_CONFIRMATION_REQUIRED',
  'VERIFICATION_BLOCKED_BY_CHECK',
  'VERIFICATION_METHOD_NOT_CONFIRMED',
  'VERIFIER_RECORDED_TRANSACTION',
  'TRANSACTION_ALREADY_USED',
  'CASH_CONFIRMATION_NOT_APPLICABLE',
  'CASH_CONFIRMATION_NOTE_REQUIRED',
  'PAYMENT_NOT_OPEN',
  'IMPORT_EMPTY',
  'IMPORT_TOO_LARGE',
  'INVALID_TRUSTED_SOURCE',
  'INVALID_CHANNEL',
  'SOURCE_CHANNEL_MISMATCH',
  'INVALID_PAYMENT_REFERENCE',
  'INVALID_AMOUNT',
  'INVALID_CURRENCY',
  'RECONCILIATION_CONTRIBUTION_REQUIRED',
  'RECONCILIATION_METHOD_REQUIRED',
  'RECONCILIATION_BASIS_REQUIRED',
  'RECONCILIATION_REFERENCE_REQUIRED',
  'RECONCILIATION_AMOUNT_INVALID',
  'RECONCILIATION_AMOUNT_EXCEEDS_UNVERIFIED',
  'RECONCILIATION_ALREADY_OPEN',
  'RECONCILIATION_REFERENCE_REUSED',
  'RECONCILIATION_DUPLICATE',
  'RECONCILIATION_NO_ROTATION_BINDING',
  'RECONCILIATION_NOT_ELIGIBLE',
  'RECONCILIATION_NOT_OPEN',
  'RECONCILIATION_DECLINE_REASON_REQUIRED',
  'HISTORICAL_IMPORT_HAS_ERRORS',
  'HISTORICAL_VARIANCE_UNACKNOWLEDGED',
  'HISTORICAL_IMPORT_STALE',
  'HISTORICAL_IMPORT_CANCELLED',
  'HISTORICAL_IMPORT_POSTED',
  'HISTORICAL_IMPORT_DUPLICATE_ROWS',
] as const

/** The translation key for a known code, or null. Unknown codes are never translated into guessed text. */
export function errorKeyFor(code: string | null | undefined): string | null {
  return code && (POSTING_ERROR_CODES as readonly string[]).includes(code) ? `payments.errors.${code}` : null
}

/** The attribution failure that must never offer a workaround (no current recipient, no guess, no force). */
export const UNRESOLVED_ATTRIBUTION_CODE = 'ROTATION_ATTRIBUTION_UNRESOLVED'

export function isUnresolvedAttribution(code: string | null | undefined): boolean {
  return code === UNRESOLVED_ATTRIBUTION_CODE
}

export function bpsToPercent(bps: number | null | undefined): string {
  if (bps === null || bps === undefined) return ''
  const percent = bps / 100
  return `${Number.isInteger(percent) ? percent : percent.toFixed(2)}%`
}
