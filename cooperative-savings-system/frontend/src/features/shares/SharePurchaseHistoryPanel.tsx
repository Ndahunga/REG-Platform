import { Chip, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { contributionStatusColor } from '@/features/contributions'
import { getErrorMessage } from '@/shared/api/client'
import { fetchSharePurchases } from '@/shared/api/shares'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { SharePurchase } from '@/shared/types/share'
import { formatMoney } from '@/shared/utils/formatMoney'

interface SharePurchaseHistoryPanelProps {
  cooperativeId: string
  memberUserId: string
}

export function SharePurchaseHistoryPanel({
  cooperativeId,
  memberUserId,
}: SharePurchaseHistoryPanelProps) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['shares', 'purchases', cooperativeId, memberUserId],
    queryFn: () =>
      fetchSharePurchases(cooperativeId, {
        memberUserId,
        page: 0,
        size: 50,
      }),
    enabled: Boolean(cooperativeId && memberUserId),
  })

  const columns: TableColumn<SharePurchase>[] = useMemo(
    () => [
      {
        id: 'shares',
        label: t('shares.fields.numberOfShares'),
        render: (row) => String(row.numberOfShares),
      },
      {
        id: 'price',
        label: t('shares.fields.pricePerShare'),
        render: (row) => formatMoney(row.pricePerShare, { currency: row.currency }),
      },
      {
        id: 'total',
        label: t('shares.fields.totalAmount'),
        render: (row) => formatMoney(row.totalAmount, { currency: row.currency }),
      },
      {
        id: 'paymentDate',
        label: t('contributions.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
      },
      {
        id: 'date',
        label: t('shares.fields.requestedAt'),
        render: (row) => (row.requestedAt ? row.requestedAt.slice(0, 10) : '—'),
      },
      {
        id: 'status',
        label: t('shares.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={contributionStatusColor(String(row.status))}
            label={t(`shares.status.${row.status}`, { defaultValue: String(row.status) })}
          />
        ),
      },
    ],
    [t],
  )

  if (query.isLoading) return <LoadingState variant="skeleton" rows={3} />
  if (query.isError) {
    return (
      <ErrorState
        message={getErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="h6">{t('shares.history.title')}</Typography>
      <ResponsiveTable
        columns={columns}
        rows={query.data?.content ?? []}
        getRowId={(row) => row.id}
        emptyTitle={t('shares.history.emptyTitle')}
        emptyDescription={t('shares.history.emptyDescription')}
      />
    </Stack>
  )
}
