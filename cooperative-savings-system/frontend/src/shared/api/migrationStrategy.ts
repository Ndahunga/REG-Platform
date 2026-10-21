import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'

export type MigrationStrategy = 'OPENING_BALANCE' | 'DETAILED_TRANSACTION_HISTORY'

export interface MigrationStrategyState {
  strategy: MigrationStrategy | null
  cutoverDate?: string | null
  source?: string | null
  /** True when the scheme brought its history in both ways: no further import is accepted until it is resolved. */
  needsReview: boolean
  /** True once anything was brought in (the strategy can no longer change). */
  locked: boolean
  resolvedAt?: string | null
}

export interface MigrationReviewItem {
  cooperativeId: string
  cooperativeName: string
  strategy: MigrationStrategy
  cutoverDate?: string | null
  source: string
}

export async function fetchMigrationStrategy(cooperativeId: string): Promise<MigrationStrategyState> {
  const response = await apiClient.get<ApiResponse<MigrationStrategyState>>(`/cooperatives/${cooperativeId}/migration-strategy`)
  return unwrapApiData(response.data)
}

/** The President or Vice President (fund authority) says which strategy is authoritative and how it was reconciled. */
export async function resolveMigrationReview(
  cooperativeId: string,
  payload: { strategy: MigrationStrategy; cutoverDate?: string; note: string },
): Promise<MigrationStrategyState> {
  const response = await apiClient.post<ApiResponse<MigrationStrategyState>>(
    `/cooperatives/${cooperativeId}/migration-strategy/resolve`,
    payload,
  )
  return unwrapApiData(response.data)
}

/** Platform administrators: every scheme still flagged for migration review. */
export async function fetchMigrationReviews(): Promise<MigrationReviewItem[]> {
  const response = await apiClient.get<ApiResponse<MigrationReviewItem[]>>('/platform/migration-reviews')
  return unwrapApiData(response.data) ?? []
}
