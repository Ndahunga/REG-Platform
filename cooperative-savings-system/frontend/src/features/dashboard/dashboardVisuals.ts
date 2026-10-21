import type {
  DashboardInsights,
  DashboardSummary,
  FineFollowUpRow,
  FrequentBorrowerRow,
  FinesInsights,
  LargestActiveInvestmentRow,
  LoansInsights,
  OverdueLoanInsightRow,
  RepaymentReliabilityRow,
  TopContributorRow,
} from '@/shared/types/dashboard'

/**
 * Pure data builders for the dashboard visuals. They only reshape values the dashboard APIs already
 * return, never compute new financial figures, and never emit NaN/Infinity (zero is valid data).
 */

export function toFiniteNumber(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** A value that is really present: zero counts as present, blank/NaN/Infinity do not. */
export function isAvailable(value: unknown): boolean {
  return value !== undefined && value !== null && value !== '' && Number.isFinite(Number(value))
}

function nonNegative(value: unknown): number {
  return Math.max(toFiniteNumber(value), 0)
}

// ---------------------------------------------------------------------------------------------
// At a glance
// ---------------------------------------------------------------------------------------------

export interface MemberStatusData {
  total: number
  active: number
  inactive: number
}

/** Active vs inactive = max(total - active, 0); no other status is invented. */
export function memberStatusData(
  summary: Pick<DashboardSummary, 'totalMembers' | 'activeMembers'>,
): MemberStatusData {
  const total = nonNegative(summary.totalMembers)
  const active = nonNegative(summary.activeMembers)
  return { total, active, inactive: Math.max(total - active, 0) }
}

export interface ContributionMixData {
  regular: number
  special: number
  total: number
}

/** The backend defines actual contributions as regular + special, so these are true parts of a whole. */
export function contributionMixData(
  summary: Pick<DashboardSummary, 'regularContributionsTotal' | 'specialContributionsTotal'>,
): ContributionMixData {
  const regular = nonNegative(summary.regularContributionsTotal)
  const special = nonNegative(summary.specialContributionsTotal)
  return { regular, special, total: regular + special }
}

export interface LoanActivityData {
  issued: number
  repaid: number
  issuedCount: number
  outstandingPrincipal: number
  hasActivity: boolean
}

/** Issued and repaid are separate flows (not parts of one total), so they are charted as bars. */
export function loanActivityData(loans: LoansInsights | undefined): LoanActivityData {
  const issued = toFiniteNumber(loans?.issuedAmountCurrentMonth)
  const repaid = toFiniteNumber(loans?.repaidCurrentMonth)
  return {
    issued,
    repaid,
    issuedCount: Math.max(Math.trunc(toFiniteNumber(loans?.issuedCountCurrentMonth)), 0),
    outstandingPrincipal: toFiniteNumber(loans?.outstandingPrincipal),
    hasActivity: issued !== 0 || repaid !== 0,
  }
}

export interface FineActivityData {
  issued: number
  collected: number
  issuedCount: number
  hasActivity: boolean
}

/** Issued vs collected fines are separate flows this month, so bars (not a donut) are used. */
export function fineActivityData(fines: FinesInsights | undefined): FineActivityData {
  const issued = toFiniteNumber(fines?.issuedAmountCurrentMonth)
  const collected = toFiniteNumber(fines?.collectedCurrentMonth)
  return {
    issued,
    collected,
    issuedCount: Math.max(Math.trunc(toFiniteNumber(fines?.issuedCountCurrentMonth)), 0),
    hasActivity: issued !== 0 || collected !== 0,
  }
}

// ---------------------------------------------------------------------------------------------
// Ranked horizontal bars
// ---------------------------------------------------------------------------------------------

export interface RankedBarDatum {
  id: string
  rank: number
  label: string
  value: number
  /** Present only when the viewer may open the member/record. */
  href?: string
  /** Secondary values shown in the tooltip and the accessible data table. */
  meta: Record<string, number | string>
}

type HrefFor = (id: string) => string | undefined

export function topContributorBars(rows: TopContributorRow[], hrefFor?: HrefFor): RankedBarDatum[] {
  return rows.map((row) => ({
    id: row.memberId,
    rank: row.rank,
    label: row.displayName,
    value: nonNegative(row.amount),
    href: hrefFor?.(row.memberId),
    meta: {},
  }))
}

export function fineFollowUpBars(rows: FineFollowUpRow[], hrefFor?: HrefFor): RankedBarDatum[] {
  return rows.map((row) => ({
    id: row.memberId,
    rank: row.rank,
    label: row.displayName,
    value: nonNegative(row.outstandingAmount),
    href: hrefFor?.(row.memberId),
    meta: {
      fineCount: Math.max(Math.trunc(toFiniteNumber(row.fineCount)), 0),
      issuedAmount: toFiniteNumber(row.issuedAmount),
      paidAmount: toFiniteNumber(row.paidAmount),
    },
  }))
}

export function overdueLoanBars(rows: OverdueLoanInsightRow[], hrefFor?: HrefFor): RankedBarDatum[] {
  return rows.map((row) => ({
    id: row.memberId,
    rank: row.rank,
    label: row.displayName,
    value: nonNegative(row.outstandingPrincipal),
    href: hrefFor?.(row.memberId),
    meta: { overdueLoanCount: Math.max(Math.trunc(toFiniteNumber(row.overdueLoanCount)), 0) },
  }))
}

export function frequentBorrowerBars(
  rows: FrequentBorrowerRow[],
  hrefFor?: HrefFor,
): RankedBarDatum[] {
  return rows.map((row) => ({
    id: row.memberId,
    rank: row.rank,
    label: row.displayName,
    value: nonNegative(row.totalPrincipalBorrowed),
    href: hrefFor?.(row.memberId),
    meta: { loans: Math.max(Math.trunc(toFiniteNumber(row.numberOfLoansDisbursed)), 0) },
  }))
}

export function investmentBars(rows: LargestActiveInvestmentRow[]): RankedBarDatum[] {
  return rows.map((row) => ({
    id: row.investmentId,
    rank: row.rank,
    label: row.name,
    value: nonNegative(row.remainingCapital),
    meta: {
      originalCapital: toFiniteNumber(row.originalCapital),
      profitReturned: toFiniteNumber(row.profitReturned),
      status: row.status,
    },
  }))
}

// ---------------------------------------------------------------------------------------------
// Repayment reliability (100% stacked)
// ---------------------------------------------------------------------------------------------

export interface ReliabilityBarDatum {
  id: string
  label: string
  href?: string
  onTime: number
  paidLate: number
  pastDue: number
  evaluated: number
  /** Percentages of the three outcome counts (they sum to 100, or all 0 when there is no data). */
  onTimePct: number
  paidLatePct: number
  pastDuePct: number
  /** The API on-time rate, or null when it is not a finite number. */
  onTimeRate: number | null
}

/**
 * The backend counts every evaluated installment as exactly one of on-time / paid-late / unpaid-past-due,
 * so the three segments are true parts of the evaluated total.
 */
export function reliabilityBars(
  rows: RepaymentReliabilityRow[],
  hrefFor?: HrefFor,
): ReliabilityBarDatum[] {
  return rows.map((row) => {
    const onTime = nonNegative(row.installmentsPaidOnTime)
    const paidLate = nonNegative(row.installmentsPaidLate)
    const pastDue = nonNegative(row.installmentsUnpaidPastDue)
    const base = onTime + paidLate + pastDue
    const pct = (n: number) => (base > 0 ? (n / base) * 100 : 0)
    const rate = Number(row.onTimeRate)
    return {
      id: row.memberId,
      label: row.displayName,
      href: hrefFor?.(row.memberId),
      onTime,
      paidLate,
      pastDue,
      evaluated: nonNegative(row.installmentsDue),
      onTimePct: pct(onTime),
      paidLatePct: pct(paidLate),
      pastDuePct: pct(pastDue),
      onTimeRate: Number.isFinite(rate) ? rate : null,
    }
  })
}

// ---------------------------------------------------------------------------------------------
// "What's happening" summary text
// ---------------------------------------------------------------------------------------------

export type Translate = (key: string, options?: Record<string, unknown>) => string

export interface SchemeSummaryInput {
  summary?: DashboardSummary
  insights?: DashboardInsights
  /** Role-based gates: a metric is only mentioned when the viewer may already see it. */
  show: { members: boolean; contributions: boolean; loans: boolean; fines: boolean }
}

function joinList(parts: string[], andWord: string): string {
  if (parts.length <= 1) return parts[0] ?? ''
  return `${parts.slice(0, -1).join(', ')} ${andWord} ${parts[parts.length - 1]}`
}

/**
 * Deterministic factual sentences built only from API values the viewer's role may see. Metrics that
 * are unavailable are omitted; zero is treated as valid data. No evaluative wording.
 */
export function buildSchemeSummary(
  input: SchemeSummaryInput,
  t: Translate,
  money: (value: number) => string,
): string[] {
  const { summary, insights, show } = input
  const lines: string[] = []

  if (show.members && summary && isAvailable(summary.totalMembers) && isAvailable(summary.activeMembers)) {
    const status = memberStatusData(summary)
    lines.push(t('dashboard.glance.summary.membersActive', { count: status.total, active: status.active }))
  }

  if (insights) {
    const parts: string[] = []
    if (show.contributions && isAvailable(insights.contributions?.currentMonth)) {
      parts.push(
        t('dashboard.glance.summary.partContributions', {
          amount: money(toFiniteNumber(insights.contributions?.currentMonth)),
        }),
      )
    }
    if (show.loans) {
      const loans = loanActivityData(insights.loans)
      if (isAvailable(insights.loans?.issuedAmountCurrentMonth)) {
        parts.push(t('dashboard.glance.summary.partLoansIssued', { amount: money(loans.issued) }))
      }
      if (isAvailable(insights.loans?.repaidCurrentMonth)) {
        parts.push(t('dashboard.glance.summary.partLoansRepaid', { amount: money(loans.repaid) }))
      }
    }
    if (show.fines && isAvailable(insights.fines?.collectedCurrentMonth)) {
      const fines = fineActivityData(insights.fines)
      parts.push(t('dashboard.glance.summary.partFines', { amount: money(fines.collected) }))
    }
    if (parts.length > 0) {
      lines.push(
        t('dashboard.glance.summary.thisMonth', {
          parts: joinList(parts, t('dashboard.glance.summary.and')),
        }),
      )
    }
  }

  if (show.loans) {
    const outstandingRaw = isAvailable(summary?.outstandingLoanPrincipal)
      ? summary?.outstandingLoanPrincipal
      : insights?.loans?.outstandingPrincipal
    const hasOutstanding = isAvailable(outstandingRaw)
    const overdue = summary?.overdueLoansCount
    const hasOverdue = isAvailable(overdue)

    if (hasOutstanding && hasOverdue) {
      lines.push(
        t('dashboard.glance.summary.outstandingWithOverdue', {
          amount: money(toFiniteNumber(outstandingRaw)),
          count: Math.max(Math.trunc(toFiniteNumber(overdue)), 0),
        }),
      )
    } else if (hasOutstanding) {
      lines.push(
        t('dashboard.glance.summary.outstanding', { amount: money(toFiniteNumber(outstandingRaw)) }),
      )
    } else if (hasOverdue) {
      lines.push(
        t('dashboard.glance.summary.overdueOnly', {
          count: Math.max(Math.trunc(toFiniteNumber(overdue)), 0),
        }),
      )
    }
  }

  return lines
}
