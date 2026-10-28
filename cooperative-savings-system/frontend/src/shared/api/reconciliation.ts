import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'

export type LineStatus = 'MATCHED' | 'SUGGESTED' | 'CONFIRMED' | 'EXPLAINED' | 'UNMATCHED'
export const EXPLAIN_CATEGORIES = [
  'BANK_CHARGE',
  'BANK_INTEREST',
  'TRANSFER_BETWEEN_ACCOUNTS',
  'RECORDED_ELSEWHERE',
  'NOT_SCHEME_MONEY',
  'OTHER',
] as const

export interface StatementSummary {
  id: string
  source: 'BANK' | 'MOBILE_MONEY'
  accountLabel?: string | null
  originalFilename?: string | null
  periodFrom?: string | null
  periodTo?: string | null
  openingBalance?: number | null
  closingBalance?: number | null
  totalIn: number
  totalOut: number
  lineCount: number
  newLines: number
  warnings: string[]
  uploadedAt: string
  uploadedBy?: string | null
  byStatus: Record<string, number>
}

export interface Suggestion {
  kind: 'PAYMENT' | 'LEDGER' | 'EXPLAIN'
  ids: string[]
  category?: string | null
  label?: string | null
  score: number
  reasons: string[]
}

export interface MatchView {
  id: string
  kind: 'PAYMENT' | 'LEDGER'
  targetId: string
  label: string
  amount: number
  how: 'AUTOMATIC' | 'OFFICER'
  paymentStatus?: string | null
}

export interface StatementLine {
  id: string
  statementId: string
  lineNumber: number
  date: string
  narration: string
  reference?: string | null
  direction: 'IN' | 'OUT'
  amount: number
  balance?: number | null
  status: LineStatus
  category?: string | null
  note?: string | null
  suggestion?: Suggestion | null
  matches: MatchView[]
  decidedBy?: string | null
  decidedAt?: string | null
}

export interface OpenRecord {
  kind: 'PAYMENT' | 'LEDGER'
  id: string
  date: string
  member?: string | null
  amount: number
  direction: 'IN' | 'OUT'
  label: string
  status?: string | null
}

export interface BalanceCheck {
  asAt: string
  statementBalance: number
  appCash: number
  difference: number
}

export interface ReconciliationOverview {
  canEdit: boolean
  statements: StatementSummary[]
  lineCounts: Record<string, number>
  lineAmounts: Record<string, number>
  recordsNotOnStatement: number
  balance?: BalanceCheck | null
}

export interface StatementDetail {
  canEdit: boolean
  statement: StatementSummary
  lines: StatementLine[]
  notOnStatement: OpenRecord[]
  balance?: BalanceCheck | null
}

export interface MatchCandidate {
  kind: 'PAYMENT' | 'LEDGER'
  id: string
  memberName?: string | null
  amount: number
  date?: string | null
  reference?: string | null
  label: string
}

export interface VerifyResult {
  paymentId: string
  outcome: 'VERIFIED' | 'NOT_VERIFIED'
  message?: string | null
}

const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/reconciliation`

export async function fetchReconciliation(cooperativeId: string): Promise<ReconciliationOverview> {
  return unwrapApiData((await apiClient.get<ApiResponse<ReconciliationOverview>>(base(cooperativeId))).data)
}

export async function fetchStatement(cooperativeId: string, statementId: string): Promise<StatementDetail> {
  return unwrapApiData((await apiClient.get<ApiResponse<StatementDetail>>(`${base(cooperativeId)}/statements/${statementId}`)).data)
}

export async function fetchLinesByStatus(cooperativeId: string, status: LineStatus): Promise<StatementLine[]> {
  return unwrapApiData(
    (await apiClient.get<ApiResponse<StatementLine[]>>(`${base(cooperativeId)}/lines`, { params: { status } })).data,
  )
}

export async function uploadStatement(
  cooperativeId: string,
  file: File,
  source: 'BANK' | 'MOBILE_MONEY',
  accountLabel?: string,
): Promise<StatementSummary> {
  const form = new FormData()
  form.append('file', file)
  form.append('source', source)
  if (accountLabel) form.append('accountLabel', accountLabel)
  const response = await apiClient.post<ApiResponse<StatementSummary>>(`${base(cooperativeId)}/statements`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
  return unwrapApiData(response.data)
}

export async function fetchCandidates(cooperativeId: string, lineId: string): Promise<MatchCandidate[]> {
  return unwrapApiData(
    (await apiClient.get<ApiResponse<MatchCandidate[]>>(`${base(cooperativeId)}/lines/${lineId}/candidates`)).data,
  )
}

export async function confirmLine(cooperativeId: string, lineId: string): Promise<StatementLine> {
  return unwrapApiData((await apiClient.post<ApiResponse<StatementLine>>(`${base(cooperativeId)}/lines/${lineId}/confirm`)).data)
}

export async function linkLine(
  cooperativeId: string,
  lineId: string,
  payload: { paymentIds: string[]; ledgerEntryIds: string[] },
): Promise<StatementLine> {
  return unwrapApiData(
    (await apiClient.post<ApiResponse<StatementLine>>(`${base(cooperativeId)}/lines/${lineId}/link`, payload)).data,
  )
}

export async function explainLine(
  cooperativeId: string,
  lineId: string,
  payload: { category: string; note?: string },
): Promise<StatementLine> {
  return unwrapApiData(
    (await apiClient.post<ApiResponse<StatementLine>>(`${base(cooperativeId)}/lines/${lineId}/explain`, payload)).data,
  )
}

export async function undoLine(cooperativeId: string, lineId: string): Promise<StatementLine> {
  return unwrapApiData((await apiClient.post<ApiResponse<StatementLine>>(`${base(cooperativeId)}/lines/${lineId}/undo`)).data)
}

export async function confirmAllSuggestions(cooperativeId: string, statementId?: string): Promise<{ confirmed: number }> {
  return unwrapApiData(
    (
      await apiClient.post<ApiResponse<{ confirmed: number }>>(`${base(cooperativeId)}/confirm-all`, undefined, {
        params: statementId ? { statementId } : undefined,
      })
    ).data,
  )
}

export async function rematch(cooperativeId: string): Promise<Record<string, number>> {
  return unwrapApiData((await apiClient.post<ApiResponse<Record<string, number>>>(`${base(cooperativeId)}/rematch`)).data)
}

export async function verifyReconciledPayments(cooperativeId: string, statementId?: string): Promise<VerifyResult[]> {
  return unwrapApiData(
    (
      await apiClient.post<ApiResponse<VerifyResult[]>>(`${base(cooperativeId)}/verify-payments`, undefined, {
        params: statementId ? { statementId } : undefined,
        timeout: 120000,
      })
    ).data,
  )
}
