import type { CooperativeStatus } from '@/shared/types/cooperative'
import type { DashboardSummary, PlatformOverview } from '@/shared/types/dashboard'

const EMPTY_STATUS_COUNTS: Record<CooperativeStatus, number> = {
  ACTIVE: 0,
  INACTIVE: 0,
  SUSPENDED: 0,
  ARCHIVED: 0,
}

function emptyOverview(): PlatformOverview {
  return {
    totalCooperatives: 0,
    activeCooperatives: 0,
    inactiveCooperatives: 0,
    suspendedCooperatives: 0,
    archivedCooperatives: 0,
    totalMembers: 0,
    activeMembers: 0,
    totalUsers: 0,
    pendingContributionReviews: 0,
    pendingSpecialContributions: 0,
    pendingLoans: 0,
    overdueLoans: 0,
    pendingFinePayments: 0,
    pendingSocialContributions: 0,
    pendingPayouts: 0,
  }
}

export function platformOverviewFromCooperatives(
  cooperatives: Array<{ status: string }>,
  totalCount: number,
): PlatformOverview {
  const counts = { ...EMPTY_STATUS_COUNTS }
  for (const cooperative of cooperatives) {
    const status = cooperative.status as CooperativeStatus
    if (status in counts) {
      counts[status] += 1
    }
  }
  return {
    ...emptyOverview(),
    totalCooperatives: totalCount,
    activeCooperatives: counts.ACTIVE,
    inactiveCooperatives: counts.INACTIVE,
    suspendedCooperatives: counts.SUSPENDED,
    archivedCooperatives: counts.ARCHIVED,
  }
}

export function applyCooperativeSummaries(
  base: PlatformOverview,
  summaries: DashboardSummary[],
): PlatformOverview {
  return {
    ...base,
    totalMembers: summaries.reduce((sum, row) => sum + (row.totalMembers ?? 0), 0),
    activeMembers: summaries.reduce((sum, row) => sum + (row.activeMembers ?? 0), 0),
    pendingSpecialContributions: summaries.reduce(
      (sum, row) => sum + (row.pendingSpecialApprovals ?? 0),
      0,
    ),
    overdueLoans: summaries.reduce((sum, row) => sum + (row.overdueLoansCount ?? 0), 0),
    pendingFinePayments: summaries.reduce((sum, row) => sum + (row.pendingFinePayments ?? 0), 0),
    pendingSocialContributions: summaries.reduce(
      (sum, row) => sum + (row.pendingSocialApprovals ?? 0),
      0,
    ),
    pendingPayouts: summaries.reduce((sum, row) => sum + (row.pendingPayoutsCount ?? 0), 0),
  }
}
