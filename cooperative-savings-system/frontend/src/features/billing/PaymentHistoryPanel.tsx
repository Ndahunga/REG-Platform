import { Chip, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { billingPaymentsQueryKey, fetchSubscriptionPayments } from '@/shared/api/billing'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { SubscriptionPaymentRecord } from '@/shared/types/billing'
import { formatMoney } from '@/shared/utils/formatMoney'
import { formatSubscriptionDate } from '@/features/subscription/subscriptionAccess'
import { paymentStatusChipColor } from './billingAccess'

interface PaymentHistoryPanelProps {
  cooperativeId: string
}

export function PaymentHistoryPanel({ cooperativeId }: PaymentHistoryPanelProps) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: billingPaymentsQueryKey(cooperativeId, { page: 0, size: 20 }),
    queryFn: () =>
      fetchSubscriptionPayments(cooperativeId, {
        page: 0,
        size: 20,
        sort: 'initiatedAt,desc',
      }),
  })

  const columns: TableColumn<SubscriptionPaymentRecord>[] = [
    {
      id: 'date',
      label: t('subscription.billing.colDate'),
      render: (row) => formatSubscriptionDate(row.paidAt || row.initiatedAt) || '—',
    },
    {
      id: 'plan',
      label: t('subscription.billing.colPlan'),
      render: (row) => t(`subscription.cycle.${row.billingCycle}`),
    },
    {
      id: 'method',
      label: t('subscription.billing.colMethod'),
      render: (row) =>
        row.paymentChannel === 'MTN_MOMO'
          ? t('subscription.billing.mtnMomo')
          : t('subscription.billing.bankCard'),
    },
    {
      id: 'amount',
      label: t('subscription.billing.colAmount'),
      render: (row) => formatMoney(row.amount, { currency: row.currency }),
    },
    {
      id: 'status',
      label: t('subscription.billing.colStatus'),
      render: (row) => (
        <Chip
          size="small"
          color={paymentStatusChipColor(row.status)}
          label={t(`subscription.billing.paymentStatus.${row.status}`)}
          aria-label={`${t('subscription.billing.colStatus')}: ${t(`subscription.billing.paymentStatus.${row.status}`)}`}
        />
      ),
    },
  ]

  return (
    <Stack spacing={1} data-testid="payment-history">
      <Typography variant="h6" component="h2">
        {t('subscription.billing.historyTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {t('subscription.billing.historyHint')}
      </Typography>
      {query.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!query.isLoading && !query.isError ? (
        <ResponsiveTable
          columns={columns}
          rows={query.data?.content ?? []}
          getRowId={(row) => row.id}
          emptyTitle={t('subscription.billing.historyEmpty')}
        />
      ) : null}
    </Stack>
  )
}
