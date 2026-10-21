import { Box, Chip, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { canManageBilling, subscriptionStatusChipColor } from '@/features/billing/billingAccess'
import { BillingPlansAndCheckout } from '@/features/billing/BillingPlansAndCheckout'
import { CurrentSubscriptionCard } from '@/features/billing/CurrentSubscriptionCard'
import { PaymentHistoryPanel } from '@/features/billing/PaymentHistoryPanel'
import { effectiveSubscriptionStatus } from '@/features/subscription/subscriptionAccess'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { billingPlansQueryKey, fetchBillingPlans } from '@/shared/api/billing'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMyCooperatives } from '@/shared/api/cooperatives'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'

export function BillingPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const roles = useAppSelector((s) => s.auth.user?.roles)
  const manage = canManageBilling(roles)
  const {
    subscription,
    isLoading: subscriptionLoading,
    isError: subscriptionError,
    error: subscriptionQueryError,
    refetch,
  } = useCooperativeSubscription(cooperativeId)
  const status = effectiveSubscriptionStatus(subscription)

  const cooperativesQuery = useQuery({
    queryKey: ['cooperatives', 'mine'],
    queryFn: fetchMyCooperatives,
    enabled: Boolean(cooperativeId),
    staleTime: 60_000,
  })
  const schemeName = cooperativesQuery.data?.find((item) => item.id === cooperativeId)?.name

  const plansQuery = useQuery({
    queryKey: cooperativeId ? billingPlansQueryKey(cooperativeId) : ['billing', 'plans', 'none'],
    queryFn: () => fetchBillingPlans(cooperativeId!),
    enabled: Boolean(cooperativeId),
  })

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader title={t('pages.billing.title')} description={t('pages.billing.description')} />
        <EmptyState
          title={t('subscription.billing.selectCooperativeTitle')}
          description={t('subscription.billing.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  return (
    <Box data-testid="billing-page">
      <PageHeader
        title={t('pages.billing.title')}
        description={t('pages.billing.description')}
        actions={
          <Chip
            color={subscriptionStatusChipColor(status)}
            label={t(`subscription.status.${status}`)}
            aria-label={`${t('subscription.billing.status')}: ${t(`subscription.status.${status}`)}`}
          />
        }
      />
      {schemeName ? (
        <Typography variant="subtitle1" sx={{ mb: 2 }} data-testid="billing-scheme-name">
          {t('subscription.billing.schemeLabel')}: {schemeName}
        </Typography>
      ) : null}

      {subscriptionLoading || plansQuery.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}
      {subscriptionError ? (
        <ErrorState
          message={getErrorMessage(subscriptionQueryError)}
          onRetry={() => void refetch()}
        />
      ) : null}
      {plansQuery.isError ? (
        <ErrorState
          message={getErrorMessage(plansQuery.error)}
          onRetry={() => void plansQuery.refetch()}
        />
      ) : null}

      {!subscriptionLoading && !plansQuery.isLoading && !subscriptionError && !plansQuery.isError ? (
        <Stack spacing={3}>
          <CurrentSubscriptionCard subscription={subscription} plans={plansQuery.data} />
          {plansQuery.data ? (
            <BillingPlansAndCheckout
              cooperativeId={cooperativeId}
              plans={plansQuery.data}
              canManage={manage}
              trialActive={status === 'TRIAL'}
            />
          ) : null}
          <PaymentHistoryPanel cooperativeId={cooperativeId} />
        </Stack>
      ) : null}
    </Box>
  )
}
