import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  Fine,
  FineCreateRequest,
  FineGenerateRequest,
  FineGenerateResponse,
  FineListQuery,
  FinePayment,
  FinePaymentCreateRequest,
  FinePaymentQueueQuery,
  FinePaymentReviewRequest,
} from '@/shared/types/fine'
import {
  mapFine,
  mapFineGenerateResponse,
  mapFinePayment,
} from '@/shared/types/fine'

function toListParams(query: FineListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.memberUserId) params.memberUserId = query.memberUserId
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchFines(
  cooperativeId: string,
  query: FineListQuery = {},
): Promise<PageResponse<Fine>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Fine>>>(
    `/cooperatives/${cooperativeId}/fines`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapFine),
  }
}

export async function fetchMyFines(
  cooperativeId: string,
  query: FineListQuery = {},
): Promise<PageResponse<Fine>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Fine> | Fine[]>>(
    `/cooperatives/${cooperativeId}/fines/my`,
    { params: toListParams(query) },
  )
  const data = unwrapApiData(response.data)
  if (Array.isArray(data)) {
    return {
      content: data.map(mapFine),
      page: 0,
      size: data.length,
      totalElements: data.length,
      totalPages: 1,
      first: true,
      last: true,
    }
  }
  return {
    ...data,
    content: (data.content ?? []).map(mapFine),
  }
}

export async function fetchFine(cooperativeId: string, fineId: string): Promise<Fine> {
  const response = await apiClient.get<ApiResponse<Fine>>(
    `/cooperatives/${cooperativeId}/fines/${fineId}`,
  )
  return mapFine(unwrapApiData(response.data))
}

export async function createFine(
  cooperativeId: string,
  payload: FineCreateRequest,
): Promise<Fine> {
  const response = await apiClient.post<ApiResponse<Fine>>(
    `/cooperatives/${cooperativeId}/fines`,
    payload,
  )
  return mapFine(unwrapApiData(response.data))
}

export async function generateAutomaticFines(
  cooperativeId: string,
  payload: FineGenerateRequest = {},
): Promise<FineGenerateResponse> {
  const response = await apiClient.post<ApiResponse<FineGenerateResponse>>(
    `/cooperatives/${cooperativeId}/fines/generate-automatic`,
    payload,
  )
  return mapFineGenerateResponse(unwrapApiData(response.data))
}

export async function waiveFine(cooperativeId: string, fineId: string): Promise<Fine> {
  const response = await apiClient.post<ApiResponse<Fine>>(
    `/cooperatives/${cooperativeId}/fines/${fineId}/waive`,
  )
  return mapFine(unwrapApiData(response.data))
}

export async function cancelFine(cooperativeId: string, fineId: string): Promise<Fine> {
  const response = await apiClient.post<ApiResponse<Fine>>(
    `/cooperatives/${cooperativeId}/fines/${fineId}/cancel`,
  )
  return mapFine(unwrapApiData(response.data))
}

function toQueueParams(query: FinePaymentQueueQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.fromDate) params.fromDate = query.fromDate
  if (query.toDate) params.toDate = query.toDate
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

/** Cooperative-wide fine payment review queue (pending/approved/rejected across all members). */
export async function fetchFinePaymentQueue(
  cooperativeId: string,
  query: FinePaymentQueueQuery = {},
): Promise<PageResponse<FinePayment>> {
  const response = await apiClient.get<ApiResponse<PageResponse<FinePayment>>>(
    `/cooperatives/${cooperativeId}/fines/payments`,
    { params: toQueueParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapFinePayment),
  }
}

export async function fetchFinePayments(
  cooperativeId: string,
  fineId: string,
): Promise<FinePayment[]> {
  const response = await apiClient.get<
    ApiResponse<FinePayment[] | PageResponse<FinePayment>>
  >(`/cooperatives/${cooperativeId}/fines/${fineId}/payments`)
  const data = unwrapApiData(response.data)
  const list = Array.isArray(data) ? data : (data.content ?? [])
  return list.map(mapFinePayment)
}

export async function createFinePayment(
  cooperativeId: string,
  fineId: string,
  payload: FinePaymentCreateRequest,
): Promise<FinePayment> {
  const response = await apiClient.post<ApiResponse<FinePayment>>(
    `/cooperatives/${cooperativeId}/fines/${fineId}/payments`,
    payload,
  )
  return mapFinePayment(unwrapApiData(response.data))
}

export async function approveFinePayment(
  cooperativeId: string,
  fineId: string,
  paymentId: string,
  payload: FinePaymentReviewRequest = {},
): Promise<FinePayment> {
  const response = await apiClient.post<ApiResponse<FinePayment>>(
    `/cooperatives/${cooperativeId}/fines/${fineId}/payments/${paymentId}/approve`,
    payload,
  )
  return mapFinePayment(unwrapApiData(response.data))
}

export async function rejectFinePayment(
  cooperativeId: string,
  fineId: string,
  paymentId: string,
  payload: FinePaymentReviewRequest = {},
): Promise<FinePayment> {
  const response = await apiClient.post<ApiResponse<FinePayment>>(
    `/cooperatives/${cooperativeId}/fines/${fineId}/payments/${paymentId}/reject`,
    payload,
  )
  return mapFinePayment(unwrapApiData(response.data))
}
