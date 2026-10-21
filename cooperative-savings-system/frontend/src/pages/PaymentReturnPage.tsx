import { Alert, Box, Button, Stack, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import {
  clearBillingReturnContext,
  fetchSubscriptionPayment,
  paymentIdFromTxRef,
  readBillingReturnContext,
} from '@/shared/api/billing'
import { cooperativeSubscriptionQueryKey } from '@/shared/api/subscription'
import { getErrorMessage } from '@/shared/api/client'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ROUTES } from '@/shared/constants/routes'

/**
 * Flutterwave return landing. Query params (status, tx_ref, transaction_id) are untrusted
 * hints only. Entitlement changes only after OuWealth verifies with the provider.
 */
export function PaymentReturnPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const selectedCooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const stored = readBillingReturnContext()

  const paymentId = useMemo(() => {
    const fromQuery = searchParams.get('paymentId')
    if (fromQuery) return fromQuery
    if (stored.paymentId) return stored.paymentId
    return paymentIdFromTxRef(searchParams.get('tx_ref'))
  }, [searchParams, stored.paymentId])

  const cooperativeId = useMemo(() => {
    // Prefer the authenticated selected cooperative so a crafted return URL cannot
    // silently switch verification into another cooperative context in the UI.
    if (selectedCooperativeId) return selectedCooperativeId
    if (stored.cooperativeId) return stored.cooperativeId
    return searchParams.get('cooperativeId')
  }, [searchParams, selectedCooperativeId, stored.cooperativeId])

  const verifyQuery = useQuery({
    queryKey: ['billing', 'payment-return', cooperativeId, paymentId],
    queryFn: () => fetchSubscriptionPayment(cooperativeId!, paymentId!),
    enabled: Boolean(cooperativeId && paymentId),
    refetchInterval: (query) => {
      if (import.meta.env.MODE === 'test') return false
      return query.state.data?.status === 'PENDING' ? 5_000 : false
    },
  })

  useEffect(() => {
    if (!verifyQuery.data || verifyQuery.data.status === 'PENDING') return
    if (cooperativeId) {
      void queryClient.invalidateQueries({ queryKey: cooperativeSubscriptionQueryKey(cooperativeId) })
      void queryClient.invalidateQueries({
        queryKey: ['cooperatives', cooperativeId, 'billing', 'payments'],
      })
    }
    clearBillingReturnContext()
  }, [cooperativeId, queryClient, verifyQuery.data])

  const status = verifyQuery.data?.status
  const missing = !cooperativeId || !paymentId

  return (
    <Box data-testid="payment-return-page">
      <PageHeader
        title={t('pages.billing.title')}
        description={t('subscription.billing.verifyingPayment')}
      />
      <Stack spacing={2} sx={{ maxWidth: 560 }}>
        {missing ? (
          <Alert severity="warning" data-testid="payment-return-missing">
            {t('subscription.billing.paymentReturnMissing')}
          </Alert>
        ) : null}
        {!missing && verifyQuery.isLoading ? (
          <LoadingState variant="skeleton" rows={2} />
        ) : null}
        {!missing && verifyQuery.isError ? (
          <Alert severity="error">{getErrorMessage(verifyQuery.error, t('errors.generic'))}</Alert>
        ) : null}
        {!missing && !verifyQuery.isLoading && !verifyQuery.isError && status === 'PENDING' ? (
          <Alert
            severity={verifyQuery.data?.verificationUnavailable ? 'warning' : 'info'}
            data-testid={
              verifyQuery.data?.verificationUnavailable
                ? 'payment-return-verify-unavailable'
                : 'payment-return-pending'
            }
          >
            <Typography component="span" sx={{ display: 'block' }}>
              {verifyQuery.data?.verificationUnavailable
                ? t('subscription.billing.verificationUnavailable')
                : t('subscription.billing.verifyingPayment')}
            </Typography>
            {!verifyQuery.data?.verificationUnavailable ? (
              <Typography component="span" sx={{ mt: 1, display: 'block' }}>
                {t('subscription.billing.paymentPending')}
              </Typography>
            ) : null}
          </Alert>
        ) : null}
        {status === 'SUCCESS' ? (
          <Alert severity="success" data-testid="payment-return-success">
            {t('subscription.billing.paymentSuccess')}
          </Alert>
        ) : null}
        {status === 'FAILED' ? (
          <Alert severity="error" data-testid="payment-return-failed">
            {t('subscription.billing.paymentFailed')}
          </Alert>
        ) : null}
        {status === 'CANCELED' ? (
          <Alert severity="error" data-testid="payment-return-canceled">
            {t('subscription.billing.paymentCanceled')}
          </Alert>
        ) : null}
        <Button component={RouterLink} to={ROUTES.billing} variant="contained">
          {t('subscription.billing.backToBilling')}
        </Button>
      </Stack>
    </Box>
  )
}
