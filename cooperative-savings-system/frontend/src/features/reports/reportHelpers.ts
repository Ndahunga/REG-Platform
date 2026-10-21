import dayjs from 'dayjs'
import type { ContributionImportStatus } from '@/shared/types/contributionImport'
import type { ReportTypeInfo } from '@/shared/types/report'
import { isValidRwandanPhone } from '@/shared/utils/rwandaCooperative'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

const MEMBER_TYPES = new Set([
  'CONTRIBUTIONS',
  'SPECIAL_CONTRIBUTIONS',
  'LOANS',
  'REPAYMENTS',
  'FINES',
  'FINE_PAYMENTS',
  'PAYOUTS',
  'FINANCIAL_LEDGER',
  'AUDIT_LOGS',
])

const STATUS_TYPES = new Set([
  'CONTRIBUTIONS',
  'SPECIAL_CONTRIBUTIONS',
  'LOANS',
  'FINES',
  'INVESTMENTS',
  'INCOME',
  'EXPENSES',
  'PAYOUTS',
])

/**
 * Status values the backend accepts per report type (it silently ignores a value that does not belong to the
 * report's own status enum, which would export unfiltered data). Each entry carries its i18n label key.
 */
const STATUS_OPTIONS: Record<string, { values: string[]; labelPrefix: string }> = {
  CONTRIBUTIONS: {
    values: ['PENDING', 'PARTIALLY_PAID', 'PAID', 'WAIVED', 'CANCELLED'],
    labelPrefix: 'contributions.status',
  },
  SPECIAL_CONTRIBUTIONS: { values: ['PENDING', 'APPROVED', 'REJECTED'], labelPrefix: 'contributions.specialStatus' },
  INCOME: { values: ['PENDING', 'APPROVED', 'REJECTED'], labelPrefix: 'transactions.status' },
  EXPENSES: { values: ['PENDING', 'APPROVED', 'REJECTED'], labelPrefix: 'transactions.status' },
  LOANS: {
    values: ['PENDING', 'AWAITING_SECOND_APPROVAL', 'APPROVED', 'ACTIVE', 'OVERDUE', 'REJECTED', 'CLOSED', 'WRITTEN_OFF'],
    labelPrefix: 'loans.status',
  },
  FINES: { values: ['UNPAID', 'PARTIALLY_PAID', 'PAID', 'WAIVED', 'CANCELLED'], labelPrefix: 'fines.status' },
  INVESTMENTS: {
    values: ['PLANNED', 'ACTIVE', 'PARTIALLY_RETURNED', 'COMPLETED', 'CANCELLED', 'LOSS_RECORDED'],
    labelPrefix: 'investments.status',
  },
  PAYOUTS: { values: ['DRAFT', 'PREVIEWED', 'CONFIRMED', 'PAID', 'CANCELLED'], labelPrefix: 'payouts.status' },
}

export interface ReportStatusOption {
  value: string
  labelKey: string
}

export function reportStatusOptions(type: string): ReportStatusOption[] {
  const entry = STATUS_OPTIONS[type]
  if (!entry) return []
  return entry.values.map((value) => ({ value, labelKey: `${entry.labelPrefix}.${value}` }))
}

/** A status filter is valid when it is empty (All) or belongs to the selected report type. */
export function isReportStatusValidFor(type: string, status: string): boolean {
  return !status || reportStatusOptions(type).some((option) => option.value === status)
}

const TRANSACTION_TYPE_TYPES = new Set(['FINANCIAL_LEDGER', 'FULL_FINANCIAL'])

const NO_DATE_RANGE_TYPES = new Set(['MEMBERS'])

export function reportTypeLabelKey(type: string): string {
  return `reports.types.${type}`
}

export function reportSupportsFromTo(
  type: string,
  meta?: ReportTypeInfo | null,
): boolean {
  if (meta?.supportsFromDate != null || meta?.supportsToDate != null) {
    return Boolean(meta.supportsFromDate ?? meta.supportsToDate)
  }
  return !NO_DATE_RANGE_TYPES.has(type)
}

export function reportSupportsMember(
  type: string,
  meta?: ReportTypeInfo | null,
): boolean {
  if (meta?.supportsMember != null) return meta.supportsMember
  return MEMBER_TYPES.has(type)
}

export function reportSupportsStatus(
  type: string,
  meta?: ReportTypeInfo | null,
): boolean {
  if (meta?.supportsStatus != null) return meta.supportsStatus
  return STATUS_TYPES.has(type)
}

export function reportSupportsTransactionType(
  type: string,
  meta?: ReportTypeInfo | null,
): boolean {
  if (meta?.supportsTransactionType != null) return meta.supportsTransactionType
  return TRANSACTION_TYPE_TYPES.has(type)
}

export function importRowValidityColor(valid: boolean): ChipColor {
  return valid ? 'success' : 'error'
}

export function importRowValidityLabelKey(valid: boolean): string {
  return valid ? 'reports.import.rowValid' : 'reports.import.rowInvalid'
}

export function importStatusColor(status: string): ChipColor {
  switch (status) {
    case 'UPLOADED':
    case 'VALIDATING':
      return 'info'
    case 'VALIDATED':
    case 'READY':
      return 'warning'
    case 'INVALID':
      return 'error'
    case 'CONFIRMED':
      return 'success'
    case 'CANCELLED':
      return 'secondary'
    case 'FAILED':
      return 'error'
    default:
      return 'default'
  }
}

export function importStatusLabelKey(status: string): string {
  return `reports.import.status.${status}`
}

export function canConfirmImport(
  status: ContributionImportStatus | string | undefined,
  validCount: number,
  isAdmin: boolean,
): boolean {
  if (!isAdmin || validCount <= 0) return false
  return status === 'UPLOADED' || status === 'VALIDATED' || status == null
}

export function canCancelImport(
  status: ContributionImportStatus | string | undefined,
  isAdmin: boolean,
): boolean {
  if (!isAdmin) return false
  return status === 'UPLOADED' || status === 'VALIDATED' || status == null
}

export function isValidReportWhatsAppRecipient(raw: string): boolean {
  return isValidRwandanPhone(raw)
}

export function defaultExportFilename(reportType: string): string {
  const safe = reportType
    .toLowerCase()
    .replace(/_/g, '-')
    .replace(/[^a-z0-9-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  return `${safe || 'report'}.pdf`
}

export type ReportTimelineIssue =
  | 'required'
  | 'fromAfterTo'
  | 'futureFrom'
  | 'futureTo'

export function defaultReportFromDate(today = dayjs(), _registrationDate?: string | null): string {
  return today.startOf('year').format('YYYY-MM-DD')
}

export function defaultReportToDate(today = dayjs()): string {
  return today.format('YYYY-MM-DD')
}

export function validateReportTimeline(
  fromDate: string,
  toDate: string,
  today = dayjs(),
  _registrationDate?: string | null,
): ReportTimelineIssue | null {
  if (!fromDate || !toDate) return 'required'
  const from = dayjs(fromDate)
  const to = dayjs(toDate)
  if (!from.isValid() || !to.isValid()) return 'required'
  const todayDate = today.startOf('day')
  if (from.isAfter(todayDate, 'day')) return 'futureFrom'
  if (to.isAfter(todayDate, 'day')) return 'futureTo'
  if (to.isBefore(from, 'day')) return 'fromAfterTo'
  return null
}
