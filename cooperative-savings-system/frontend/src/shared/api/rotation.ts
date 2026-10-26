import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  RotationConfiguration,
  RotationConfigurationRequest,
  RotationCycle,
  RotationCycleCreateRequest,
  RotationPeriod,
  RotationRecipientChange,
  RotationImportRequest,
  RotationImportResponse,
  ShareCycleCreateRequest,
  SharePool,
  TurnPayoutEligibility,
} from '@/shared/types/rotation'

const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/rotation`

export async function fetchRotationConfigurations(cooperativeId: string): Promise<RotationConfiguration[]> {
  const response = await apiClient.get<ApiResponse<RotationConfiguration[]>>(`${base(cooperativeId)}/configurations`)
  return unwrapApiData(response.data) ?? []
}

/** Deletes a configuration that has not taken effect and was never used; the backend refuses any other. */
export async function deleteRotationConfiguration(cooperativeId: string, configurationId: string): Promise<void> {
  await apiClient.delete(`${base(cooperativeId)}/configurations/${configurationId}`)
}

export async function createRotationConfiguration(
  cooperativeId: string,
  payload: RotationConfigurationRequest,
): Promise<RotationConfiguration> {
  const response = await apiClient.post<ApiResponse<RotationConfiguration>>(
    `${base(cooperativeId)}/configurations`,
    payload,
  )
  return unwrapApiData(response.data)
}

export async function fetchRotationCycles(cooperativeId: string): Promise<RotationCycle[]> {
  const response = await apiClient.get<ApiResponse<RotationCycle[]>>(`${base(cooperativeId)}/cycles`)
  return unwrapApiData(response.data) ?? []
}

/** `idempotencyKey` makes a retried request return the original cycle instead of creating a second draft. */
export async function createRotationCycle(
  cooperativeId: string,
  payload: RotationCycleCreateRequest,
  idempotencyKey?: string,
): Promise<RotationCycle> {
  const response = await apiClient.post<ApiResponse<RotationCycle>>(`${base(cooperativeId)}/cycles`, payload, {
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
  return unwrapApiData(response.data)
}

export async function activateRotationCycle(cooperativeId: string, cycleId: string): Promise<RotationCycle> {
  const response = await apiClient.post<ApiResponse<RotationCycle>>(
    `${base(cooperativeId)}/cycles/${cycleId}/activate`,
  )
  return unwrapApiData(response.data)
}

export async function cancelRotationCycle(
  cooperativeId: string,
  cycleId: string,
  reason: string,
): Promise<RotationCycle> {
  const response = await apiClient.post<ApiResponse<RotationCycle>>(
    `${base(cooperativeId)}/cycles/${cycleId}/cancel`,
    { reason },
  )
  return unwrapApiData(response.data)
}

export async function changeRotationRecipient(
  cooperativeId: string,
  periodId: string,
  recipientUserId: string,
  reason: string,
): Promise<RotationPeriod> {
  const response = await apiClient.put<ApiResponse<RotationPeriod>>(
    `${base(cooperativeId)}/periods/${periodId}/recipient`,
    { recipientUserId, reason },
  )
  return unwrapApiData(response.data)
}

export async function fetchRotationRecipientChanges(
  cooperativeId: string,
  periodId: string,
): Promise<RotationRecipientChange[]> {
  const response = await apiClient.get<ApiResponse<RotationRecipientChange[]>>(
    `${base(cooperativeId)}/periods/${periodId}/recipient-changes`,
  )
  return unwrapApiData(response.data) ?? []
}

export async function createShareCycle(
  cooperativeId: string,
  payload: ShareCycleCreateRequest,
  idempotencyKey?: string,
): Promise<RotationCycle> {
  const response = await apiClient.post<ApiResponse<RotationCycle>>(`${base(cooperativeId)}/share-cycles`, payload, {
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
  return unwrapApiData(response.data)
}

export async function reassignRotationTurn(
  cooperativeId: string,
  turnId: string,
  memberUserId: string,
  reason: string,
): Promise<RotationCycle> {
  const response = await apiClient.put<ApiResponse<RotationCycle>>(`${base(cooperativeId)}/turns/${turnId}/member`, {
    memberUserId,
    reason,
  })
  return unwrapApiData(response.data)
}

export async function fetchTurnEligibility(cooperativeId: string): Promise<TurnPayoutEligibility[]> {
  const response = await apiClient.get<ApiResponse<TurnPayoutEligibility[]>>(
    `${base(cooperativeId)}/payouts/turn-eligibility`,
  )
  return unwrapApiData(response.data) ?? []
}

export async function requestTurnPayout(
  cooperativeId: string,
  turnId: string,
  amount: number | undefined,
  idempotencyKey: string,
): Promise<unknown> {
  const response = await apiClient.post<ApiResponse<unknown>>(
    `${base(cooperativeId)}/payouts`,
    { rotationTurnId: turnId, amount },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return unwrapApiData(response.data)
}

export async function fetchSharePool(cooperativeId: string): Promise<SharePool> {
  const response = await apiClient.get<ApiResponse<SharePool>>(`${base(cooperativeId)}/pool/share`)
  return unwrapApiData(response.data)
}

export async function importRotation(
  cooperativeId: string,
  payload: RotationImportRequest,
): Promise<RotationImportResponse> {
  const response = await apiClient.post<ApiResponse<RotationImportResponse>>(`${base(cooperativeId)}/migration`, payload)
  return unwrapApiData(response.data)
}
