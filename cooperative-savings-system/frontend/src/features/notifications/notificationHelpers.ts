import type { AppNotification, PendingApprovals } from '@/shared/types/notification'
import { ROUTES } from '@/shared/constants/routes'

export const NOTIFICATION_POLL_MS = 60_000

export function isUnread(notification: Pick<AppNotification, 'read'>): boolean {
  return !notification.read
}

export function unreadHighlightSx(unread: boolean) {
  if (!unread) return undefined
  return {
    bgcolor: 'action.hover',
    borderLeft: '3px solid',
    borderLeftColor: 'primary.main',
  } as const
}

export function sortNotificationsNewestFirst(
  items: AppNotification[],
): AppNotification[] {
  return [...items].sort((a, b) => {
    const aTime = a.createdAt ? Date.parse(a.createdAt) : 0
    const bTime = b.createdAt ? Date.parse(b.createdAt) : 0
    return bTime - aTime
  })
}

export function pendingApprovalTotal(pending?: PendingApprovals | null): number {
  if (!pending) return 0
  return (
    (pending.contributionPendingCount || 0) +
    (pending.loanPendingCount || 0) +
    (pending.loanSecondApprovalCount || 0) +
    (pending.sharePurchasePendingCount || 0)
  )
}

export function notificationBadgeCount(
  unreadCount: number,
  pending?: PendingApprovals | null,
): number {
  return (unreadCount || 0) + pendingApprovalTotal(pending)
}

export type PendingApprovalKind = 'contributions' | 'loans' | 'shares'

export interface PendingApprovalItem {
  kind: PendingApprovalKind
  count: number
  path: string
}

export function pendingApprovalItems(
  pending?: PendingApprovals | null,
): PendingApprovalItem[] {
  const items: PendingApprovalItem[] = []
  const contributionCount = pending?.contributionPendingCount ?? 0
  if (contributionCount > 0) {
    items.push({
      kind: 'contributions',
      count: contributionCount,
      path: `${ROUTES.contributions}?tab=approvals`,
    })
  }
  const loanCount =
    (pending?.loanPendingCount ?? 0) + (pending?.loanSecondApprovalCount ?? 0)
  if (loanCount > 0) {
    items.push({
      kind: 'loans',
      count: loanCount,
      path: `${ROUTES.loans}?tab=approvals`,
    })
  }
  const shareCount = pending?.sharePurchasePendingCount ?? 0
  if (shareCount > 0) {
    items.push({
      kind: 'shares',
      count: shareCount,
      path: `${ROUTES.contributions}?tab=share-approvals`,
    })
  }
  return items
}

export function pendingApprovalLabelKey(
  kind: PendingApprovalKind,
  count: number,
): string {
  return count === 1
    ? `notifications.pending.${kind}`
    : `notifications.pending.${kind}_plural`
}

export function notificationTargetPath(
  notification: Pick<AppNotification, 'entityType' | 'entityId'>,
): string | null {
  const type = notification.entityType?.trim().toLowerCase()
  if (!type) return null
  if (type === 'contribution') {
    return `${ROUTES.contributions}?tab=history`
  }
  if (type === 'sharepurchase') {
    return `${ROUTES.contributions}?tab=share-approvals`
  }
  if (type === 'loan' && notification.entityId) {
    return ROUTES.loanDetail(notification.entityId)
  }
  return null
}
