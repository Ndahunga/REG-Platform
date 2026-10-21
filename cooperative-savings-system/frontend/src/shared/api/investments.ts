import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  Investment,
  InvestmentCreateRequest,
  InvestmentListQuery,
  InvestmentLossRequest,
  InvestmentReturn,
  InvestmentReturnCreateRequest,
} from '@/shared/types/investment'
import { mapInvestment, mapInvestmentReturn } from '@/shared/types/investment'

function toListParams(query: InvestmentListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchInvestments(
  cooperativeId: string,
  query: InvestmentListQuery = {},
): Promise<PageResponse<Investment>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Investment>>>(
    `/cooperatives/${cooperativeId}/investments`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapInvestment),
  }
}

export async function fetchInvestment(
  cooperativeId: string,
  investmentId: string,
): Promise<Investment> {
  const response = await apiClient.get<ApiResponse<Investment>>(
    `/cooperatives/${cooperativeId}/investments/${investmentId}`,
  )
  return mapInvestment(unwrapApiData(response.data))
}

export async function createInvestment(
  cooperativeId: string,
  payload: InvestmentCreateRequest,
): Promise<Investment> {
  const response = await apiClient.post<ApiResponse<Investment>>(
    `/cooperatives/${cooperativeId}/investments`,
    payload,
  )
  return mapInvestment(unwrapApiData(response.data))
}

export async function activateInvestment(
  cooperativeId: string,
  investmentId: string,
): Promise<Investment> {
  const response = await apiClient.post<ApiResponse<Investment>>(
    `/cooperatives/${cooperativeId}/investments/${investmentId}/activate`,
  )
  return mapInvestment(unwrapApiData(response.data))
}

export async function cancelInvestment(
  cooperativeId: string,
  investmentId: string,
): Promise<Investment> {
  const response = await apiClient.post<ApiResponse<Investment>>(
    `/cooperatives/${cooperativeId}/investments/${investmentId}/cancel`,
  )
  return mapInvestment(unwrapApiData(response.data))
}

export async function recordInvestmentReturn(
  cooperativeId: string,
  investmentId: string,
  payload: InvestmentReturnCreateRequest,
): Promise<InvestmentReturn> {
  const response = await apiClient.post<ApiResponse<InvestmentReturn>>(
    `/cooperatives/${cooperativeId}/investments/${investmentId}/returns`,
    payload,
  )
  return mapInvestmentReturn(unwrapApiData(response.data))
}

export async function recordInvestmentLoss(
  cooperativeId: string,
  investmentId: string,
  payload: InvestmentLossRequest = {},
): Promise<Investment> {
  const response = await apiClient.post<ApiResponse<Investment>>(
    `/cooperatives/${cooperativeId}/investments/${investmentId}/record-loss`,
    payload,
  )
  return mapInvestment(unwrapApiData(response.data))
}

export async function fetchInvestmentReturns(
  cooperativeId: string,
  investmentId: string,
): Promise<InvestmentReturn[]> {
  const response = await apiClient.get<
    ApiResponse<InvestmentReturn[] | PageResponse<InvestmentReturn>>
  >(`/cooperatives/${cooperativeId}/investments/${investmentId}/returns`)
  const data = unwrapApiData(response.data)
  const list = Array.isArray(data) ? data : (data.content ?? [])
  return list.map(mapInvestmentReturn)
}
