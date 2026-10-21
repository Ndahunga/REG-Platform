import { useQuery } from '@tanstack/react-query'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsSuperAdmin, selectSelectedCooperativeId } from '@/app/store/authSlice'
import {
  cooperativeSubscriptionQueryKey,
  fetchSubscription,
} from '@/shared/api/subscription'
import { resolveSubscriptionAccess } from './subscriptionAccess'

export function useCooperativeSubscription(cooperativeId?: string | null) {
  const selectedId = useAppSelector(selectSelectedCooperativeId)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const id = cooperativeId === undefined ? selectedId : cooperativeId

  const query = useQuery({
    queryKey: id ? cooperativeSubscriptionQueryKey(id) : ['cooperatives', 'subscription', 'none'],
    queryFn: () => fetchSubscription(id!),
    enabled: Boolean(id),
    staleTime: 60_000,
  })

  const access = resolveSubscriptionAccess({
    subscription: query.data,
    isPending: Boolean(id) && !query.isSuccess,
    isSuperAdmin,
    hasCooperative: Boolean(id),
  })

  return {
    ...query,
    cooperativeId: id,
    subscription: query.data ?? null,
    isSuperAdmin,
    ...access,
  }
}
