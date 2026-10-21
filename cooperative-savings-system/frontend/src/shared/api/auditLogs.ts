import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type { AuditLog, AuditLogQuery } from '@/shared/types/auditLog'

function toParams(query: AuditLogQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.action?.trim()) params.action = query.action.trim()
  if (query.userId?.trim()) params.userId = query.userId.trim()
  if (query.entityType?.trim()) params.entityType = query.entityType.trim()
  if (query.from) params.from = query.from
  if (query.to) params.to = query.to
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchAuditLogs(
  cooperativeId: string,
  query: AuditLogQuery = {},
): Promise<PageResponse<AuditLog>> {
  const response = await apiClient.get<ApiResponse<PageResponse<AuditLog>>>(
    `/cooperatives/${cooperativeId}/audit-logs`,
    { params: toParams(query) },
  )
  return unwrapApiData(response.data)
}

export async function fetchAuditLog(
  cooperativeId: string,
  auditId: string,
): Promise<AuditLog> {
  const response = await apiClient.get<ApiResponse<AuditLog>>(
    `/cooperatives/${cooperativeId}/audit-logs/${auditId}`,
  )
  return unwrapApiData(response.data)
}
