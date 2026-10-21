import type { ContributionStatus } from '@/shared/types/contribution'
import { normalizeDecimalString } from '@/shared/utils/formatMoney'
import {
  checkMoney,
  exceedsLength,
  isFutureIsoDate,
  isMoneyValid,
  isValidIsoDate,
  type MoneyProblem,
} from '@/shared/utils/formValidation'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export function contributionStatusColor(status: string): ChipColor {
  switch (status) {
    case 'PAID':
    case 'APPROVED':
      return 'success'
    case 'PARTIALLY_PAID':
      return 'warning'
    case 'PENDING':
      return 'info'
    case 'WAIVED':
      return 'default'
    case 'CANCELLED':
    case 'REJECTED':
      return 'error'
    case 'ACTIVE':
      return 'success'
    case 'DRAFT':
      return 'default'
    case 'CLOSED':
      return 'secondary'
    default:
      return 'default'
  }
}

export function contributionStatusLabelKey(status: string): string {
  return `contributions.status.${status}`
}

/** Compare decimal money strings/numbers without relying on binary float equality. */
export function moneyToScaledInt(value: string | number, scale = 4): bigint {
  const normalized = normalizeDecimalString(value)
  const sign = normalized.startsWith('-') ? -1n : 1n
  const raw = sign === -1n ? normalized.slice(1) : normalized
  const [whole = '0', fraction = ''] = raw.split('.')
  const padded = fraction.padEnd(scale, '0').slice(0, scale)
  return sign * (BigInt(whole || '0') * 10n ** BigInt(scale) + BigInt(padded || '0'))
}

export function computeOutstandingAmount(
  expected: string | number,
  paid: string | number,
): string {
  const scale = 4
  const outstanding = moneyToScaledInt(expected, scale) - moneyToScaledInt(paid || 0, scale)
  if (outstanding <= 0n) return '0'
  const negative = outstanding < 0n
  const abs = negative ? -outstanding : outstanding
  const divisor = 10n ** BigInt(scale)
  const whole = abs / divisor
  const fraction = abs % divisor
  const fractionStr = fraction.toString().padStart(scale, '0').replace(/0+$/, '')
  const body = fractionStr ? `${whole}.${fractionStr}` : `${whole}`
  return negative ? `-${body}` : body
}

export function deriveContributionStatus(
  expected: string | number,
  paid: string | number,
  current?: string,
): ContributionStatus {
  if (current === 'WAIVED' || current === 'CANCELLED') {
    return current
  }
  const paidScaled = moneyToScaledInt(paid || 0)
  if (paidScaled <= 0n) return 'PENDING'
  if (paidScaled >= moneyToScaledInt(expected || 0)) return 'PAID'
  return 'PARTIALLY_PAID'
}

export type ContributionTab =
  | 'monthly'
  | 'submit'
  | 'approvals'
  | 'share-approvals'
  | 'history'
  | 'special'

/**
 * Tabs of the Contributions page. `share-approvals` (Share Purchase Approvals) is its own direct view and is
 * only offered to users who may review share purchases (`canReviewShares`, i.e. the existing
 * selectCanReviewSharePurchases rule); it is never added for users who cannot record contributions.
 */
export function contributionTabsForUser(
  canRecord: boolean,
  isSuperAdmin: boolean,
  canReviewShares = false,
): ContributionTab[] {
  const shareApprovals: ContributionTab[] = canRecord && canReviewShares ? ['share-approvals'] : []
  if (isSuperAdmin) {
    return ['monthly', 'approvals', ...shareApprovals, 'history', 'special']
  }
  if (canRecord) {
    return ['monthly', 'submit', 'approvals', ...shareApprovals, 'history', 'special']
  }
  return ['submit', 'history', 'special']
}

export function isNonNegativeMoney(value: string): boolean {
  const trimmed = value.trim()
  if (!trimmed) return false
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return false
  try {
    return moneyToScaledInt(trimmed) >= 0n
  } catch {
    return false
  }
}

/** Display name for a contribution row: full name, else username. Never a UUID. Empty when neither exists. */
export function contributionLineName(line: { fullName?: string | null; username?: string | null }): string {
  return (line.fullName || '').trim() || (line.username || '').trim()
}

/** Backend limits for contribution lines (contributions.payment_reference / notes columns). */
export const CONTRIBUTION_REFERENCE_MAX = 128
export const CONTRIBUTION_NOTES_MAX = 2000

export type ContributionLineField = 'paidAmount' | 'paymentDate' | 'paymentReference' | 'notes'

export interface ContributionLineErrors {
  paidAmount?: MoneyProblem
  paymentDate?: 'invalid' | 'future'
  paymentReference?: 'tooLong'
  notes?: 'tooLong'
}

export interface ContributionLineInput {
  paidAmountInput: string
  paymentDateInput: string
  paymentReferenceInput: string
  notesInput: string
}

/**
 * Validates one editable grid row against the backend rules: amount is a non-negative number with at most
 * 4 decimals (blank means 0 = nothing paid), the payment date is a real date that is not in the future,
 * and the reference/notes fit their columns. Returns only the failing fields.
 */
export function validateContributionLine(
  line: ContributionLineInput,
  today?: string,
): ContributionLineErrors {
  const errors: ContributionLineErrors = {}
  const amountProblem = checkMoney(line.paidAmountInput.trim() || '0', { allowZero: true })
  if (amountProblem) errors.paidAmount = amountProblem
  const date = line.paymentDateInput.trim()
  if (date) {
    if (!isValidIsoDate(date)) errors.paymentDate = 'invalid'
    else if (isFutureIsoDate(date, today)) errors.paymentDate = 'future'
  }
  if (exceedsLength(line.paymentReferenceInput, CONTRIBUTION_REFERENCE_MAX)) errors.paymentReference = 'tooLong'
  if (exceedsLength(line.notesInput, CONTRIBUTION_NOTES_MAX)) errors.notes = 'tooLong'
  return errors
}

export function hasContributionLineErrors(errors: ContributionLineErrors): boolean {
  return Object.keys(errors).length > 0
}

/** Why the monthly grid will not edit a row. */
export type ContributionLineLock = 'terminal' | 'review'

/**
 * Rows the monthly batch editor must never touch: a WAIVED/CANCELLED contribution keeps its terminal
 * state, and one whose member submission is awaiting review is decided in Approvals, not overwritten here.
 */
export function contributionLineLock(line: {
  status?: string | null
  reviewStatus?: string | null
}): ContributionLineLock | null {
  if (line.status === 'WAIVED' || line.status === 'CANCELLED') return 'terminal'
  if (line.reviewStatus === 'PENDING') return 'review'
  return null
}

const trimmed = (value: string | null | undefined) => (value ?? '').trim()

function scaledAmountOrNull(input: string): bigint | null {
  const value = trimmed(input) || '0'
  return isMoneyValid(value, { allowZero: true }) ? moneyToScaledInt(value) : null
}

/**
 * Whether the user changed a row compared with the values it was loaded with. Harmless representation
 * differences are not changes: null vs '' for optional text, surrounding whitespace (the save payload
 * trims it), and equal amounts written differently ("5000" vs "5000.0000", blank vs "0").
 * Text that is not a valid amount is a change, so it gets validated instead of being ignored.
 */
export function isContributionLineDirty(
  original: ContributionLineInput,
  current: ContributionLineInput,
): boolean {
  const currentAmount = scaledAmountOrNull(current.paidAmountInput)
  if (currentAmount === null) return true
  const originalAmount = scaledAmountOrNull(original.paidAmountInput)
  const amountChanged =
    originalAmount === null
      ? trimmed(original.paidAmountInput) !== trimmed(current.paidAmountInput)
      : originalAmount !== currentAmount
  return (
    amountChanged ||
    trimmed(original.paymentDateInput) !== trimmed(current.paymentDateInput) ||
    trimmed(original.paymentReferenceInput) !== trimmed(current.paymentReferenceInput) ||
    trimmed(original.notesInput) !== trimmed(current.notesInput)
  )
}

/**
 * The paid amount to use for live derived values (outstanding, status) while the user is still typing.
 * Text that is not a valid amount counts as 0 so the grid keeps rendering; it is flagged on save instead.
 */
export function contributionEffectivePaid(input: string): string {
  const trimmed = input.trim()
  return trimmed && isMoneyValid(trimmed, { allowZero: true }) ? trimmed : '0'
}
