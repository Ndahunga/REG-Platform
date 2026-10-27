import { apiClient, isNotFoundError } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageQuery, PageResponse } from '@/shared/types/api'
import type {
  AssignPresidentRequest,
  Cooperative,
  CooperativeCreateRequest,
  CooperativeStatusUpdateRequest,
  CooperativeSubscription,
  CooperativeSummary,
  CooperativeUpdateRequest,
  OperatingModelClassificationRequest,
} from '@/shared/types/cooperative'
import { mapCooperativeSummary } from '@/shared/types/cooperative'
import type { Member } from '@/shared/types/member'
import { mapMember } from '@/shared/types/member'

function toParams(query: PageQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchMyCooperatives(): Promise<CooperativeSummary[]> {
  const response = await apiClient.get<ApiResponse<CooperativeSummary[]>>('/cooperatives/mine')
  const data = unwrapApiData(response.data)
  return (data ?? []).map(mapCooperativeSummary)
}

export async function fetchCooperatives(
  query: PageQuery = {},
): Promise<PageResponse<Cooperative>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Cooperative>>>('/cooperatives', {
    params: toParams(query),
  })
  return unwrapApiData(response.data)
}

export async function fetchCooperative(id: string): Promise<Cooperative> {
  const response = await apiClient.get<ApiResponse<Cooperative>>(`/cooperatives/${id}`)
  return unwrapApiData(response.data)
}

export async function createCooperative(
  payload: CooperativeCreateRequest,
): Promise<Cooperative> {
  const response = await apiClient.post<ApiResponse<Cooperative>>('/cooperatives', payload)
  return unwrapApiData(response.data)
}

export async function updateCooperative(
  id: string,
  payload: CooperativeUpdateRequest,
): Promise<Cooperative> {
  const response = await apiClient.put<ApiResponse<Cooperative>>(`/cooperatives/${id}`, payload)
  return unwrapApiData(response.data)
}

/** One-time classification of a legacy scheme. The server answers 409 if a model is already set. */
export async function classifyCooperativeOperatingModel(
  id: string,
  payload: OperatingModelClassificationRequest,
): Promise<Cooperative> {
  const response = await apiClient.post<ApiResponse<Cooperative>>(
    `/cooperatives/${id}/operating-model`,
    payload,
  )
  return unwrapApiData(response.data)
}

export async function updateCooperativeStatus(
  id: string,
  payload: CooperativeStatusUpdateRequest,
): Promise<Cooperative> {
  const response = await apiClient.patch<ApiResponse<Cooperative>>(
    `/cooperatives/${id}/status`,
    payload,
  )
  return unwrapApiData(response.data)
}

export async function uploadCooperativeLogo(id: string, file: File): Promise<Cooperative> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiClient.post<ApiResponse<Cooperative>>(
    `/cooperatives/${id}/logo`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  )
  return unwrapApiData(response.data)
}

export async function fetchCooperativeSubscription(
  id: string,
): Promise<CooperativeSubscription | null> {
  try {
    const response = await apiClient.get<ApiResponse<CooperativeSubscription>>(
      `/cooperatives/${id}/subscription`,
    )
    return unwrapApiData(response.data)
  } catch (error) {
    if (isNotFoundError(error)) return null
    throw error
  }
}

export async function assignCooperativePresident(
  cooperativeId: string,
  payload: AssignPresidentRequest,
): Promise<Member> {
  const response = await apiClient.post<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/administrators`,
    payload,
  )
  return mapMember(unwrapApiData(response.data))
}

export interface SchemeDeletionPreview {
  schemeId: string
  schemeName: string
  members: number
  accountsRemoved: number
  rowsTotal: number
  rowsByTable: Record<string, number>
}

/** Platform administrators: what deleting the scheme for good would remove (nothing changes). */
export async function fetchSchemeDeletionPreview(id: string): Promise<SchemeDeletionPreview> {
  const response = await apiClient.get<ApiResponse<SchemeDeletionPreview>>(`/cooperatives/${id}/permanent-deletion`)
  return unwrapApiData(response.data)
}

export async function deleteSchemePermanently(
  id: string,
  payload: { confirmName: string; reason: string },
): Promise<{ schemeId: string; schemeName: string; rowsDeleted: number; accountsDeleted: number }> {
  const response = await apiClient.post<
    ApiResponse<{ schemeId: string; schemeName: string; rowsDeleted: number; accountsDeleted: number }>
  >(`/cooperatives/${id}/permanent-deletion`, payload)
  return unwrapApiData(response.data)
}
