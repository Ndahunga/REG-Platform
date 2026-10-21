import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  DisbursementBody,
  LateRecovery,
  ModelSummary,
  MemberObligation,
  PayoutRequestBody,
  PeriodEntitlement,
  PeriodPayoutEligibility,
  PoolEntryBody,
  RotationPoolEntry,
  RotationPoolSummary,
  RotationPayout,
  RotationPayoutEvent,
} from '@/shared/types/rotationPayout'

/**
 * Rotation entitlements and payouts. Everything is scoped to one Saving Scheme in the path, and every action is
 * refetched afterwards: the backend is the only source of truth for what was collected, owed and paid.
 */

const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/rotation`

/** Officers: every period's expected, collected, distributed and outstanding amounts. */
export async function fetchEntitlements(cooperativeId: string): Promise<PeriodEntitlement[]> {
  const response = await apiClient.get<ApiResponse<PeriodEntitlement[]>>(`${base(cooperativeId)}/entitlements`)
  return unwrapApiData(response.data) ?? []
}

/** The periods of which the signed-in member is the original scheduled recipient. */
export async function fetchMyEntitlements(cooperativeId: string): Promise<PeriodEntitlement[]> {
  const response = await apiClient.get<ApiResponse<PeriodEntitlement[]>>(`${base(cooperativeId)}/entitlements/mine`)
  return unwrapApiData(response.data) ?? []
}

export async function fetchRecoveries(cooperativeId: string, periodId: string): Promise<LateRecovery[]> {
  const response = await apiClient.get<ApiResponse<LateRecovery[]>>(
    `${base(cooperativeId)}/entitlements/${periodId}/recoveries`,
  )
  return unwrapApiData(response.data) ?? []
}

export async function requestRotationPayout(
  cooperativeId: string,
  payload: PayoutRequestBody,
  idempotencyKey: string,
): Promise<RotationPayout> {
  const response = await apiClient.post<ApiResponse<RotationPayout>>(`${base(cooperativeId)}/payouts`, payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return unwrapApiData(response.data)
}

/** Officers: which periods can have a payout requested now, and why not. The backend decides; the screen only shows it. */
export async function fetchPayoutEligibility(cooperativeId: string): Promise<PeriodPayoutEligibility[]> {
  const response = await apiClient.get<ApiResponse<PeriodPayoutEligibility[]>>(`${base(cooperativeId)}/payouts/eligibility`)
  return unwrapApiData(response.data) ?? []
}

export async function fetchRotationPayouts(cooperativeId: string): Promise<RotationPayout[]> {
  const response = await apiClient.get<ApiResponse<RotationPayout[]>>(`${base(cooperativeId)}/payouts`)
  return unwrapApiData(response.data) ?? []
}

export async function fetchMyRotationPayouts(cooperativeId: string): Promise<RotationPayout[]> {
  const response = await apiClient.get<ApiResponse<RotationPayout[]>>(`${base(cooperativeId)}/payouts/mine`)
  return unwrapApiData(response.data) ?? []
}

export async function fetchRotationPayoutEvents(
  cooperativeId: string,
  payoutId: string,
): Promise<RotationPayoutEvent[]> {
  const response = await apiClient.get<ApiResponse<RotationPayoutEvent[]>>(
    `${base(cooperativeId)}/payouts/${payoutId}/events`,
  )
  return unwrapApiData(response.data) ?? []
}

export async function approveRotationPayout(cooperativeId: string, payoutId: string): Promise<RotationPayout> {
  const response = await apiClient.post<ApiResponse<RotationPayout>>(`${base(cooperativeId)}/payouts/${payoutId}/approve`)
  return unwrapApiData(response.data)
}

export async function rejectRotationPayout(
  cooperativeId: string,
  payoutId: string,
  reason: string,
): Promise<RotationPayout> {
  const response = await apiClient.post<ApiResponse<RotationPayout>>(`${base(cooperativeId)}/payouts/${payoutId}/reject`, {
    reason,
  })
  return unwrapApiData(response.data)
}

export async function cancelRotationPayout(
  cooperativeId: string,
  payoutId: string,
  reason: string,
): Promise<RotationPayout> {
  const response = await apiClient.post<ApiResponse<RotationPayout>>(`${base(cooperativeId)}/payouts/${payoutId}/cancel`, {
    reason,
  })
  return unwrapApiData(response.data)
}

/** Confirms that the money was ACTUALLY handed over (method and reference). Only this completes a payout. */
export async function confirmRotationDisbursement(
  cooperativeId: string,
  payoutId: string,
  payload: DisbursementBody,
): Promise<RotationPayout> {
  const response = await apiClient.post<ApiResponse<RotationPayout>>(
    `${base(cooperativeId)}/payouts/${payoutId}/confirm-disbursement`,
    payload,
  )
  return unwrapApiData(response.data)
}

/** Officers: the model-aware financial summary (expected, collected, retained, rotating, owed, paid out, arrears). */
export async function fetchModelSummary(cooperativeId: string): Promise<ModelSummary> {
  const response = await apiClient.get<ApiResponse<ModelSummary>>(`/cooperatives/${cooperativeId}/reports/model-summary`)
  return unwrapApiData(response.data)
}

/** Officers: the rotating pool (collected, opening, available, unused reserve). */
export async function fetchRotationPool(cooperativeId: string): Promise<RotationPoolSummary> {
  const response = await apiClient.get<ApiResponse<RotationPoolSummary>>(`${base(cooperativeId)}/pool`)
  return unwrapApiData(response.data)
}

export async function fetchRotationPoolEntries(cooperativeId: string): Promise<RotationPoolEntry[]> {
  const response = await apiClient.get<ApiResponse<RotationPoolEntry[]>>(`${base(cooperativeId)}/pool/entries`)
  return unwrapApiData(response.data) ?? []
}

/** Fund authority: records the opening pool, a top-up or an expense. An earmark, never a cash movement. */
export async function recordRotationPoolEntry(
  cooperativeId: string,
  body: PoolEntryBody,
  idempotencyKey: string,
): Promise<RotationPoolEntry> {
  const response = await apiClient.post<ApiResponse<RotationPoolEntry>>(`${base(cooperativeId)}/pool/entries`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return unwrapApiData(response.data)
}

/** Officers: every member's contributions paid, payout received, remaining and overdue amounts. */
export async function fetchMemberObligations(cooperativeId: string): Promise<MemberObligation[]> {
  const response = await apiClient.get<ApiResponse<MemberObligation[]>>(`${base(cooperativeId)}/obligations`)
  return unwrapApiData(response.data) ?? []
}

/** The signed-in member's own position. */
export async function fetchMyObligation(cooperativeId: string): Promise<MemberObligation> {
  const response = await apiClient.get<ApiResponse<MemberObligation>>(`${base(cooperativeId)}/obligations/mine`)
  return unwrapApiData(response.data)
}
