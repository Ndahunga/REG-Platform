export type SpecialCampaignStatus = 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'CANCELLED'

export type SpecialContributionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export const SPECIAL_CAMPAIGN_STATUSES: SpecialCampaignStatus[] = [
  'DRAFT',
  'ACTIVE',
  'CLOSED',
  'CANCELLED',
]

export const SPECIAL_CONTRIBUTION_STATUSES: SpecialContributionStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
]

export interface SpecialCampaign {
  id: string
  cooperativeId?: string
  name: string
  purpose?: string | null
  description?: string | null
  suggestedAmount?: string | number | null
  targetAmount?: string | number | null
  startDate?: string | null
  endDate?: string | null
  status: SpecialCampaignStatus | string
  createdBy?: string | null
  createdAt?: string
  updatedAt?: string
  contributionCount?: number
  raisedAmount?: string | number
}

export interface SpecialCampaignCreateRequest {
  name: string
  purpose?: string
  description?: string
  suggestedAmount?: string | number
  targetAmount?: string | number
  startDate?: string
  endDate?: string
}

export interface SpecialCampaignUpdateRequest {
  name?: string
  purpose?: string
  description?: string
  suggestedAmount?: string | number
  targetAmount?: string | number
  startDate?: string
  endDate?: string
}

export interface SpecialCampaignStatusUpdateRequest {
  status: SpecialCampaignStatus
}

export interface SpecialContribution {
  id: string
  campaignId: string
  cooperativeId?: string
  memberUserId: string
  /** Display name as the backend sends it (SpecialContributionResponse). */
  memberName?: string | null
  fullName?: string
  username?: string
  amount: string | number
  contributionDate?: string | null
  paymentReference?: string | null
  notes?: string | null
  status: SpecialContributionStatus | string
  reviewedBy?: string | null
  reviewedAt?: string | null
  reviewNotes?: string | null
  recordedBy?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface SpecialContributionSubmitRequest {
  amount: string | number
  contributionDate?: string
  paymentReference?: string
  notes?: string
  memberUserId?: string
}

export interface SpecialContributionReviewRequest {
  reviewNotes?: string
}

export function mapSpecialCampaign(raw: SpecialCampaign): SpecialCampaign {
  return {
    ...raw,
    id: String(raw.id),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
  }
}

export function mapSpecialContribution(raw: SpecialContribution): SpecialContribution {
  return {
    ...raw,
    id: String(raw.id),
    campaignId: String(raw.campaignId),
    memberUserId: String(raw.memberUserId),
    fullName: (raw.fullName || raw.memberName || '').trim() || undefined,
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
  }
}
