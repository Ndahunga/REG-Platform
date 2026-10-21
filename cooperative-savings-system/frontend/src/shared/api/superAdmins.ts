import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'

export type SuperAdminAccountStatus = 'ACTIVE' | 'INACTIVE' | 'LOCKED' | 'PENDING' | 'SUSPENDED'

export interface SuperAdmin {
  id: string
  firstName: string
  lastName: string
  fullName: string
  username: string
  email: string
  phone?: string | null
  accountStatus: SuperAdminAccountStatus
  lastLoginAt?: string | null
  createdAt?: string | null
  currentUser?: boolean
}

export interface SuperAdminCreateRequest {
  firstName: string
  lastName: string
  username: string
  email: string
  phone?: string
  password: string
}

export const SUPER_ADMINS_QUERY_KEY = ['platform', 'super-admins'] as const

const BASE = '/platform/super-admins'

export async function fetchSuperAdmins(): Promise<SuperAdmin[]> {
  const response = await apiClient.get<ApiResponse<SuperAdmin[]>>(BASE)
  return unwrapApiData(response.data) ?? []
}

export async function createSuperAdmin(payload: SuperAdminCreateRequest): Promise<SuperAdmin> {
  const response = await apiClient.post<ApiResponse<SuperAdmin>>(BASE, payload)
  return unwrapApiData(response.data)
}

export async function updateSuperAdminStatus(
  id: string,
  status: 'ACTIVE' | 'INACTIVE',
): Promise<SuperAdmin> {
  const response = await apiClient.patch<ApiResponse<SuperAdmin>>(`${BASE}/${id}/status`, { status })
  return unwrapApiData(response.data)
}
