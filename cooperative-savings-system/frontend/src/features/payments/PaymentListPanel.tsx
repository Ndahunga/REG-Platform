import { Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMyPayments, fetchPaymentReviewQueue, fetchPayments } from '@/shared/api/memberPayments'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { PAYMENT_STATUSES, type PaymentStatus, type PaymentSummary } from '@/shared/types/memberPayment'
import { formatMoney } from '@/shared/utils/formatMoney'
import { PaymentDetailDialog } from './PaymentDetailDialog'
import { PaymentStatusChip } from './PaymentParts'

export type PaymentListSource = 'mine' | 'review' | 'verified' | 'posted' | 'all'

interface PaymentListPanelProps {
  cooperativeId: string
  source: PaymentListSource
}

const PAGE_SIZE = 20

function allocationSummary(types: PaymentSummary['allocationTypes'], t: (key: string) => string): string {
  const unique = [...new Set(types)]
  return unique.map((type) => t(`payments.allocation.type.${type}`)).join(' + ')
}

/**
 * A page of payments. 'mine' is the member's own history; the other sources are the officer views, all scoped to the
 * selected Saving Scheme by the backend. Opening a row shows the payment detail with the actions the user may take.
 */
export function PaymentListPanel({ cooperativeId, source }: PaymentListPanelProps) {
  const { t } = useTranslation()
  const staff = source !== 'mine'
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<PaymentStatus | ''>('')
  const [openId, setOpenId] = useState<string | null>(null)

  const query = useQuery({
    queryKey: ['member-payments', cooperativeId, 'list', source, status, page],
    queryFn: () => {
      const paging = { page, size: PAGE_SIZE }
      switch (source) {
        case 'mine':
          return fetchMyPayments(cooperativeId, { ...paging, status: status || undefined })
        case 'review':
          return fetchPaymentReviewQueue(cooperativeId, paging)
        case 'verified':
          return fetchPayments(cooperativeId, { ...paging, status: 'VERIFIED' })
        case 'posted':
          return fetchPayments(cooperativeId, { ...paging, status: 'POSTED' })
        default:
          return fetchPayments(cooperativeId, { ...paging, status: status || undefined })
      }
    },
  })

  const columns: TableColumn<PaymentSummary>[] = [
    ...(staff
      ? [{ id: 'member', label: t('payments.columns.member'), render: (row: PaymentSummary) => row.memberName || row.memberUserId }]
      : []),
    { id: 'date', label: t('payments.columns.date'), render: (row) => row.paymentDate },
    {
      id: 'amount',
      label: t('payments.columns.amount'),
      render: (row) => formatMoney(row.amount, { currency: row.currency }),
    },
    { id: 'method', label: t('payments.columns.method'), render: (row) => t(`payments.method.${row.paymentMethod}`), hideOnMobile: true },
    { id: 'reference', label: t('payments.columns.reference'), render: (row) => row.transactionReference || '—', hideOnMobile: true },
    { id: 'allocations', label: t('payments.columns.allocations'), render: (row) => allocationSummary(row.allocationTypes, t) },
    { id: 'status', label: t('payments.columns.status'), render: (row) => <PaymentStatusChip status={row.status} /> },
  ]

  const filterable = source === 'mine' || source === 'all'
  const data = query.data

  return (
    <Stack spacing={2} data-testid={`payment-list-${source}`}>
      {filterable ? (
        <TextField
          select
          size="small"
          label={t('payments.columns.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as PaymentStatus | '')
            setPage(0)
          }}
          sx={{ maxWidth: 260 }}
        >
          <MenuItem value="">{t('payments.filter.all')}</MenuItem>
          {PAYMENT_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`payments.status.${s}`)}
            </MenuItem>
          ))}
        </TextField>
      ) : null}

      {query.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}
      {query.isError ? (
        <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
      ) : null}
      {data ? (
        <>
          <ResponsiveTable
            columns={columns}
            rows={data.content}
            getRowId={(row) => row.id}
            emptyTitle={t(`payments.empty.${source}`)}
            emptyDescription=""
            onRowClick={(row) => setOpenId(row.id)}
          />
          {data.totalPages > 1 ? (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Button onClick={() => setPage((p) => Math.max(p - 1, 0))} disabled={page === 0}>
                {t('payments.previous')}
              </Button>
              <Typography variant="body2">{t('payments.pagination', { page: page + 1, pages: data.totalPages })}</Typography>
              <Button onClick={() => setPage((p) => p + 1)} disabled={data.last || page + 1 >= data.totalPages}>
                {t('payments.next')}
              </Button>
            </Box>
          ) : null}
        </>
      ) : null}

      {openId ? (
        <PaymentDetailDialog cooperativeId={cooperativeId} paymentId={openId} staff={staff} onClose={() => setOpenId(null)} />
      ) : null}
    </Stack>
  )
}
