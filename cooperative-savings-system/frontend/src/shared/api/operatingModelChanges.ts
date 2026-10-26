import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'

export interface OperatingModelChange {
  id: string
  fromModel: SchemeOperatingModel
  toModel: SchemeOperatingModel
  effectiveYear: number
  effectiveMonth: number
  status: 'SCHEDULED' | 'APPLIED' | 'CANCELLED'
  reason?: string | null
  createdAt?: string
  appliedAt?: string | null
}

const base = (id: string) => `/cooperatives/${id}/operating-model/changes`

export async function fetchOperatingModelChanges(cooperativeId: string): Promise<OperatingModelChange[]> {
  const response = await apiClient.get<ApiResponse<OperatingModelChange[]>>(base(cooperativeId))
  return unwrapApiData(response.data) ?? []
}

export async function scheduleOperatingModelChange(
  cooperativeId: string,
  payload: { operatingModel: SchemeOperatingModel; effectiveYear: number; effectiveMonth: number; reason?: string },
): Promise<OperatingModelChange> {
  const response = await apiClient.post<ApiResponse<OperatingModelChange>>(base(cooperativeId), payload)
  return unwrapApiData(response.data)
}

export async function cancelOperatingModelChange(cooperativeId: string, changeId: string): Promise<void> {
  await apiClient.post(`${base(cooperativeId)}/${changeId}/cancel`)
}
