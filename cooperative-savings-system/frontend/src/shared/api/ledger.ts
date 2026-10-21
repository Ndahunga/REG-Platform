import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type { LedgerEntry, LedgerListQuery } from '@/shared/types/ledger'
import { mapLedgerEntry } from '@/shared/types/ledger'

function toListParams(query: LedgerListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.transactionType) params.transactionType = query.transactionType
  if (query.from) params.from = query.from
  if (query.to) params.to = query.to
  if (query.memberUserId) params.memberUserId = query.memberUserId
  if (query.sourceEntityType) params.sourceEntityType = query.sourceEntityType
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchLedgerEntries(
  cooperativeId: string,
  query: LedgerListQuery = {},
): Promise<PageResponse<LedgerEntry>> {
  const response = await apiClient.get<ApiResponse<PageResponse<LedgerEntry>>>(
    `/cooperatives/${cooperativeId}/ledger`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapLedgerEntry),
  }
}

export async function fetchLedgerEntry(
  cooperativeId: string,
  entryId: string,
): Promise<LedgerEntry> {
  const response = await apiClient.get<ApiResponse<LedgerEntry>>(
    `/cooperatives/${cooperativeId}/ledger/${entryId}`,
  )
  return mapLedgerEntry(unwrapApiData(response.data))
}
