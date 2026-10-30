import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'

export const EXPENSE_TYPES = [
  'MEETING',
  'MEALS_AND_REFRESHMENTS',
  'TRANSPORT',
  'STATIONERY_AND_PRINTING',
  'COMMUNICATION',
  'VENUE',
  'SUPPORT_AND_GIFTS',
  'OTHER',
] as const

export type EvidenceCheck = 'MATCH' | 'MISMATCH' | 'UNREADABLE' | 'STATEMENT_LINE'

export interface Expense {
  id: string
  expenseType: string
  amount: number
  date: string
  description?: string | null
  reference?: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  recordedBy?: string | null
  approvedBy?: string | null
  rejectionReason?: string | null
  evidenceKey?: string | null
  evidenceFileName?: string | null
  evidenceCheck?: EvidenceCheck | null
  evidenceAmountRead?: number | null
  evidenceNote?: string | null
  statementLineId?: string | null
  mine: boolean
}

export interface ExpensesOverview {
  canRecord: boolean
  canApprove: boolean
  expenses: Expense[]
  pendingCount: number
  pendingAmount: number
  approvedThisMonth: number
  byTypeThisYear: Record<string, number>
  bankChargeLinesToRecord: number
}

export interface BankChargeLine {
  lineId: string
  statementId: string
  statementFile?: string | null
  date: string
  narration: string
  amount: number
  lineStatus: string
}

const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/expenses`

export async function fetchExpenses(cooperativeId: string): Promise<ExpensesOverview> {
  return unwrapApiData((await apiClient.get<ApiResponse<ExpensesOverview>>(base(cooperativeId))).data)
}

export async function recordExpense(
  cooperativeId: string,
  input: { file: File; expenseType: string; amount: string; date: string; description: string; reference?: string; explanation?: string },
): Promise<Expense> {
  const form = new FormData()
  form.append('file', input.file)
  form.append('expenseType', input.expenseType)
  form.append('amount', input.amount)
  form.append('date', input.date)
  form.append('description', input.description)
  if (input.reference) form.append('reference', input.reference)
  if (input.explanation) form.append('explanation', input.explanation)
  const response = await apiClient.post<ApiResponse<Expense>>(base(cooperativeId), form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 90000,
  })
  return unwrapApiData(response.data)
}

export async function fetchBankChargeLines(cooperativeId: string): Promise<BankChargeLine[]> {
  return unwrapApiData((await apiClient.get<ApiResponse<BankChargeLine[]>>(`${base(cooperativeId)}/bank-charge-lines`)).data)
}

export async function recordBankCharges(cooperativeId: string, lineIds: string[]): Promise<Expense[]> {
  return unwrapApiData(
    (await apiClient.post<ApiResponse<Expense[]>>(`${base(cooperativeId)}/bank-charges`, { lineIds })).data,
  )
}

export async function approveExpense(cooperativeId: string, id: string): Promise<void> {
  await apiClient.post(`/cooperatives/${cooperativeId}/transactions/${id}/approve`)
}

export async function rejectExpense(cooperativeId: string, id: string, reason: string): Promise<void> {
  await apiClient.post(`/cooperatives/${cooperativeId}/transactions/${id}/reject`, { rejectionReason: reason })
}
