import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  IncomeExpenseCreateRequest,
  IncomeExpenseListQuery,
  IncomeExpenseReviewRequest,
  IncomeExpenseTransaction,
} from '@/shared/types/incomeExpense'
import { mapIncomeExpenseTransaction } from '@/shared/types/incomeExpense'

function toListParams(query: IncomeExpenseListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.category) params.category = query.category
  if (query.status) params.status = query.status
  if (query.from) params.from = query.from
  if (query.to) params.to = query.to
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchTransactions(
  cooperativeId: string,
  query: IncomeExpenseListQuery = {},
): Promise<PageResponse<IncomeExpenseTransaction>> {
  const response = await apiClient.get<
    ApiResponse<PageResponse<IncomeExpenseTransaction>>
  >(`/cooperatives/${cooperativeId}/transactions`, {
    params: toListParams(query),
  })
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapIncomeExpenseTransaction),
  }
}

export async function fetchTransaction(
  cooperativeId: string,
  transactionId: string,
): Promise<IncomeExpenseTransaction> {
  const response = await apiClient.get<ApiResponse<IncomeExpenseTransaction>>(
    `/cooperatives/${cooperativeId}/transactions/${transactionId}`,
  )
  return mapIncomeExpenseTransaction(unwrapApiData(response.data))
}

export async function createTransaction(
  cooperativeId: string,
  payload: IncomeExpenseCreateRequest,
): Promise<IncomeExpenseTransaction> {
  const response = await apiClient.post<ApiResponse<IncomeExpenseTransaction>>(
    `/cooperatives/${cooperativeId}/transactions`,
    payload,
  )
  return mapIncomeExpenseTransaction(unwrapApiData(response.data))
}

export async function approveTransaction(
  cooperativeId: string,
  transactionId: string,
  payload: IncomeExpenseReviewRequest = {},
): Promise<IncomeExpenseTransaction> {
  const response = await apiClient.post<ApiResponse<IncomeExpenseTransaction>>(
    `/cooperatives/${cooperativeId}/transactions/${transactionId}/approve`,
    payload,
  )
  return mapIncomeExpenseTransaction(unwrapApiData(response.data))
}

export async function rejectTransaction(
  cooperativeId: string,
  transactionId: string,
  payload: IncomeExpenseReviewRequest = {},
): Promise<IncomeExpenseTransaction> {
  const response = await apiClient.post<ApiResponse<IncomeExpenseTransaction>>(
    `/cooperatives/${cooperativeId}/transactions/${transactionId}/reject`,
    payload,
  )
  return mapIncomeExpenseTransaction(unwrapApiData(response.data))
}
