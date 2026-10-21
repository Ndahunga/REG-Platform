import { describe, expect, it } from 'vitest'
import type { DashboardSummary } from '@/shared/types/dashboard'
import {
  applyCooperativeSummaries,
  platformOverviewFromCooperatives,
} from './platformOverviewFallback'

describe('platformOverviewFallback', () => {
  it('builds cooperative status counts from the list the Super Admin already has', () => {
    const overview = platformOverviewFromCooperatives(
      [{ status: 'ACTIVE' }, { status: 'ACTIVE' }, { status: 'SUSPENDED' }],
      3,
    )
    expect(overview.totalCooperatives).toBe(3)
    expect(overview.activeCooperatives).toBe(2)
    expect(overview.suspendedCooperatives).toBe(1)
    expect(overview.totalMembers).toBe(0)
  })

  it('fills member and pending counts from per-cooperative dashboard summaries', () => {
    const base = platformOverviewFromCooperatives([{ status: 'ACTIVE' }], 1)
    const summaries: DashboardSummary[] = [
      {
        totalMembers: 10,
        activeMembers: 8,
        regularContributionsTotal: 0,
        specialContributionsTotal: 0,
        actualContributionsTotal: 0,
        availableGroupFunds: 0,
        pendingSpecialApprovals: 2,
        overdueLoansCount: 1,
        pendingFinePayments: 3,
        pendingSocialApprovals: 4,
        pendingPayoutsCount: 5,
      },
      {
        totalMembers: 5,
        activeMembers: 5,
        regularContributionsTotal: 0,
        specialContributionsTotal: 0,
        actualContributionsTotal: 0,
        availableGroupFunds: 0,
        pendingSpecialApprovals: 1,
        overdueLoansCount: 0,
        pendingFinePayments: 0,
        pendingSocialApprovals: 0,
        pendingPayoutsCount: 1,
      },
    ]
    const overview = applyCooperativeSummaries(base, summaries)
    expect(overview.totalMembers).toBe(15)
    expect(overview.activeMembers).toBe(13)
    expect(overview.pendingSpecialContributions).toBe(3)
    expect(overview.overdueLoans).toBe(1)
    expect(overview.pendingFinePayments).toBe(3)
    expect(overview.pendingSocialContributions).toBe(4)
    expect(overview.pendingPayouts).toBe(6)
  })
})
