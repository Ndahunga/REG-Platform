import { fetchCooperativeSubscription } from './cooperatives'
import type { CooperativeSubscription } from '@/shared/types/cooperative'

export const cooperativeSubscriptionQueryKey = (cooperativeId: string) =>
  ['cooperatives', cooperativeId, 'subscription'] as const

export async function fetchSubscription(
  cooperativeId: string,
): Promise<CooperativeSubscription | null> {
  return fetchCooperativeSubscription(cooperativeId)
}
