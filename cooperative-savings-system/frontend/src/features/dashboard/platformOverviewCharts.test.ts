import { describe, expect, it } from 'vitest'
import type { PlatformOverview } from '@/shared/types/dashboard'
import {
  cooperativeStatusSlices,
  pendingWorkBars,
  totalPendingWork,
} from './platformOverviewCharts'

const sample: PlatformOverview = {
  totalCooperatives: 5,
  activeCooperatives: 3,
  inactiveCooperatives: 0,
  suspendedCooperatives: 2,
  archivedCooperatives: 0,
  totalMembers: 40,
  activeMembers: 36,
  totalUsers: 42,
  pendingContributionReviews: 4,
  pendingSpecialContributions: 1,
  pendingLoans: 2,
  overdueLoans: 0,
  pendingFinePayments: 3,
  pendingSocialContributions: 0,
  pendingPayouts: 1,
}

describe('platformOverviewCharts', () => {
  it('drops empty cooperative status slices for the pie', () => {
    expect(cooperativeStatusSlices(sample).map((s) => s.key)).toEqual(['ACTIVE', 'SUSPENDED'])
  })

  it('keeps pending-work bars even when some counts are zero', () => {
    const bars = pendingWorkBars(sample)
    expect(bars).toHaveLength(7)
    expect(totalPendingWork(sample)).toBe(11)
  })
})
