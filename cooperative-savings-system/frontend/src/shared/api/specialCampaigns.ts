import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageQuery, PageResponse } from '@/shared/types/api'
import type {
  SpecialCampaign,
  SpecialCampaignCreateRequest,
  SpecialCampaignStatusUpdateRequest,
  SpecialCampaignUpdateRequest,
  SpecialContribution,
  SpecialContributionReviewRequest,
  SpecialContributionSubmitRequest,
} from '@/shared/types/specialContribution'
import {
  mapSpecialCampaign,
  mapSpecialContribution,
} from '@/shared/types/specialContribution'

function toParams(query: PageQuery & { status?: string } = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchSpecialCampaigns(
  cooperativeId: string,
  query: PageQuery & { status?: string } = {},
): Promise<PageResponse<SpecialCampaign> | SpecialCampaign[]> {
  const response = await apiClient.get<
    ApiResponse<PageResponse<SpecialCampaign> | SpecialCampaign[]>
  >(`/cooperatives/${cooperativeId}/special-campaigns`, {
    params: toParams(query),
  })
  const data = unwrapApiData(response.data)
  if (Array.isArray(data)) {
    return data.map(mapSpecialCampaign)
  }
  return {
    ...data,
    content: (data.content ?? []).map(mapSpecialCampaign),
  }
}

export async function fetchSpecialCampaign(
  cooperativeId: string,
  campaignId: string,
): Promise<SpecialCampaign> {
  const response = await apiClient.get<ApiResponse<SpecialCampaign>>(
    `/cooperatives/${cooperativeId}/special-campaigns/${campaignId}`,
  )
  return mapSpecialCampaign(unwrapApiData(response.data))
}

export async function createSpecialCampaign(
  cooperativeId: string,
  payload: SpecialCampaignCreateRequest,
): Promise<SpecialCampaign> {
  const response = await apiClient.post<ApiResponse<SpecialCampaign>>(
    `/cooperatives/${cooperativeId}/special-campaigns`,
    payload,
  )
  return mapSpecialCampaign(unwrapApiData(response.data))
}

export async function updateSpecialCampaign(
  cooperativeId: string,
  campaignId: string,
  payload: SpecialCampaignUpdateRequest,
): Promise<SpecialCampaign> {
  const response = await apiClient.put<ApiResponse<SpecialCampaign>>(
    `/cooperatives/${cooperativeId}/special-campaigns/${campaignId}`,
    payload,
  )
  return mapSpecialCampaign(unwrapApiData(response.data))
}

export async function updateSpecialCampaignStatus(
  cooperativeId: string,
  campaignId: string,
  payload: SpecialCampaignStatusUpdateRequest,
): Promise<SpecialCampaign> {
  const response = await apiClient.patch<ApiResponse<SpecialCampaign>>(
    `/cooperatives/${cooperativeId}/special-campaigns/${campaignId}/status`,
    payload,
  )
  return mapSpecialCampaign(unwrapApiData(response.data))
}

export async function fetchSpecialContributions(
  cooperativeId: string,
  campaignId: string,
  query: PageQuery & { status?: string } = {},
): Promise<PageResponse<SpecialContribution> | SpecialContribution[]> {
  const response = await apiClient.get<
    ApiResponse<PageResponse<SpecialContribution> | SpecialContribution[]>
  >(`/cooperatives/${cooperativeId}/special-campaigns/${campaignId}/contributions`, {
    params: toParams(query),
  })
  const data = unwrapApiData(response.data)
  if (Array.isArray(data)) {
    return data.map(mapSpecialContribution)
  }
  return {
    ...data,
    content: (data.content ?? []).map(mapSpecialContribution),
  }
}

export async function submitSpecialContribution(
  cooperativeId: string,
  campaignId: string,
  payload: SpecialContributionSubmitRequest,
): Promise<SpecialContribution> {
  const response = await apiClient.post<ApiResponse<SpecialContribution>>(
    `/cooperatives/${cooperativeId}/special-campaigns/${campaignId}/contributions`,
    payload,
  )
  return mapSpecialContribution(unwrapApiData(response.data))
}

export async function approveSpecialContribution(
  cooperativeId: string,
  campaignId: string,
  contributionId: string,
  payload: SpecialContributionReviewRequest = {},
): Promise<SpecialContribution> {
  const response = await apiClient.post<ApiResponse<SpecialContribution>>(
    `/cooperatives/${cooperativeId}/special-campaigns/${campaignId}/contributions/${contributionId}/approve`,
    payload,
  )
  return mapSpecialContribution(unwrapApiData(response.data))
}

export async function rejectSpecialContribution(
  cooperativeId: string,
  campaignId: string,
  contributionId: string,
  payload: SpecialContributionReviewRequest = {},
): Promise<SpecialContribution> {
  const response = await apiClient.post<ApiResponse<SpecialContribution>>(
    `/cooperatives/${cooperativeId}/special-campaigns/${campaignId}/contributions/${contributionId}/reject`,
    payload,
  )
  return mapSpecialContribution(unwrapApiData(response.data))
}
