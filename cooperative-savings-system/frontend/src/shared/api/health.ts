import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, HealthResponse } from '@/shared/types/api'
import type { SystemInfo } from '@/shared/types/system'

export async function fetchHealth(): Promise<HealthResponse> {
  const { data } = await apiClient.get<HealthResponse>('/public/health')
  return data
}

export async function fetchSystemInfo(): Promise<SystemInfo> {
  const response = await apiClient.get<ApiResponse<SystemInfo>>('/system/info')
  return unwrapApiData(response.data)
}
