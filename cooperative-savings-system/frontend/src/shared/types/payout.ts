export type PayoutRunStatus =
  | 'DRAFT'
  | 'PREVIEWED'
  | 'CONFIRMED'
  | 'PAID'
  | 'CANCELLED'

export type PayoutLineStatus = 'PENDING' | 'CONFIRMED' | 'PAID'

export const PAYOUT_RUN_STATUSES: PayoutRunStatus[] = [
  'DRAFT',
  'PREVIEWED',
  'CONFIRMED',
  'PAID',
  'CANCELLED',
]

export const PAYOUT_LINE_STATUSES: PayoutLineStatus[] = ['PENDING', 'CONFIRMED', 'PAID']

export interface PayoutLine {
  id: string
  payoutRunId?: string
  cooperativeId?: string
  memberUserId: string
  memberName?: string | null
  memberFirstName?: string | null
  memberLastName?: string | null
  eligibleContributionAmount: string | number
  percentage: string | number
  payoutAmount: string | number
  status: PayoutLineStatus | string
  currency?: string
  createdAt?: string
  /** Present on /my and member history responses. */
  periodFrom?: string | null
  periodTo?: string | null
  runName?: string | null
  runStatus?: PayoutRunStatus | string | null
  runId?: string | null
}

export interface PayoutRun {
  id: string
  cooperativeId?: string
  name?: string | null
  title?: string | null
  periodFrom: string
  periodTo: string
  includeRegular: boolean
  includeSpecial: boolean
  availableFundSnapshot?: string | number | null
  payoutPoolAmount: string | number
  totalEligibleContributions?: string | number | null
  currency?: string
  status: PayoutRunStatus | string
  confirmedAt?: string | null
  confirmedBy?: string | null
  paidAt?: string | null
  paidBy?: string | null
  createdBy?: string | null
  notes?: string | null
  createdAt?: string
  updatedAt?: string
  version?: number
  lines?: PayoutLine[]
}

export interface PayoutPreviewRequest {
  periodFrom: string
  periodTo: string
  includeRegular: boolean
  includeSpecial: boolean
  payoutPoolAmount?: string | number
  name?: string
  notes?: string
}

export interface PayoutStatement {
  cooperativeId?: string
  cooperativeName?: string | null
  payoutRunId?: string
  name?: string | null
  periodFrom?: string
  periodTo?: string
  generatedAt?: string
  currency?: string
  payoutPoolAmount?: string | number
  totalEligibleContributions?: string | number
  totalPayoutAmount?: string | number
  status?: PayoutRunStatus | string
  includeRegular?: boolean
  includeSpecial?: boolean
  lines?: PayoutLine[]
}

export interface PayoutListQuery {
  q?: string
  status?: string
  page?: number
  size?: number
  sort?: string
}

export function payoutRunDisplayName(run: Pick<PayoutRun, 'name' | 'title' | 'periodFrom' | 'periodTo'>): string {
  const named = (run.name || run.title || '').trim()
  if (named) return named
  if (run.periodFrom && run.periodTo) return `${run.periodFrom} → ${run.periodTo}`
  return run.periodFrom || run.periodTo || 'Payout'
}

export function payoutLineMemberName(line: PayoutLine): string {
  if (line.memberName?.trim()) return line.memberName.trim()
  const composed = `${line.memberFirstName ?? ''} ${line.memberLastName ?? ''}`.trim()
  if (composed) return composed
  return line.memberUserId || '—'
}

export function mapPayoutLine(raw: PayoutLine): PayoutLine {
  return {
    ...raw,
    id: String(raw.id),
    payoutRunId:
      raw.payoutRunId != null
        ? String(raw.payoutRunId)
        : raw.runId != null
          ? String(raw.runId)
          : undefined,
    runId: raw.runId != null ? String(raw.runId) : raw.payoutRunId != null ? String(raw.payoutRunId) : null,
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    memberUserId: String(raw.memberUserId),
    eligibleContributionAmount: raw.eligibleContributionAmount ?? 0,
    percentage: raw.percentage ?? 0,
    payoutAmount: raw.payoutAmount ?? 0,
    status: raw.status || 'PENDING',
    currency: raw.currency || 'RWF',
  }
}

export function mapPayoutRun(raw: PayoutRun): PayoutRun {
  return {
    ...raw,
    id: String(raw.id),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    name: raw.name ?? raw.title ?? null,
    title: raw.title ?? raw.name ?? null,
    periodFrom: raw.periodFrom || '',
    periodTo: raw.periodTo || '',
    includeRegular: Boolean(raw.includeRegular),
    includeSpecial: Boolean(raw.includeSpecial),
    availableFundSnapshot: raw.availableFundSnapshot ?? null,
    payoutPoolAmount: raw.payoutPoolAmount ?? 0,
    totalEligibleContributions: raw.totalEligibleContributions ?? 0,
    currency: raw.currency || 'RWF',
    status: raw.status || 'PREVIEWED',
    confirmedBy: raw.confirmedBy != null ? String(raw.confirmedBy) : null,
    paidBy: raw.paidBy != null ? String(raw.paidBy) : null,
    createdBy: raw.createdBy != null ? String(raw.createdBy) : null,
    lines: Array.isArray(raw.lines) ? raw.lines.map(mapPayoutLine) : undefined,
  }
}

export function mapPayoutStatement(raw: PayoutStatement): PayoutStatement {
  return {
    ...raw,
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    payoutRunId: raw.payoutRunId != null ? String(raw.payoutRunId) : undefined,
    currency: raw.currency || 'RWF',
    payoutPoolAmount: raw.payoutPoolAmount ?? 0,
    totalEligibleContributions: raw.totalEligibleContributions ?? 0,
    totalPayoutAmount: raw.totalPayoutAmount ?? 0,
    lines: Array.isArray(raw.lines) ? raw.lines.map(mapPayoutLine) : [],
  }
}
