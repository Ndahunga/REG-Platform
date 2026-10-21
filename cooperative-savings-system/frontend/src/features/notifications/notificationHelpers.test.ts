import { describe, expect, it } from 'vitest'
import type { AppNotification, PendingApprovals } from '@/shared/types/notification'
import { ROUTES } from '@/shared/constants/routes'
import {
  isUnread,
  notificationBadgeCount,
  notificationTargetPath,
  pendingApprovalItems,
  pendingApprovalLabelKey,
  pendingApprovalTotal,
  sortNotificationsNewestFirst,
  unreadHighlightSx,
} from './notificationHelpers'

function n(partial: Partial<AppNotification> & Pick<AppNotification, 'id'>): AppNotification {
  return {
    userId: 'u1',
    type: 'SYSTEM',
    title: 't',
    read: false,
    createdAt: '2026-01-01T00:00:00Z',
    ...partial,
  }
}

describe('notificationHelpers', () => {
  it('detects unread notifications', () => {
    expect(isUnread({ read: false })).toBe(true)
    expect(isUnread({ read: true })).toBe(false)
  })

  it('returns highlight styles only for unread', () => {
    expect(unreadHighlightSx(false)).toBeUndefined()
    expect(unreadHighlightSx(true)).toMatchObject({
      borderLeft: '3px solid',
      borderLeftColor: 'primary.main',
    })
  })

  it('sorts newest first', () => {
    const sorted = sortNotificationsNewestFirst([
      n({ id: 'a', createdAt: '2026-01-01T00:00:00Z' }),
      n({ id: 'b', createdAt: '2026-02-01T00:00:00Z' }),
      n({ id: 'c', createdAt: '2026-01-15T00:00:00Z' }),
    ])
    expect(sorted.map((x) => x.id)).toEqual(['b', 'c', 'a'])
  })

  it('routes Contribution notifications to contribution history', () => {
    expect(
      notificationTargetPath({
        entityType: 'Contribution',
        entityId: 'c1',
      }),
    ).toBe(`${ROUTES.contributions}?tab=history`)
  })

  it('routes Loan notifications to the loan detail page', () => {
    expect(
      notificationTargetPath({
        entityType: 'Loan',
        entityId: 'loan-9',
      }),
    ).toBe(ROUTES.loanDetail('loan-9'))
  })

  it('returns no path when entity type is missing or unknown', () => {
    expect(notificationTargetPath({ entityType: null, entityId: 'x' })).toBeNull()
    expect(notificationTargetPath({ entityType: 'Fine', entityId: 'f1' })).toBeNull()
    expect(notificationTargetPath({ entityType: 'Loan', entityId: null })).toBeNull()
  })

  it('routes SharePurchase notifications to the Share Purchase Approvals view', () => {
    expect(
      notificationTargetPath({
        entityType: 'SharePurchase',
        entityId: 'sp-1',
      }),
    ).toBe(`${ROUTES.contributions}?tab=share-approvals`)
  })

  it('builds pending approval copy items only for counts greater than zero', () => {
    const none: PendingApprovals = {
      contributionPendingCount: 0,
      loanPendingCount: 0,
      loanSecondApprovalCount: 0,
    }
    expect(pendingApprovalItems(none)).toEqual([])
    expect(pendingApprovalTotal(none)).toBe(0)

    const pending: PendingApprovals = {
      contributionPendingCount: 3,
      loanPendingCount: 1,
      loanSecondApprovalCount: 1,
      sharePurchasePendingCount: 2,
    }
    expect(pendingApprovalItems(pending)).toEqual([
      {
        kind: 'contributions',
        count: 3,
        path: `${ROUTES.contributions}?tab=approvals`,
      },
      {
        kind: 'loans',
        count: 2,
        path: `${ROUTES.loans}?tab=approvals`,
      },
      {
        kind: 'shares',
        count: 2,
        path: `${ROUTES.contributions}?tab=share-approvals`,
      },
    ])
    expect(pendingApprovalLabelKey('contributions', 3)).toBe(
      'notifications.pending.contributions_plural',
    )
    expect(pendingApprovalLabelKey('loans', 1)).toBe('notifications.pending.loans')
    expect(pendingApprovalLabelKey('shares', 2)).toBe('notifications.pending.shares_plural')
    expect(notificationBadgeCount(4, pending)).toBe(11)
  })
})
