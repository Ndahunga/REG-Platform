import type { PlatformOverview } from '@/shared/types/dashboard'

export const COOPERATIVE_STATUS_COLORS: Record<string, string> = {
  ACTIVE: '#1B4D8C',
  INACTIVE: '#94A3B8',
  SUSPENDED: '#FF7A00',
  ARCHIVED: '#64748B',
}

export const PENDING_WORK_COLORS: Record<string, string> = {
  contributions: '#1B4D8C',
  special: '#4A7AB8',
  loans: '#FF7A00',
  overdue: '#C62828',
  fines: '#FF5C00',
  social: '#0D9488',
  payouts: '#7C3AED',
}

export interface NamedCountSlice {
  key: string
  value: number
}

export function cooperativeStatusSlices(overview: PlatformOverview): NamedCountSlice[] {
  return [
    { key: 'ACTIVE', value: overview.activeCooperatives },
    { key: 'INACTIVE', value: overview.inactiveCooperatives },
    { key: 'SUSPENDED', value: overview.suspendedCooperatives },
    { key: 'ARCHIVED', value: overview.archivedCooperatives },
  ].filter((slice) => slice.value > 0)
}

export function pendingWorkBars(overview: PlatformOverview): NamedCountSlice[] {
  return [
    { key: 'contributions', value: overview.pendingContributionReviews },
    { key: 'special', value: overview.pendingSpecialContributions },
    { key: 'loans', value: overview.pendingLoans },
    { key: 'overdue', value: overview.overdueLoans },
    { key: 'fines', value: overview.pendingFinePayments },
    { key: 'social', value: overview.pendingSocialContributions },
    { key: 'payouts', value: overview.pendingPayouts },
  ]
}

export function totalPendingWork(overview: PlatformOverview): number {
  return pendingWorkBars(overview).reduce((sum, bar) => sum + bar.value, 0)
}
