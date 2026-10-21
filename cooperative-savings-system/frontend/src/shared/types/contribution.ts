import type { ApprovalEvent } from './approval'
import { mapApprovalEvent } from './approval'

export type ContributionStatus =
  | 'PENDING'
  | 'PARTIALLY_PAID'
  | 'PAID'
  | 'WAIVED'
  | 'CANCELLED'

export type ContributionReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export const CONTRIBUTION_REVIEW_STATUSES: ContributionReviewStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
]

export const CONTRIBUTION_STATUSES: ContributionStatus[] = [
  'PENDING',
  'PARTIALLY_PAID',
  'PAID',
  'WAIVED',
  'CANCELLED',
]

export type ContributionOperatingModel = 'RETAINED' | 'ROTATING' | 'HYBRID' | string

/**
 * Obligation view (Task 3B-2). Every amount here is an EXPECTATION taken from the period's snapshot, never money
 * that was collected or received. `dueDate` is null when the scheme has no configured due day.
 */
export interface ContributionObligationFields {
  dueDate?: string | null
  overdue?: boolean
  overdueAmount?: string | number | null
  obligationSnapshotted?: boolean
  operatingModel?: ContributionOperatingModel | null
  rotatingBps?: number | null
  retainedBps?: number | null
  expectedRotatingAmount?: string | number | null
  expectedRetainedAmount?: string | number | null
  rotationPeriodId?: string | null
}

export interface ContributionDueDayEntry {
  effectiveYear: number
  effectiveMonth: number
  dueDay: number
  createdAt?: string
}

export interface ContributionDueDay {
  configured: boolean
  currentDueDay: number | null
  pending: ContributionDueDayEntry | null
  history: ContributionDueDayEntry[]
}

export interface ContributionDueDayRequest {
  dueDay: number
  confirmed: boolean
}

/**
 * A row of the monthly period grid. The backend returns the member's display name as `memberName`
 * (ContributionResponse); `fullName`/`username` are tolerated for older/other shapes. After mapping,
 * `fullName` and `username` are always strings (possibly empty), never undefined.
 */
export interface ContributionPeriodLine extends ContributionObligationFields {
  memberUserId: string
  fullName: string
  username: string
  memberName?: string | null
  expectedAmount: string | number
  paidAmount: string | number
  outstandingAmount: string | number
  status: ContributionStatus | string
  paymentDate?: string | null
  paymentReference?: string | null
  notes?: string | null
  contributionId?: string | null
  /** Review state of a member-submitted contribution; PENDING rows are locked in the monthly grid. */
  reviewStatus?: string | null
  /** False when the row only reflects the expected default and has never been saved. */
  persisted?: boolean
}

export interface ContributionPeriodGrid {
  year: number
  month: number
  expectedAmountDefault?: string | number
  lines: ContributionPeriodLine[]
}

export interface ContributionLineSaveRequest {
  memberUserId: string
  paidAmount: string | number
  paymentDate?: string | null
  paymentReference?: string | null
  notes?: string | null
  status?: ContributionStatus | string | null
}

export interface ContributionPeriodSaveRequest {
  lines: ContributionLineSaveRequest[]
}

export interface Contribution extends ContributionObligationFields {
  id: string
  cooperativeId?: string
  memberUserId: string
  fullName?: string
  username?: string
  year: number
  month: number
  shareCount?: number | null
  expectedAmount: string | number
  paidAmount: string | number
  outstandingAmount: string | number
  remainingAmount?: string | number | null
  status: ContributionStatus | string
  paymentDate?: string | null
  paymentReference?: string | null
  notes?: string | null
  recordedBy?: string | null
  memberName?: string | null
  submittedAmount?: string | number | null
  evidenceFileKey?: string | null
  submittedBy?: string | null
  submittedByName?: string | null
  submittedAt?: string | null
  reviewedBy?: string | null
  reviewedByName?: string | null
  reviewedAt?: string | null
  reviewStatus?: ContributionReviewStatus | string | null
  rejectionReason?: string | null
  approvalHistory?: ApprovalEvent[]
  createdAt?: string
  updatedAt?: string
}

export interface ContributionSubmitRequest {
  year?: number
  month?: number
  amount: string | number
  paymentDate: string
  paymentReference?: string
  evidenceFileKey?: string
  notes?: string
}

export interface ContributionPeriodPreview {
  contributionId?: string | null
  cooperativeId?: string
  memberUserId?: string
  year: number
  month: number
  shareCount: number
  requiredAmount: string | number
  paidAmount: string | number
  pendingSubmittedAmount?: string | number | null
  remainingAmount: string | number
  paymentDate?: string | null
  dueDate?: string | null
  status?: ContributionStatus | string | null
  reviewStatus?: ContributionReviewStatus | string | null
  awaitingReview?: boolean
  canSubmit?: boolean
  overdue?: boolean
  overdueAmount?: string | number | null
  operatingModel?: ContributionOperatingModel | null
  rotatingBps?: number | null
  retainedBps?: number | null
  expectedRotatingAmount?: string | number | null
  expectedRetainedAmount?: string | number | null
}

export interface ContributionReviewRequest {
  rejectionReason: string
}

export interface ContributionUpdateRequest {
  paidAmount?: string | number
  paymentDate?: string | null
  paymentReference?: string | null
  notes?: string | null
  status?: ContributionStatus | string | null
}

export interface ContributionSummary {
  year?: number | null
  month?: number | null
  expectedTotal: string | number
  paidTotal: string | number
  outstandingTotal: string | number
  memberCount?: number
  paidCount?: number
  pendingCount?: number
}

export interface ContributionListQuery {
  q?: string
  status?: string
  year?: number
  month?: number
  memberUserId?: string
  fromDate?: string
  toDate?: string
  page?: number
  size?: number
  sort?: string
}

export type ContributionPeriodLineRaw = Omit<ContributionPeriodLine, 'fullName' | 'username'> & {
  fullName?: string | null
  username?: string | null
}

export function mapContributionPeriodLine(raw: ContributionPeriodLineRaw): ContributionPeriodLine {
  return {
    ...raw,
    memberUserId: String(raw.memberUserId),
    // The API sends the display name as memberName; keep fullName when a caller already supplies it.
    fullName: (raw.fullName || raw.memberName || '').trim(),
    username: (raw.username || '').trim(),
    contributionId: raw.contributionId != null ? String(raw.contributionId) : null,
    expectedAmount: raw.expectedAmount ?? 0,
    paidAmount: raw.paidAmount ?? 0,
    outstandingAmount: raw.outstandingAmount ?? 0,
  }
}

export function mapContribution(raw: Contribution): Contribution {
  return {
    ...raw,
    id: String(raw.id),
    memberUserId: String(raw.memberUserId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    fullName: raw.fullName || raw.memberName || undefined,
    approvalHistory: (raw.approvalHistory ?? []).map(mapApprovalEvent),
  }
}
