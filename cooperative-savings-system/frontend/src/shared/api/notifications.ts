import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  AppNotification,
  NotificationQuery,
  PendingApprovals,
  UnreadCountResponse,
} from '@/shared/types/notification'

function toParams(query: NotificationQuery = {}) {
  const params: Record<string, string | number | boolean> = {}
  if (query.unreadOnly != null) params.unreadOnly = query.unreadOnly
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchNotifications(
  query: NotificationQuery = {},
): Promise<PageResponse<AppNotification>> {
  const response = await apiClient.get<ApiResponse<PageResponse<AppNotification>>>(
    '/notifications',
    { params: toParams(query) },
  )
  return unwrapApiData(response.data)
}

export async function fetchUnreadCount(): Promise<number> {
  const response = await apiClient.get<ApiResponse<UnreadCountResponse | number>>(
    '/notifications/unread-count',
  )
  const data = unwrapApiData(response.data)
  if (typeof data === 'number') return data
  return data?.count ?? 0
}

export async function markNotificationRead(id: string): Promise<AppNotification> {
  const response = await apiClient.patch<ApiResponse<AppNotification>>(
    `/notifications/${id}/read`,
  )
  return unwrapApiData(response.data)
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiClient.post<ApiResponse<null>>('/notifications/read-all')
}

export async function fetchPendingApprovals(): Promise<PendingApprovals> {
  const response = await apiClient.get<ApiResponse<PendingApprovals>>('/notifications/pending-approvals')
  const data = unwrapApiData(response.data)
  return {
    contributionPendingCount: data?.contributionPendingCount ?? 0,
    loanPendingCount: data?.loanPendingCount ?? 0,
    loanSecondApprovalCount: data?.loanSecondApprovalCount ?? 0,
  }
}
