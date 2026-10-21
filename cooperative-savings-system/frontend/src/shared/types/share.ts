export const SHARE_PURCHASE_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'] as const

export type SharePurchaseStatus = (typeof SHARE_PURCHASE_STATUSES)[number]

export interface ShareValuation {
  cooperativeId: string
  currency?: string
  availableFunds: string | number
  outstandingLoans: string | number
  unpaidInterest: string | number
  unpaidPenalties: string | number
  otherAssets: string | number
  liabilities: string | number
  totalIkiminaValue: string | number
  totalExistingShares: number
  currentShareValue: string | number
  calculatedAt?: string
  baseSharePrice?: string | number | null
  usedBaseSharePrice: boolean
  canPurchase: boolean
  purchaseBlockedReason?: string | null
}

export interface SharePurchase {
  id: string
  cooperativeId: string
  memberUserId: string
  memberName?: string | null
  numberOfShares: number
  pricePerShare: string | number
  currentShareValue?: string | number
  totalAmount: string | number
  currency?: string
  status: SharePurchaseStatus | string
  requestedBy?: string
  requestedAt?: string
  reviewedBy?: string | null
  reviewedByName?: string | null
  reviewedAt?: string | null
  rejectionReason?: string | null
  paymentDate?: string | null
  paymentReference?: string | null
  evidenceFileKey?: string | null
  notes?: string | null
  createdAt?: string
  updatedAt?: string
  valuationSnapshotId?: string | null
  pricingValuation?: ShareValuation | null
}

export interface SharePurchaseSubmitRequest {
  numberOfShares: number
  memberUserId?: string
  paymentDate: string
  paymentReference?: string
  evidenceFileKey: string
  notes?: string
}

export interface SharePurchaseReviewRequest {
  rejectionReason?: string
}

export function mapShareValuation(raw: ShareValuation): ShareValuation {
  return {
    ...raw,
    cooperativeId: String(raw.cooperativeId),
    totalExistingShares: Number(raw.totalExistingShares ?? 0),
  }
}

export function mapSharePurchase(raw: SharePurchase): SharePurchase {
  return {
    ...raw,
    id: String(raw.id),
    cooperativeId: String(raw.cooperativeId),
    memberUserId: String(raw.memberUserId),
    numberOfShares: Number(raw.numberOfShares ?? 0),
    pricingValuation: raw.pricingValuation
      ? mapShareValuation(raw.pricingValuation)
      : raw.pricingValuation,
  }
}
