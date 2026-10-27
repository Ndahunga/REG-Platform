import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageQuery, PageResponse } from '@/shared/types/api'
import type {
  Member,
  MemberCreateRequest,
  MemberFinancialSummary,
  MemberRemovalEligibility,
  MemberStatusUpdateRequest,
  MemberUpdateRequest,
} from '@/shared/types/member'
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

export async function fetchMembers(
  cooperativeId: string,
  query: PageQuery = {},
): Promise<PageResponse<Member>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Member>>>(
    `/cooperatives/${cooperativeId}/members`,
    { params: toParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapMember),
  }
}

export async function fetchMember(cooperativeId: string, userId: string): Promise<Member> {
  const response = await apiClient.get<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members/${userId}`,
  )
  return mapMember(unwrapApiData(response.data))
}

export async function createMember(
  cooperativeId: string,
  payload: MemberCreateRequest,
): Promise<Member> {
  const response = await apiClient.post<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members`,
    payload,
  )
  return mapMember(unwrapApiData(response.data))
}

export async function updateMember(
  cooperativeId: string,
  userId: string,
  payload: MemberUpdateRequest,
): Promise<Member> {
  const response = await apiClient.put<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members/${userId}`,
    payload,
  )
  return mapMember(unwrapApiData(response.data))
}

export async function updateMemberStatus(
  cooperativeId: string,
  userId: string,
  payload: MemberStatusUpdateRequest,
): Promise<Member> {
  const response = await apiClient.patch<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members/${userId}/status`,
    payload,
  )
  return mapMember(unwrapApiData(response.data))
}

export async function uploadMemberProfileImage(
  cooperativeId: string,
  userId: string,
  file: File,
): Promise<Member> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiClient.post<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members/${userId}/profile-image`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  )
  return mapMember(unwrapApiData(response.data))
}

export async function fetchMemberFinancialSummary(
  cooperativeId: string,
  userId: string,
): Promise<MemberFinancialSummary> {
  const response = await apiClient.get<ApiResponse<MemberFinancialSummary>>(
    `/cooperatives/${cooperativeId}/members/${userId}/financial-summary`,
  )
  return unwrapApiData(response.data)
}

/** Paged, searchable financial summaries for every member — used on the admin dashboard. */
export async function fetchMemberFinancialSummaries(
  cooperativeId: string,
  query: { q?: string; page?: number; size?: number } = {},
): Promise<PageResponse<MemberFinancialSummary>> {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  const response = await apiClient.get<ApiResponse<PageResponse<MemberFinancialSummary>>>(
    `/cooperatives/${cooperativeId}/members/financial-summaries`,
    { params },
  )
  return unwrapApiData(response.data)
}

/** What may be done to remove a member (delete, deactivate or reactivate), and why not. The backend decides. */
export async function fetchMemberRemovalEligibility(
  cooperativeId: string,
  userId: string,
): Promise<MemberRemovalEligibility> {
  const response = await apiClient.get<ApiResponse<MemberRemovalEligibility>>(
    `/cooperatives/${cooperativeId}/members/${userId}/removal-eligibility`,
  )
  return unwrapApiData(response.data)
}

/** Takes the member out of active membership; every record, loan and obligation stays. */
export async function deactivateMember(cooperativeId: string, userId: string, reason: string): Promise<Member> {
  const response = await apiClient.post<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members/${userId}/deactivate`,
    { reason },
  )
  return mapMember(unwrapApiData(response.data))
}

export async function reactivateMember(cooperativeId: string, userId: string): Promise<Member> {
  const response = await apiClient.post<ApiResponse<Member>>(
    `/cooperatives/${cooperativeId}/members/${userId}/reactivate`,
  )
  return mapMember(unwrapApiData(response.data))
}

/** Permanently deletes a member who has no history at all; the backend refuses anyone else. */
export async function deleteMember(cooperativeId: string, userId: string): Promise<void> {
  await apiClient.delete(`/cooperatives/${cooperativeId}/members/${userId}`)
}

/** What deleting the member and all their records in the scheme would remove (nothing changes). */
export interface MemberFullDeletionPreview {
  memberUserId: string
  username: string
  canPurge: boolean
  blockedReason?: string | null
  blockedDetails?: string[]
  accountRemoved: boolean
  rowsTotal: number
  rowsByTable: Record<string, number>
}

export async function fetchMemberFullDeletion(
  cooperativeId: string,
  userId: string,
): Promise<MemberFullDeletionPreview> {
  const response = await apiClient.get<ApiResponse<MemberFullDeletionPreview>>(
    `/cooperatives/${cooperativeId}/members/${userId}/full-deletion`,
  )
  return unwrapApiData(response.data)
}

/** President or Vice President: deletes the member and every record they have in the scheme, for good. */
export async function deleteMemberFully(
  cooperativeId: string,
  userId: string,
  payload: { reason: string; confirmUsername: string },
): Promise<MemberFullDeletionPreview> {
  const response = await apiClient.post<ApiResponse<MemberFullDeletionPreview>>(
    `/cooperatives/${cooperativeId}/members/${userId}/full-deletion`,
    payload,
  )
  return unwrapApiData(response.data)
}
