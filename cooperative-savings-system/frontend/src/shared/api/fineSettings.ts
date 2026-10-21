import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type { FineSettings, FineSettingsUpdateRequest } from '@/shared/types/fine'
import { mapFineSettings } from '@/shared/types/fine'

export async function fetchFineSettings(cooperativeId: string): Promise<FineSettings> {
  const response = await apiClient.get<ApiResponse<FineSettings>>(
    `/cooperatives/${cooperativeId}/fine-settings`,
  )
  return mapFineSettings(unwrapApiData(response.data))
}

export async function updateFineSettings(
  cooperativeId: string,
  payload: FineSettingsUpdateRequest,
): Promise<FineSettings> {
  const response = await apiClient.put<ApiResponse<FineSettings>>(
    `/cooperatives/${cooperativeId}/fine-settings`,
    payload,
  )
  return mapFineSettings(unwrapApiData(response.data))
}
