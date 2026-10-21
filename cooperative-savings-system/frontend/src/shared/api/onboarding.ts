import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type { LoginResponse } from '@/shared/types/auth'
import type { PublicOnboardingPayload } from '@/features/onboarding/onboardingSchema'

export async function onboardCooperative(payload: PublicOnboardingPayload): Promise<LoginResponse> {
  const response = await apiClient.post<ApiResponse<LoginResponse>>('/onboarding/signup', payload)
  return unwrapApiData(response.data)
}
