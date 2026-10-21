export type LedgerEntryStatus = 'APPROVED' | 'REVERSED' | 'PENDING'

export const LEDGER_TRANSACTION_TYPES = [
  'SHARE_PURCHASE',
  'REGULAR_CONTRIBUTION',
  'SPECIAL_CONTRIBUTION',
  'LOAN_DISBURSEMENT',
  'LOAN_PRINCIPAL_REPAYMENT',
  'LOAN_INTEREST_PAYMENT',
  'FINE_PAYMENT',
  'SOCIAL_CONTRIBUTION',
  'SOCIAL_DISBURSEMENT',
  'INVESTMENT_OUTFLOW',
  'INVESTMENT_CAPITAL_RETURN',
  'INVESTMENT_PROFIT',
  'OTHER_INCOME',
  'GENERAL_EXPENSE',
  'INTEREST_EXPENSE',
  'MEMBER_PAYOUT',
  'ADJUSTMENT',
  'REVERSAL',
] as const

export type LedgerTransactionType = (typeof LEDGER_TRANSACTION_TYPES)[number]

export interface LedgerEntry {
  id: string
  cooperativeId?: string
  memberUserId?: string | null
  transactionType: LedgerTransactionType | string
  debitAmount: string | number
  creditAmount: string | number
  currency?: string
  transactionDate: string
  reference?: string | null
  sourceEntityType?: string | null
  sourceEntityId?: string | null
  description?: string | null
  status?: LedgerEntryStatus | string
  recordedBy?: string | null
  approvedBy?: string | null
  reversesEntryId?: string | null
  createdAt?: string
}

export interface LedgerListQuery {
  transactionType?: string
  from?: string
  to?: string
  memberUserId?: string
  sourceEntityType?: string
  page?: number
  size?: number
  sort?: string
}

export function mapLedgerEntry(raw: LedgerEntry): LedgerEntry {
  return {
    ...raw,
    id: String(raw.id),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    memberUserId: raw.memberUserId != null ? String(raw.memberUserId) : null,
    transactionType: raw.transactionType || 'ADJUSTMENT',
    debitAmount: raw.debitAmount ?? 0,
    creditAmount: raw.creditAmount ?? 0,
    currency: raw.currency || 'RWF',
    status: raw.status || 'APPROVED',
    sourceEntityId: raw.sourceEntityId != null ? String(raw.sourceEntityId) : null,
    recordedBy: raw.recordedBy != null ? String(raw.recordedBy) : null,
    approvedBy: raw.approvedBy != null ? String(raw.approvedBy) : null,
    reversesEntryId: raw.reversesEntryId != null ? String(raw.reversesEntryId) : null,
  }
}
