import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  MemberPayment,
  PaymentEvent,
  CreditApplyRequest,
  CreditOverview,
  PaymentAccount,
  PaymentAccountChannel,
  PaymentListQuery,
  PaymentPosting,
  PaymentReversal,
  PaymentSubmitRequest,
  PaymentSummary,
  PaymentVerifyRequest,
  ReceiptAnalysis,
} from '@/shared/types/memberPayment'

/**
 * Member payment API (backend {@code MemberPaymentController}). Every call is scoped to one Saving Scheme in the path.
 * Nothing here is optimistic: callers refetch the payment after every action and the backend stays authoritative.
 */

const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/payments`

function listParams(query: PaymentListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.status) params.status = query.status
  if (query.memberUserId) params.memberUserId = query.memberUserId
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  return params
}

async function getPage(url: string, query: PaymentListQuery): Promise<PageResponse<PaymentSummary>> {
  const response = await apiClient.get<ApiResponse<PageResponse<PaymentSummary>>>(url, { params: listParams(query) })
  const page = unwrapApiData(response.data)
  return { ...page, content: page.content ?? [] }
}

/**
 * Submits a payment claim for the signed-in member. The Idempotency-Key makes a repeated request (double click, retry
 * after a timeout) return the original payment instead of creating a second claim. Moves no money.
 */
export async function submitPayment(
  cooperativeId: string,
  payload: PaymentSubmitRequest,
  idempotencyKey: string,
): Promise<MemberPayment> {
  const response = await apiClient.post<ApiResponse<MemberPayment>>(base(cooperativeId), payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return unwrapApiData(response.data)
}

export function fetchMyPayments(cooperativeId: string, query: PaymentListQuery = {}) {
  return getPage(`${base(cooperativeId)}/mine`, query)
}

/** Staff: payments awaiting review (SUBMITTED and UNDER_REVIEW), oldest first. */
export function fetchPaymentReviewQueue(cooperativeId: string, query: PaymentListQuery = {}) {
  return getPage(`${base(cooperativeId)}/review-queue`, query)
}

/** Staff: all payments of the scheme, newest first; status=VERIFIED lists payments awaiting posting. */
export function fetchPayments(cooperativeId: string, query: PaymentListQuery = {}) {
  return getPage(base(cooperativeId), query)
}

export async function fetchPayment(cooperativeId: string, paymentId: string): Promise<MemberPayment> {
  const response = await apiClient.get<ApiResponse<MemberPayment>>(`${base(cooperativeId)}/${paymentId}`)
  return unwrapApiData(response.data)
}

export async function fetchPaymentEvents(cooperativeId: string, paymentId: string): Promise<PaymentEvent[]> {
  const response = await apiClient.get<ApiResponse<PaymentEvent[]>>(`${base(cooperativeId)}/${paymentId}/events`)
  return unwrapApiData(response.data) ?? []
}

/** Staff only: posting status, verification evidence flags and the immutable posting results. */
export async function fetchPaymentPosting(cooperativeId: string, paymentId: string): Promise<PaymentPosting> {
  const response = await apiClient.get<ApiResponse<PaymentPosting>>(`${base(cooperativeId)}/${paymentId}/posting`)
  return unwrapApiData(response.data)
}

export async function startPaymentReview(cooperativeId: string, paymentId: string): Promise<MemberPayment> {
  const response = await apiClient.post<ApiResponse<MemberPayment>>(`${base(cooperativeId)}/${paymentId}/start-review`)
  return unwrapApiData(response.data)
}

export async function rejectPayment(cooperativeId: string, paymentId: string, reason: string): Promise<MemberPayment> {
  const response = await apiClient.post<ApiResponse<MemberPayment>>(`${base(cooperativeId)}/${paymentId}/reject`, {
    reason,
  })
  return unwrapApiData(response.data)
}

/** Records HOW receipt of funds was confirmed. Verification never posts anything. */
export async function verifyPayment(
  cooperativeId: string,
  paymentId: string,
  payload: PaymentVerifyRequest,
): Promise<MemberPayment> {
  const response = await apiClient.post<ApiResponse<MemberPayment>>(`${base(cooperativeId)}/${paymentId}/verify`, payload)
  return unwrapApiData(response.data)
}

/** For a payment marked VERIFIED before evidence was required. */
export async function completePaymentEvidence(
  cooperativeId: string,
  paymentId: string,
  payload: PaymentVerifyRequest,
): Promise<MemberPayment> {
  const response = await apiClient.post<ApiResponse<MemberPayment>>(
    `${base(cooperativeId)}/${paymentId}/verification-evidence`,
    payload,
  )
  return unwrapApiData(response.data)
}

/** VERIFIED to POSTED, atomically. Posting an already POSTED payment returns the existing result. */
export async function postPayment(cooperativeId: string, paymentId: string): Promise<PaymentPosting> {
  const response = await apiClient.post<ApiResponse<PaymentPosting>>(`${base(cooperativeId)}/${paymentId}/post`)
  return unwrapApiData(response.data)
}

/** A manual retry of a posting that failed earlier. Never called automatically. */
export async function retryPaymentPosting(cooperativeId: string, paymentId: string): Promise<PaymentPosting> {
  const response = await apiClient.post<ApiResponse<PaymentPosting>>(`${base(cooperativeId)}/${paymentId}/retry-posting`)
  return unwrapApiData(response.data)
}

// ------------------------------------------------------------------ contribution-only reversal

/** Requests the reversal of a posted contribution-only payment. Moves no money: another officer must approve. */
export async function requestPaymentReversal(
  cooperativeId: string,
  paymentId: string,
  reason: string,
): Promise<PaymentReversal> {
  const response = await apiClient.post<ApiResponse<PaymentReversal>>(`${base(cooperativeId)}/${paymentId}/reversals`, {
    reason,
  })
  return unwrapApiData(response.data)
}

export async function fetchPaymentReversals(cooperativeId: string, paymentId: string): Promise<PaymentReversal[]> {
  const response = await apiClient.get<ApiResponse<PaymentReversal[]>>(`${base(cooperativeId)}/${paymentId}/reversals`)
  return unwrapApiData(response.data) ?? []
}

/** Approval executes the compensating accounting atomically; the backend refuses it when it would be inconsistent. */
export async function approvePaymentReversal(cooperativeId: string, reversalId: string): Promise<PaymentReversal> {
  const response = await apiClient.post<ApiResponse<PaymentReversal>>(
    `${base(cooperativeId)}/reversals/${reversalId}/approve`,
  )
  return unwrapApiData(response.data)
}

/** The original requester withdraws a pending reversal request. Moves no money. */
export async function withdrawPaymentReversal(
  cooperativeId: string,
  reversalId: string,
  note?: string,
): Promise<PaymentReversal> {
  const response = await apiClient.post<ApiResponse<PaymentReversal>>(
    `${base(cooperativeId)}/reversals/${reversalId}/withdraw`,
    note ? { note } : {},
  )
  return unwrapApiData(response.data)
}

export async function declinePaymentReversal(
  cooperativeId: string,
  reversalId: string,
  reason: string,
): Promise<PaymentReversal> {
  const response = await apiClient.post<ApiResponse<PaymentReversal>>(
    `${base(cooperativeId)}/reversals/${reversalId}/decline`,
    { reason },
  )
  return unwrapApiData(response.data)
}

/**
 * Reads an uploaded receipt before the payment is submitted. Advisory: it moves no money and verifies nothing. The same
 * rule is applied again by the backend when the payment is submitted.
 */
export async function analyzeReceipt(
  cooperativeId: string,
  fileKey: string,
  enteredAmount?: string,
): Promise<ReceiptAnalysis> {
  const response = await apiClient.post<ApiResponse<ReceiptAnalysis>>(`${base(cooperativeId)}/receipt-analysis`, {
    fileKey,
    enteredAmount: enteredAmount || undefined,
  })
  return unwrapApiData(response.data)
}

export async function fetchPaymentAccounts(cooperativeId: string): Promise<PaymentAccount[]> {
  const response = await apiClient.get<ApiResponse<PaymentAccount[]>>(`${base(cooperativeId)}/accounts`)
  return unwrapApiData(response.data) ?? []
}

export async function addPaymentAccount(
  cooperativeId: string,
  payload: { channel: PaymentAccountChannel; accountIdentifier: string; label?: string },
): Promise<PaymentAccount> {
  const response = await apiClient.post<ApiResponse<PaymentAccount>>(`${base(cooperativeId)}/accounts`, payload)
  return unwrapApiData(response.data)
}

export async function deactivatePaymentAccount(cooperativeId: string, accountId: string): Promise<void> {
  await apiClient.post(`${base(cooperativeId)}/accounts/${accountId}/deactivate`)
}

/** Permanently deletes an account nobody ever paid into; the backend refuses one that was used. */
export async function deletePaymentAccount(cooperativeId: string, accountId: string): Promise<void> {
  await apiClient.delete(`${base(cooperativeId)}/accounts/${accountId}`)
}

/** The payer takes back a payment nobody has started to review. */
export async function withdrawPayment(cooperativeId: string, paymentId: string, reason?: string): Promise<MemberPayment> {
  const response = await apiClient.post<ApiResponse<MemberPayment>>(`${base(cooperativeId)}/${paymentId}/withdraw`, {
    reason,
  })
  return unwrapApiData(response.data)
}

/** Held credit by member and purpose. A member sees only their own; an officer may name a member. */
export async function fetchCredits(cooperativeId: string, memberUserId?: string): Promise<CreditOverview> {
  const response = await apiClient.get<ApiResponse<CreditOverview>>(`${base(cooperativeId)}/credits`, {
    params: memberUserId ? { memberUserId } : undefined,
  })
  const overview = unwrapApiData(response.data)
  return { ...overview, balances: overview.balances ?? [] }
}

/** An officer applies a member's held credit to a contribution or a loan. The key makes a repeat a no-op. */
export async function applyCredit(cooperativeId: string, payload: CreditApplyRequest, idempotencyKey: string): Promise<void> {
  await apiClient.post(`${base(cooperativeId)}/credits/apply`, payload, { headers: { 'Idempotency-Key': idempotencyKey } })
}
