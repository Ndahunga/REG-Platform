import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  SocialContribution,
  SocialContributionCreateRequest,
  SocialContributionReviewRequest,
  SocialDisbursement,
  SocialDisbursementCreateRequest,
  SocialDisbursementReviewRequest,
  SocialFundListQuery,
  SocialFundReport,
  SocialFundSettings,
  SocialFundSettingsUpdateRequest,
  SocialFundSummary,
} from '@/shared/types/socialFund'
import {
  mapSocialContribution,
  mapSocialDisbursement,
  mapSocialFundReport,
  mapSocialFundSettings,
  mapSocialFundSummary,
} from '@/shared/types/socialFund'

function toListParams(query: SocialFundListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.memberUserId) params.memberUserId = query.memberUserId
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchSocialFundSummary(
  cooperativeId: string,
): Promise<SocialFundSummary> {
  const response = await apiClient.get<ApiResponse<SocialFundSummary>>(
    `/cooperatives/${cooperativeId}/social-fund/summary`,
  )
  return mapSocialFundSummary(unwrapApiData(response.data))
}

export async function fetchSocialFundSettings(
  cooperativeId: string,
): Promise<SocialFundSettings> {
  const response = await apiClient.get<ApiResponse<SocialFundSettings>>(
    `/cooperatives/${cooperativeId}/social-fund/settings`,
  )
  return mapSocialFundSettings(unwrapApiData(response.data))
}

export async function updateSocialFundSettings(
  cooperativeId: string,
  payload: SocialFundSettingsUpdateRequest,
): Promise<SocialFundSettings> {
  const response = await apiClient.put<ApiResponse<SocialFundSettings>>(
    `/cooperatives/${cooperativeId}/social-fund/settings`,
    payload,
  )
  return mapSocialFundSettings(unwrapApiData(response.data))
}

export async function fetchSocialContributions(
  cooperativeId: string,
  query: SocialFundListQuery = {},
): Promise<PageResponse<SocialContribution>> {
  const response = await apiClient.get<ApiResponse<PageResponse<SocialContribution>>>(
    `/cooperatives/${cooperativeId}/social-fund/contributions`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapSocialContribution),
  }
}

export async function fetchMySocialContributions(
  cooperativeId: string,
): Promise<SocialContribution[]> {
  const response = await apiClient.get<ApiResponse<SocialContribution[]>>(
    `/cooperatives/${cooperativeId}/social-fund/contributions/my`,
  )
  return (unwrapApiData(response.data) ?? []).map(mapSocialContribution)
}

export async function createSocialContribution(
  cooperativeId: string,
  payload: SocialContributionCreateRequest,
): Promise<SocialContribution> {
  const response = await apiClient.post<ApiResponse<SocialContribution>>(
    `/cooperatives/${cooperativeId}/social-fund/contributions`,
    payload,
  )
  return mapSocialContribution(unwrapApiData(response.data))
}

export async function approveSocialContribution(
  cooperativeId: string,
  contributionId: string,
  payload: SocialContributionReviewRequest = {},
): Promise<SocialContribution> {
  const response = await apiClient.post<ApiResponse<SocialContribution>>(
    `/cooperatives/${cooperativeId}/social-fund/contributions/${contributionId}/approve`,
    payload,
  )
  return mapSocialContribution(unwrapApiData(response.data))
}

export async function rejectSocialContribution(
  cooperativeId: string,
  contributionId: string,
  payload: SocialContributionReviewRequest = {},
): Promise<SocialContribution> {
  const response = await apiClient.post<ApiResponse<SocialContribution>>(
    `/cooperatives/${cooperativeId}/social-fund/contributions/${contributionId}/reject`,
    payload,
  )
  return mapSocialContribution(unwrapApiData(response.data))
}

export async function fetchSocialDisbursements(
  cooperativeId: string,
  query: SocialFundListQuery = {},
): Promise<PageResponse<SocialDisbursement>> {
  const response = await apiClient.get<ApiResponse<PageResponse<SocialDisbursement>>>(
    `/cooperatives/${cooperativeId}/social-fund/disbursements`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapSocialDisbursement),
  }
}

export async function createSocialDisbursement(
  cooperativeId: string,
  payload: SocialDisbursementCreateRequest,
): Promise<SocialDisbursement> {
  const response = await apiClient.post<ApiResponse<SocialDisbursement>>(
    `/cooperatives/${cooperativeId}/social-fund/disbursements`,
    payload,
  )
  return mapSocialDisbursement(unwrapApiData(response.data))
}

export async function approveSocialDisbursement(
  cooperativeId: string,
  disbursementId: string,
  payload: SocialDisbursementReviewRequest = {},
): Promise<SocialDisbursement> {
  const response = await apiClient.post<ApiResponse<SocialDisbursement>>(
    `/cooperatives/${cooperativeId}/social-fund/disbursements/${disbursementId}/approve`,
    payload,
  )
  return mapSocialDisbursement(unwrapApiData(response.data))
}

export async function rejectSocialDisbursement(
  cooperativeId: string,
  disbursementId: string,
  payload: SocialDisbursementReviewRequest = {},
): Promise<SocialDisbursement> {
  const response = await apiClient.post<ApiResponse<SocialDisbursement>>(
    `/cooperatives/${cooperativeId}/social-fund/disbursements/${disbursementId}/reject`,
    payload,
  )
  return mapSocialDisbursement(unwrapApiData(response.data))
}

export async function cancelSocialDisbursement(
  cooperativeId: string,
  disbursementId: string,
): Promise<SocialDisbursement> {
  const response = await apiClient.post<ApiResponse<SocialDisbursement>>(
    `/cooperatives/${cooperativeId}/social-fund/disbursements/${disbursementId}/cancel`,
  )
  return mapSocialDisbursement(unwrapApiData(response.data))
}

export async function fetchSocialFundReport(
  cooperativeId: string,
  params: { from?: string; to?: string } = {},
): Promise<SocialFundReport> {
  const query: Record<string, string> = {}
  if (params.from) query.from = params.from
  if (params.to) query.to = params.to
  const response = await apiClient.get<ApiResponse<SocialFundReport>>(
    `/cooperatives/${cooperativeId}/social-fund/report`,
    { params: query },
  )
  return mapSocialFundReport(unwrapApiData(response.data))
}
