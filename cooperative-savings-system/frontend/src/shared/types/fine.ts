export type FineStatus = 'UNPAID' | 'PARTIALLY_PAID' | 'PAID' | 'WAIVED' | 'CANCELLED'

export type FineType = 'AUTOMATIC' | 'MANUAL'

export type FineCalculationMode = 'FIXED' | 'PROGRESSIVE'

export type FinePaymentStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export const FINE_STATUSES: FineStatus[] = [
  'UNPAID',
  'PARTIALLY_PAID',
  'PAID',
  'WAIVED',
  'CANCELLED',
]

export const FINE_TYPES: FineType[] = ['AUTOMATIC', 'MANUAL']

export const FINE_CALCULATION_MODES: FineCalculationMode[] = ['FIXED', 'PROGRESSIVE']

export const FINE_PAYMENT_STATUSES: FinePaymentStatus[] = ['PENDING', 'APPROVED', 'REJECTED']

export interface FineSettings {
  id?: string
  cooperativeId?: string
  autoFinesEnabled: boolean
  fineMode: FineCalculationMode | string
  baseFineAmount: string | number
  dailyIncrement: string | number
  graceDays: number
  currency?: string
  createdAt?: string
  updatedAt?: string
}

export interface FineSettingsUpdateRequest {
  autoFinesEnabled?: boolean
  fineMode: FineCalculationMode | string
  baseFineAmount: string | number
  dailyIncrement: string | number
  graceDays: number
  currency?: string
}

export interface Fine {
  id: string
  cooperativeId?: string
  memberUserId: string
  memberName?: string | null
  fullName?: string | null
  username?: string | null
  fineType: FineType | string
  calculationMode: FineCalculationMode | string
  sourceContributionId?: string | null
  contributionYear?: number | null
  contributionMonth?: number | null
  baseAmount: string | number
  dailyIncrementSnapshot?: string | number | null
  overdueDays?: number
  totalAmount: string | number
  paidAmount?: string | number | null
  outstandingAmount?: string | number | null
  reason?: string | null
  notes?: string | null
  issuedDate?: string | null
  dueDate?: string | null
  status: FineStatus | string
  issuedBy?: string | null
  currency?: string
  createdAt?: string
  updatedAt?: string
}

export interface FineCreateRequest {
  memberUserId: string
  calculationMode?: FineCalculationMode | string
  amount?: string | number
  baseAmount?: string | number
  dailyIncrement?: string | number
  overdueDays?: number
  reason?: string
  notes?: string
  issuedDate?: string
  dueDate?: string
}

export interface FineGenerateRequest {
  year?: number
  month?: number
}

export interface FineGenerateResponse {
  createdCount: number
  skippedDuplicates: number
  skippedNotOverdue: number
  created?: Fine[]
}

export type FinePaymentMethod = 'CASH' | 'MOBILE_MONEY' | 'BANK_TRANSFER' | 'OTHER'

export const FINE_PAYMENT_METHODS: FinePaymentMethod[] = [
  'CASH',
  'MOBILE_MONEY',
  'BANK_TRANSFER',
  'OTHER',
]

export interface FinePayment {
  id: string
  fineId: string
  cooperativeId?: string
  memberUserId?: string
  memberName?: string | null
  username?: string | null
  fineReason?: string | null
  fineTotalAmount?: string | number | null
  fineOutstandingAmount?: string | number | null
  amount: string | number
  paymentDate: string
  paymentReference?: string | null
  paymentMethod?: FinePaymentMethod | string | null
  paymentMethodDetail?: string | null
  notes?: string | null
  evidenceFileKey?: string | null
  status: FinePaymentStatus | string
  submittedBy?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  reviewNotes?: string | null
  currency?: string
  createdAt?: string
  updatedAt?: string
}

export interface FinePaymentCreateRequest {
  amount: string | number
  paymentDate: string
  paymentMethod: FinePaymentMethod | string
  paymentMethodDetail?: string
  paymentReference?: string
  notes?: string
  evidenceFileKey?: string
}

export interface FinePaymentReviewRequest {
  reviewNotes?: string
}

export interface FineListQuery {
  q?: string
  status?: string
  memberUserId?: string
  page?: number
  size?: number
  sort?: string
}

export interface FinePaymentQueueQuery {
  q?: string
  status?: string
  fromDate?: string
  toDate?: string
  page?: number
  size?: number
  sort?: string
}

export function mapFineSettings(raw: FineSettings): FineSettings {
  return {
    ...raw,
    id: raw.id != null ? String(raw.id) : undefined,
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    autoFinesEnabled: Boolean(raw.autoFinesEnabled),
    fineMode: raw.fineMode || 'FIXED',
    baseFineAmount: raw.baseFineAmount ?? 0,
    dailyIncrement: raw.dailyIncrement ?? 0,
    graceDays: Number(raw.graceDays ?? 0),
  }
}

export function mapFine(raw: Fine): Fine {
  return {
    ...raw,
    id: String(raw.id),
    memberUserId: String(raw.memberUserId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    sourceContributionId:
      raw.sourceContributionId != null ? String(raw.sourceContributionId) : null,
    fineType: raw.fineType || 'MANUAL',
    calculationMode: raw.calculationMode || 'FIXED',
    baseAmount: raw.baseAmount ?? 0,
    totalAmount: raw.totalAmount ?? 0,
    paidAmount: raw.paidAmount ?? 0,
    outstandingAmount: raw.outstandingAmount ?? 0,
    overdueDays: Number(raw.overdueDays ?? 0),
    status: raw.status || 'UNPAID',
    issuedBy: raw.issuedBy != null ? String(raw.issuedBy) : null,
  }
}

export function mapFinePayment(raw: FinePayment): FinePayment {
  return {
    ...raw,
    id: String(raw.id),
    fineId: String(raw.fineId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    memberUserId: raw.memberUserId != null ? String(raw.memberUserId) : undefined,
    memberName: raw.memberName ?? null,
    username: raw.username ?? null,
    fineReason: raw.fineReason ?? null,
    fineTotalAmount: raw.fineTotalAmount ?? undefined,
    fineOutstandingAmount: raw.fineOutstandingAmount ?? undefined,
    amount: raw.amount ?? 0,
    status: raw.status || 'PENDING',
    submittedBy: raw.submittedBy != null ? String(raw.submittedBy) : null,
    reviewedBy: raw.reviewedBy != null ? String(raw.reviewedBy) : null,
  }
}

export function mapFineGenerateResponse(raw: FineGenerateResponse): FineGenerateResponse {
  return {
    createdCount: Number(raw.createdCount ?? 0),
    skippedDuplicates: Number(raw.skippedDuplicates ?? 0),
    skippedNotOverdue: Number(raw.skippedNotOverdue ?? 0),
    created: (raw.created ?? []).map(mapFine),
  }
}

export function fineDisplayName(
  fine: Pick<Fine, 'memberName' | 'fullName' | 'username' | 'memberUserId'>,
): string {
  return fine.memberName || fine.fullName || fine.username || fine.memberUserId
}

export function finePaymentDisplayName(
  payment: Pick<FinePayment, 'memberName' | 'username' | 'memberUserId'>,
): string {
  return payment.memberName || payment.username || payment.memberUserId || '—'
}
