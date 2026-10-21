import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type { LoanSettings, LoanSettingsUpdateRequest } from '@/shared/types/loan'
import { mapLoanSettings } from '@/shared/types/loan'

export async function fetchLoanSettings(cooperativeId: string): Promise<LoanSettings> {
  const response = await apiClient.get<ApiResponse<LoanSettings>>(
    `/cooperatives/${cooperativeId}/loan-settings`,
  )
  return mapLoanSettings(unwrapApiData(response.data))
}

export async function updateLoanSettings(
  cooperativeId: string,
  payload: LoanSettingsUpdateRequest,
): Promise<LoanSettings> {
  const response = await apiClient.put<ApiResponse<LoanSettings>>(
    `/cooperatives/${cooperativeId}/loan-settings`,
    payload,
  )
  return mapLoanSettings(unwrapApiData(response.data))
}
