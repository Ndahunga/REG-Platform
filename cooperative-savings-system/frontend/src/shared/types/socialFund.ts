import type { ApprovalEvent } from './approval'
import { mapApprovalEvent } from './approval'

export type SocialContributionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type SocialDisbursementStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'

export const SOCIAL_CONTRIBUTION_STATUSES: SocialContributionStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
]

export const SOCIAL_DISBURSEMENT_STATUSES: SocialDisbursementStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
  'CANCELLED',
]

export interface SocialFundSummary {
  balance: string | number
  totalApprovedContributions: string | number
  totalApprovedDisbursements: string | number
  pendingContributions?: number
  pendingDisbursements?: number
  currency?: string
}

export interface SocialFundSettings {
  id?: string
  cooperativeId?: string
  suggestedContributionAmount?: string | number | null
  enabled?: boolean
  currency?: string
  createdAt?: string
  updatedAt?: string
}

export interface SocialFundSettingsUpdateRequest {
  suggestedContributionAmount?: string | number | null
  enabled?: boolean
}

export interface SocialContribution {
  id: string
  cooperativeId?: string
  memberUserId: string
  memberName?: string | null
  fullName?: string | null
  username?: string | null
  amount: string | number
  contributionDate?: string | null
  paymentReference?: string | null
  notes?: string | null
  evidenceFileKey?: string | null
  status: SocialContributionStatus | string
  submittedBy?: string | null
  submittedByName?: string | null
  reviewedBy?: string | null
  reviewedByName?: string | null
  reviewedAt?: string | null
  reviewNotes?: string | null
  approvalHistory?: ApprovalEvent[]
  currency?: string
  createdAt?: string
  updatedAt?: string
}

export interface SocialContributionCreateRequest {
  amount: string | number
  contributionDate?: string
  paymentReference?: string
  notes?: string
  evidenceFileKey?: string
  /** Admin may submit on behalf of a member. */
  memberUserId?: string
}

export interface SocialContributionReviewRequest {
  reviewNotes?: string
}

export interface SocialDisbursement {
  id: string
  cooperativeId?: string
  beneficiaryMemberUserId: string
  beneficiaryName?: string | null
  memberName?: string | null
  fullName?: string | null
  username?: string | null
  amount: string | number
  disbursementDate?: string | null
  reason?: string | null
  notes?: string | null
  evidenceFileKey?: string | null
  status: SocialDisbursementStatus | string
  requestedBy?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  reviewNotes?: string | null
  currency?: string
  createdAt?: string
  updatedAt?: string
}

export interface SocialDisbursementCreateRequest {
  beneficiaryMemberUserId: string
  amount: string | number
  reason: string
  disbursementDate?: string
  notes?: string
  evidenceFileKey?: string
}

export interface SocialDisbursementReviewRequest {
  reviewNotes?: string
}

export interface SocialFundReport {
  from?: string | null
  to?: string | null
  balance?: string | number
  totalApprovedContributions: string | number
  totalApprovedDisbursements: string | number
  netChange?: string | number
  currency?: string
  contributions?: SocialContribution[]
  disbursements?: SocialDisbursement[]
}

export interface SocialFundListQuery {
  q?: string
  status?: string
  memberUserId?: string
  page?: number
  size?: number
  sort?: string
}

export function mapSocialFundSummary(raw: SocialFundSummary): SocialFundSummary {
  return {
    balance: raw.balance ?? 0,
    totalApprovedContributions: raw.totalApprovedContributions ?? 0,
    totalApprovedDisbursements: raw.totalApprovedDisbursements ?? 0,
    pendingContributions: Number(raw.pendingContributions ?? 0),
    pendingDisbursements: Number(raw.pendingDisbursements ?? 0),
    currency: raw.currency || 'RWF',
  }
}

export function mapSocialFundSettings(raw: SocialFundSettings): SocialFundSettings {
  return {
    ...raw,
    id: raw.id != null ? String(raw.id) : undefined,
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    suggestedContributionAmount: raw.suggestedContributionAmount ?? null,
    enabled: raw.enabled ?? true,
  }
}

export function mapSocialContribution(raw: SocialContribution): SocialContribution {
  return {
    ...raw,
    id: String(raw.id),
    memberUserId: String(raw.memberUserId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    amount: raw.amount ?? 0,
    status: raw.status || 'PENDING',
    submittedBy: raw.submittedBy != null ? String(raw.submittedBy) : null,
    reviewedBy: raw.reviewedBy != null ? String(raw.reviewedBy) : null,
    approvalHistory: (raw.approvalHistory ?? []).map(mapApprovalEvent),
  }
}

export function mapSocialDisbursement(raw: SocialDisbursement): SocialDisbursement {
  return {
    ...raw,
    id: String(raw.id),
    beneficiaryMemberUserId: String(raw.beneficiaryMemberUserId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    amount: raw.amount ?? 0,
    status: raw.status || 'PENDING',
    requestedBy: raw.requestedBy != null ? String(raw.requestedBy) : null,
    reviewedBy: raw.reviewedBy != null ? String(raw.reviewedBy) : null,
  }
}

export function mapSocialFundReport(raw: SocialFundReport): SocialFundReport {
  return {
    ...raw,
    balance: raw.balance ?? undefined,
    totalApprovedContributions: raw.totalApprovedContributions ?? 0,
    totalApprovedDisbursements: raw.totalApprovedDisbursements ?? 0,
    netChange: raw.netChange ?? undefined,
    currency: raw.currency || 'RWF',
    contributions: (raw.contributions ?? []).map(mapSocialContribution),
    disbursements: (raw.disbursements ?? []).map(mapSocialDisbursement),
  }
}

export function socialContributionDisplayName(
  row: Pick<SocialContribution, 'memberName' | 'fullName' | 'username' | 'memberUserId'>,
): string {
  return row.memberName || row.fullName || row.username || row.memberUserId
}

export function socialDisbursementDisplayName(
  row: Pick<
    SocialDisbursement,
    'beneficiaryName' | 'memberName' | 'fullName' | 'username' | 'beneficiaryMemberUserId'
  >,
): string {
  return (
    row.beneficiaryName ||
    row.memberName ||
    row.fullName ||
    row.username ||
    row.beneficiaryMemberUserId
  )
}
