import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  CooperativeSettings,
  CooperativeSettingsUpdateRequest,
} from '@/shared/types/cooperativeSettings'

export async function fetchCooperativeSettings(
  cooperativeId: string,
): Promise<CooperativeSettings> {
  const response = await apiClient.get<ApiResponse<CooperativeSettings>>(
    `/cooperatives/${cooperativeId}/settings`,
  )
  return unwrapApiData(response.data)
}

export async function updateCooperativeSettings(
  cooperativeId: string,
  payload: CooperativeSettingsUpdateRequest,
): Promise<CooperativeSettings> {
  const response = await apiClient.put<ApiResponse<CooperativeSettings>>(
    `/cooperatives/${cooperativeId}/settings`,
    payload,
  )
  return unwrapApiData(response.data)
}
