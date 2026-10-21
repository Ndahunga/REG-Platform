import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  AttentionItem,
  ImportResponse,
  ReceiptExtraction,
  ReconciliationCandidate,
  ReconciliationEvent,
  ReconciliationRequest,
  ReconciliationRequestBody,
  TrustedTransaction,
  TrustedTransactionLine,
  VerificationCapabilities,
  VerificationCheck,
  VerificationOverview,
} from '@/shared/types/paymentVerification'

/**
 * Receipt reading, trusted verification and legacy rotating reconciliation. The backend is the only source of truth:
 * every result is refetched afterwards, and nothing here verifies or posts a payment.
 */

const payments = (cooperativeId: string) => `/cooperatives/${cooperativeId}/payments`
const reconciliation = (cooperativeId: string) => `/cooperatives/${cooperativeId}/rotation/reconciliation`

export async function fetchVerificationCapabilities(cooperativeId: string): Promise<VerificationCapabilities> {
  const response = await apiClient.get<ApiResponse<VerificationCapabilities>>(
    `${payments(cooperativeId)}/verification-capabilities`,
  )
  return unwrapApiData(response.data)
}

// ---------------------------------------------------------------- receipt (advisory only)

export async function fetchReceiptExtractions(cooperativeId: string, paymentId: string): Promise<ReceiptExtraction[]> {
  const response = await apiClient.get<ApiResponse<ReceiptExtraction[]>>(`${payments(cooperativeId)}/${paymentId}/receipt`)
  return unwrapApiData(response.data) ?? []
}

export async function extractReceipt(cooperativeId: string, paymentId: string): Promise<ReceiptExtraction> {
  const response = await apiClient.post<ApiResponse<ReceiptExtraction>>(
    `${payments(cooperativeId)}/${paymentId}/receipt/extract`,
  )
  return unwrapApiData(response.data)
}

export async function reviewReceiptExtraction(
  cooperativeId: string,
  paymentId: string,
  extractionId: string,
  note?: string,
): Promise<ReceiptExtraction> {
  const response = await apiClient.post<ApiResponse<ReceiptExtraction>>(
    `${payments(cooperativeId)}/${paymentId}/receipt/${extractionId}/review`,
    note ? { note } : {},
  )
  return unwrapApiData(response.data)
}

// ---------------------------------------------------------------- verification outcomes

export async function fetchVerificationOverview(cooperativeId: string, paymentId: string): Promise<VerificationOverview> {
  const response = await apiClient.get<ApiResponse<VerificationOverview>>(
    `${payments(cooperativeId)}/${paymentId}/verification-checks`,
  )
  return unwrapApiData(response.data)
}

export async function recheckPayment(cooperativeId: string, paymentId: string): Promise<VerificationCheck> {
  const response = await apiClient.post<ApiResponse<VerificationCheck>>(
    `${payments(cooperativeId)}/${paymentId}/verification-checks/recheck`,
  )
  return unwrapApiData(response.data)
}

/** An authorized officer confirms the cash was received. The only route to CONFIRMED for a cash payment. */
export async function confirmCashReceived(
  cooperativeId: string,
  paymentId: string,
  note: string,
): Promise<VerificationCheck> {
  const response = await apiClient.post<ApiResponse<VerificationCheck>>(
    `${payments(cooperativeId)}/${paymentId}/verification-checks/cash-confirmation`,
    { note },
  )
  return unwrapApiData(response.data)
}

export async function fetchAttention(cooperativeId: string): Promise<AttentionItem[]> {
  const response = await apiClient.get<ApiResponse<AttentionItem[]>>(`${payments(cooperativeId)}/reconciliation/attention`)
  return unwrapApiData(response.data) ?? []
}

// ---------------------------------------------------------------- trusted transactions

export async function fetchTrustedTransactions(cooperativeId: string): Promise<TrustedTransaction[]> {
  const response = await apiClient.get<ApiResponse<TrustedTransaction[]>>(`${payments(cooperativeId)}/trusted-transactions`)
  return unwrapApiData(response.data) ?? []
}

export async function importTrustedTransactions(
  cooperativeId: string,
  items: TrustedTransactionLine[],
): Promise<ImportResponse> {
  const response = await apiClient.post<ApiResponse<ImportResponse>>(`${payments(cooperativeId)}/trusted-transactions`, {
    items,
  })
  return unwrapApiData(response.data)
}

// ---------------------------------------------------------------- legacy rotating reconciliation

export async function fetchReconciliationCandidates(cooperativeId: string): Promise<ReconciliationCandidate[]> {
  const response = await apiClient.get<ApiResponse<ReconciliationCandidate[]>>(`${reconciliation(cooperativeId)}/candidates`)
  return unwrapApiData(response.data) ?? []
}

export async function fetchReconciliations(cooperativeId: string): Promise<ReconciliationRequest[]> {
  const response = await apiClient.get<ApiResponse<ReconciliationRequest[]>>(`${reconciliation(cooperativeId)}/requests`)
  return unwrapApiData(response.data) ?? []
}

export async function requestReconciliation(
  cooperativeId: string,
  body: ReconciliationRequestBody,
): Promise<ReconciliationRequest> {
  const response = await apiClient.post<ApiResponse<ReconciliationRequest>>(`${reconciliation(cooperativeId)}/requests`, body)
  return unwrapApiData(response.data)
}

/** Confirms the amount as received. Creates no ledger entry: it only makes the amount verified and payout-eligible. */
export async function approveReconciliation(cooperativeId: string, id: string, note?: string): Promise<ReconciliationRequest> {
  const response = await apiClient.post<ApiResponse<ReconciliationRequest>>(
    `${reconciliation(cooperativeId)}/requests/${id}/approve`,
    note ? { note } : {},
  )
  return unwrapApiData(response.data)
}

export async function declineReconciliation(cooperativeId: string, id: string, note: string): Promise<ReconciliationRequest> {
  const response = await apiClient.post<ApiResponse<ReconciliationRequest>>(
    `${reconciliation(cooperativeId)}/requests/${id}/decline`,
    { note },
  )
  return unwrapApiData(response.data)
}

export async function withdrawReconciliation(cooperativeId: string, id: string, note?: string): Promise<ReconciliationRequest> {
  const response = await apiClient.post<ApiResponse<ReconciliationRequest>>(
    `${reconciliation(cooperativeId)}/requests/${id}/withdraw`,
    note ? { note } : {},
  )
  return unwrapApiData(response.data)
}

export async function fetchReconciliationEvents(cooperativeId: string, id: string): Promise<ReconciliationEvent[]> {
  const response = await apiClient.get<ApiResponse<ReconciliationEvent[]>>(
    `${reconciliation(cooperativeId)}/requests/${id}/events`,
  )
  return unwrapApiData(response.data) ?? []
}
