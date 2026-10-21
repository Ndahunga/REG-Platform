import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  SharePurchase,
  SharePurchaseReviewRequest,
  SharePurchaseStatus,
  SharePurchaseSubmitRequest,
  ShareValuation,
} from '@/shared/types/share'
import { mapSharePurchase, mapShareValuation } from '@/shared/types/share'

export async function fetchShareValuation(cooperativeId: string): Promise<ShareValuation> {
  const response = await apiClient.get<ApiResponse<ShareValuation>>(
    `/cooperatives/${cooperativeId}/shares/valuation`,
  )
  return mapShareValuation(unwrapApiData(response.data))
}

export async function submitSharePurchase(
  cooperativeId: string,
  payload: SharePurchaseSubmitRequest,
): Promise<SharePurchase> {
  const response = await apiClient.post<ApiResponse<SharePurchase>>(
    `/cooperatives/${cooperativeId}/shares/purchases`,
    payload,
  )
  return mapSharePurchase(unwrapApiData(response.data))
}

export async function fetchSharePurchases(
  cooperativeId: string,
  query: { memberUserId?: string; status?: SharePurchaseStatus; page?: number; size?: number } = {},
): Promise<PageResponse<SharePurchase>> {
  const params: Record<string, string | number> = {}
  if (query.memberUserId) params.memberUserId = query.memberUserId
  if (query.status) params.status = query.status
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  const response = await apiClient.get<ApiResponse<PageResponse<SharePurchase>>>(
    `/cooperatives/${cooperativeId}/shares/purchases`,
    { params },
  )
  const data = unwrapApiData(response.data)
  return {
    ...data,
    content: (data.content ?? []).map(mapSharePurchase),
  }
}

export async function fetchPendingSharePurchases(
  cooperativeId: string,
  query: { page?: number; size?: number } = {},
): Promise<PageResponse<SharePurchase>> {
  const response = await apiClient.get<ApiResponse<PageResponse<SharePurchase>>>(
    `/cooperatives/${cooperativeId}/shares/purchases/pending`,
    { params: query },
  )
  const data = unwrapApiData(response.data)
  return {
    ...data,
    content: (data.content ?? []).map(mapSharePurchase),
  }
}

export async function approveSharePurchase(
  cooperativeId: string,
  purchaseId: string,
): Promise<SharePurchase> {
  const response = await apiClient.post<ApiResponse<SharePurchase>>(
    `/cooperatives/${cooperativeId}/shares/purchases/${purchaseId}/approve`,
  )
  return mapSharePurchase(unwrapApiData(response.data))
}

export async function rejectSharePurchase(
  cooperativeId: string,
  purchaseId: string,
  payload: SharePurchaseReviewRequest,
): Promise<SharePurchase> {
  const response = await apiClient.post<ApiResponse<SharePurchase>>(
    `/cooperatives/${cooperativeId}/shares/purchases/${purchaseId}/reject`,
    payload,
  )
  return mapSharePurchase(unwrapApiData(response.data))
}
