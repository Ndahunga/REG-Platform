import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  PayoutLine,
  PayoutListQuery,
  PayoutPreviewRequest,
  PayoutRun,
  PayoutStatement,
} from '@/shared/types/payout'
import { mapPayoutLine, mapPayoutRun, mapPayoutStatement } from '@/shared/types/payout'

function toListParams(query: PayoutListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function previewPayout(
  cooperativeId: string,
  payload: PayoutPreviewRequest,
): Promise<PayoutRun> {
  const response = await apiClient.post<ApiResponse<PayoutRun>>(
    `/cooperatives/${cooperativeId}/payouts/preview`,
    payload,
  )
  return mapPayoutRun(unwrapApiData(response.data))
}

export async function fetchPayoutRuns(
  cooperativeId: string,
  query: PayoutListQuery = {},
): Promise<PageResponse<PayoutRun>> {
  const response = await apiClient.get<ApiResponse<PageResponse<PayoutRun>>>(
    `/cooperatives/${cooperativeId}/payouts`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapPayoutRun),
  }
}

export async function fetchPayoutRun(
  cooperativeId: string,
  runId: string,
): Promise<PayoutRun> {
  const response = await apiClient.get<ApiResponse<PayoutRun>>(
    `/cooperatives/${cooperativeId}/payouts/${runId}`,
  )
  return mapPayoutRun(unwrapApiData(response.data))
}

export async function fetchMyPayouts(
  cooperativeId: string,
  query: PayoutListQuery = {},
): Promise<PageResponse<PayoutLine> | PayoutLine[]> {
  const response = await apiClient.get<
    ApiResponse<PageResponse<PayoutLine> | PayoutLine[]>
  >(`/cooperatives/${cooperativeId}/payouts/my`, {
    params: toListParams(query),
  })
  const data = unwrapApiData(response.data)
  if (Array.isArray(data)) {
    return data.map(mapPayoutLine)
  }
  return {
    ...data,
    content: (data.content ?? []).map(mapPayoutLine),
  }
}

export async function confirmPayout(
  cooperativeId: string,
  runId: string,
): Promise<PayoutRun> {
  const response = await apiClient.post<ApiResponse<PayoutRun>>(
    `/cooperatives/${cooperativeId}/payouts/${runId}/confirm`,
  )
  return mapPayoutRun(unwrapApiData(response.data))
}

export async function markPayoutPaid(
  cooperativeId: string,
  runId: string,
): Promise<PayoutRun> {
  const response = await apiClient.post<ApiResponse<PayoutRun>>(
    `/cooperatives/${cooperativeId}/payouts/${runId}/mark-paid`,
  )
  return mapPayoutRun(unwrapApiData(response.data))
}

export async function cancelPayout(
  cooperativeId: string,
  runId: string,
): Promise<PayoutRun> {
  const response = await apiClient.post<ApiResponse<PayoutRun>>(
    `/cooperatives/${cooperativeId}/payouts/${runId}/cancel`,
  )
  return mapPayoutRun(unwrapApiData(response.data))
}

export async function fetchPayoutStatement(
  cooperativeId: string,
  runId: string,
): Promise<PayoutStatement> {
  const response = await apiClient.get<ApiResponse<PayoutStatement>>(
    `/cooperatives/${cooperativeId}/payouts/${runId}/statement`,
  )
  return mapPayoutStatement(unwrapApiData(response.data))
}
