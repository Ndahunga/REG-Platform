export type InvestmentStatus =
  | 'PLANNED'
  | 'ACTIVE'
  | 'PARTIALLY_RETURNED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'LOSS_RECORDED'

export const INVESTMENT_STATUSES: InvestmentStatus[] = [
  'PLANNED',
  'ACTIVE',
  'PARTIALLY_RETURNED',
  'COMPLETED',
  'CANCELLED',
  'LOSS_RECORDED',
]

export interface Investment {
  id: string
  cooperativeId?: string
  name: string
  description?: string | null
  amount: string | number
  expectedReturnAmount?: string | number | null
  expectedReturnDate?: string | null
  remainingCapital?: string | number | null
  totalCapitalReturned?: string | number | null
  totalProfitReturned?: string | number | null
  status: InvestmentStatus | string
  documentFileKey?: string | null
  activatedAt?: string | null
  completedAt?: string | null
  createdBy?: string | null
  currency?: string
  createdAt?: string
  updatedAt?: string
  version?: number
}

export interface InvestmentCreateRequest {
  name: string
  amount: string | number
  expectedReturnAmount?: string | number
  expectedReturnDate?: string
  description?: string
  documentFileKey?: string
}

export interface InvestmentReturn {
  id: string
  investmentId: string
  cooperativeId?: string
  returnDate: string
  capitalPortion: string | number
  profitPortion: string | number
  amountTotal: string | number
  notes?: string | null
  reference?: string | null
  recordedBy?: string | null
  currency?: string
  createdAt?: string
}

export interface InvestmentReturnCreateRequest {
  returnDate: string
  capitalPortion?: string | number
  profitPortion?: string | number
  notes?: string
  reference?: string
}

export interface InvestmentLossRequest {
  notes?: string
  reference?: string
}

export interface InvestmentListQuery {
  q?: string
  status?: string
  page?: number
  size?: number
  sort?: string
}

export function mapInvestment(raw: Investment): Investment {
  return {
    ...raw,
    id: String(raw.id),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    name: raw.name || '',
    amount: raw.amount ?? 0,
    remainingCapital: raw.remainingCapital ?? raw.amount ?? 0,
    totalCapitalReturned: raw.totalCapitalReturned ?? 0,
    totalProfitReturned: raw.totalProfitReturned ?? 0,
    status: raw.status || 'PLANNED',
    createdBy: raw.createdBy != null ? String(raw.createdBy) : null,
    currency: raw.currency || 'RWF',
  }
}

export function mapInvestmentReturn(raw: InvestmentReturn): InvestmentReturn {
  return {
    ...raw,
    id: String(raw.id),
    investmentId: String(raw.investmentId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    capitalPortion: raw.capitalPortion ?? 0,
    profitPortion: raw.profitPortion ?? 0,
    amountTotal: raw.amountTotal ?? 0,
    recordedBy: raw.recordedBy != null ? String(raw.recordedBy) : null,
    currency: raw.currency || 'RWF',
  }
}
