export const NOTIFICATION_TYPES = [
  'ACCOUNT',
  'CONTRIBUTION',
  'LOAN',
  'FINE',
  'SOCIAL',
  'INVESTMENT',
  'PAYOUT',
  'SECURITY',
  'SYSTEM',
] as const

export type NotificationType = (typeof NOTIFICATION_TYPES)[number]

export interface AppNotification {
  id: string
  userId: string
  cooperativeId?: string | null
  type: NotificationType | string
  title: string
  body?: string | null
  entityType?: string | null
  entityId?: string | null
  read: boolean
  readAt?: string | null
  createdAt: string
}

export interface NotificationQuery {
  unreadOnly?: boolean
  page?: number
  size?: number
  sort?: string
}

export interface UnreadCountResponse {
  count: number
}

export interface PendingApprovals {
  contributionPendingCount: number
  loanPendingCount: number
  loanSecondApprovalCount: number
  sharePurchasePendingCount?: number
}
