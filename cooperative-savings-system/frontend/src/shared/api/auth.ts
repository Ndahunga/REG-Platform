import { apiClient } from './client'
import type { ApiResponse } from '@/shared/types/api'
import type {
  AuthUser,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  PasswordResetConfirmRequest,
  PasswordResetRequest,
  SignupRequest,
  UpdateProfileRequest,
} from '@/shared/types/auth'

/** Unwrap the backend `{ success, message, data, timestamp }` envelope. */
export function unwrapApiData<T>(body: ApiResponse<T>): T {
  if (!body?.success || body.data === undefined || body.data === null) {
    throw new Error(body?.message || 'Unexpected API response')
  }
  return body.data
}

export async function login(payload: LoginRequest): Promise<LoginResponse> {
  const response = await apiClient.post<ApiResponse<LoginResponse>>('/auth/login', payload)
  return unwrapApiData(response.data)
}

export async function signup(payload: SignupRequest): Promise<LoginResponse> {
  const response = await apiClient.post<ApiResponse<LoginResponse>>('/auth/signup', payload)
  return unwrapApiData(response.data)
}

/** @deprecated Prefer `login` — kept for existing imports during Phase 2. */
export const loginRequest = login

export async function refresh(): Promise<LoginResponse> {
  const response = await apiClient.post<ApiResponse<LoginResponse>>('/auth/refresh')
  return unwrapApiData(response.data)
}

export async function logout(): Promise<void> {
  await apiClient.post<ApiResponse<null>>('/auth/logout')
}

export async function fetchMe(): Promise<AuthUser> {
  const response = await apiClient.get<ApiResponse<AuthUser>>('/auth/me')
  return unwrapApiData(response.data)
}

export async function updateMe(payload: UpdateProfileRequest): Promise<AuthUser> {
  const response = await apiClient.patch<ApiResponse<AuthUser>>('/auth/me', payload)
  return unwrapApiData(response.data)
}

export async function changePassword(payload: ChangePasswordRequest): Promise<LoginResponse> {
  const response = await apiClient.post<ApiResponse<LoginResponse>>('/auth/change-password', payload)
  return unwrapApiData(response.data)
}

export async function requestPasswordReset(payload: PasswordResetRequest): Promise<void> {
  await apiClient.post<ApiResponse<null>>('/auth/password-reset/request', payload)
}

export async function confirmPasswordReset(payload: PasswordResetConfirmRequest): Promise<void> {
  await apiClient.post<ApiResponse<null>>('/auth/password-reset/confirm', payload)
}
