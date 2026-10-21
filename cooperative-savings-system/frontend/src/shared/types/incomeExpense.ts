export type IncomeExpenseCategory =
  | 'OTHER_INCOME'
  | 'GENERAL_EXPENSE'
  | 'INTEREST_EXPENSE'
  | 'ADJUSTMENT'

export type IncomeExpenseApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type LedgerEffect = 'CREDIT' | 'DEBIT'

export const INCOME_EXPENSE_CATEGORIES: IncomeExpenseCategory[] = [
  'OTHER_INCOME',
  'GENERAL_EXPENSE',
  'INTEREST_EXPENSE',
  'ADJUSTMENT',
]

export const INCOME_CATEGORIES: IncomeExpenseCategory[] = ['OTHER_INCOME']

export const EXPENSE_CATEGORIES: IncomeExpenseCategory[] = [
  'GENERAL_EXPENSE',
  'INTEREST_EXPENSE',
]

export const INCOME_EXPENSE_APPROVAL_STATUSES: IncomeExpenseApprovalStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
]

export const LEDGER_EFFECTS: LedgerEffect[] = ['CREDIT', 'DEBIT']

export interface IncomeExpenseTransaction {
  id: string
  cooperativeId?: string
  category: IncomeExpenseCategory | string
  amount: string | number
  transactionDate: string
  reference?: string | null
  description?: string | null
  notes?: string | null
  supportingFileKey?: string | null
  /** Required when category is ADJUSTMENT. */
  ledgerEffect?: LedgerEffect | string | null
  approvalStatus: IncomeExpenseApprovalStatus | string
  recordedBy?: string | null
  approvedBy?: string | null
  approvedAt?: string | null
  rejectionReason?: string | null
  currency?: string
  createdAt?: string
  updatedAt?: string
  version?: number
}

export interface IncomeExpenseCreateRequest {
  category: IncomeExpenseCategory | string
  amount: string | number
  transactionDate: string
  reference?: string
  description?: string
  notes?: string
  supportingFileKey?: string
  ledgerEffect?: LedgerEffect | string
}

export interface IncomeExpenseReviewRequest {
  reviewNotes?: string
  rejectionReason?: string
}

export interface IncomeExpenseListQuery {
  q?: string
  category?: string
  status?: string
  from?: string
  to?: string
  page?: number
  size?: number
  sort?: string
}

export function mapIncomeExpenseTransaction(
  raw: IncomeExpenseTransaction,
): IncomeExpenseTransaction {
  return {
    ...raw,
    id: String(raw.id),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    category: raw.category || 'OTHER_INCOME',
    amount: raw.amount ?? 0,
    approvalStatus: raw.approvalStatus || 'PENDING',
    recordedBy: raw.recordedBy != null ? String(raw.recordedBy) : null,
    approvedBy: raw.approvedBy != null ? String(raw.approvedBy) : null,
    currency: raw.currency || 'RWF',
  }
}
