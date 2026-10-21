import {
  Box,
  Chip,
  Stack,
  TablePagination,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMyPayouts } from '@/shared/api/payouts'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { PayoutLine } from '@/shared/types/payout'
import { formatMoney } from '@/shared/utils/formatMoney'
import { formatPayoutPercentage, payoutStatusColor } from './payoutHelpers'

interface PayoutMyPanelProps {
  cooperativeId: string
}

export function PayoutMyPanel({ cooperativeId }: PayoutMyPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const query = useQuery({
    queryKey: ['payouts', 'my', cooperativeId, page, size],
    queryFn: () =>
      fetchMyPayouts(cooperativeId, {
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId),
  })

  const columns: TableColumn<PayoutLine>[] = useMemo(
    () => [
      {
        id: 'run',
        label: t('payouts.fields.name'),
        render: (row) =>
          row.runName ||
          (row.periodFrom && row.periodTo
            ? `${row.periodFrom} → ${row.periodTo}`
            : row.payoutRunId || row.runId || '—'),
      },
      {
        id: 'period',
        label: t('payouts.fields.period'),
        render: (row) =>
          row.periodFrom && row.periodTo
            ? `${row.periodFrom} → ${row.periodTo}`
            : '—',
        hideOnMobile: true,
      },
      {
        id: 'eligible',
        label: t('payouts.fields.eligibleAmount'),
        render: (row) =>
          formatMoney(row.eligibleContributionAmount, { currency: row.currency }),
      },
      {
        id: 'percentage',
        label: t('payouts.fields.percentage'),
        render: (row) => formatPayoutPercentage(row.percentage),
        hideOnMobile: true,
      },
      {
        id: 'payout',
        label: t('payouts.fields.payoutAmount'),
        render: (row) => formatMoney(row.payoutAmount, { currency: row.currency }),
      },
      {
        id: 'status',
        label: t('payouts.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={payoutStatusColor(String(row.runStatus || row.status))}
            label={t(`payouts.status.${row.runStatus || row.status}`, {
              defaultValue: String(row.runStatus || row.status),
            })}
          />
        ),
      },
    ],
    [t],
  )

  if (query.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (query.isError) {
    return (
      <ErrorState
        message={getErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const data = query.data
  const rows = Array.isArray(data) ? data : (data?.content ?? [])
  const total = Array.isArray(data) ? data.length : (data?.totalElements ?? 0)
  const paged = Array.isArray(data)

  return (
    <Box>
      <Stack spacing={0} sx={{ mb: 1 }}>
        <ResponsiveTable
          columns={columns}
          rows={paged ? rows.slice(page * size, page * size + size) : rows}
          getRowId={(row) => row.id}
          emptyTitle={t('payouts.myEmptyTitle')}
          emptyDescription={t('payouts.myEmptyDescription')}
          onRowClick={(row) => {
            const runId = row.payoutRunId || row.runId
            if (runId) navigate(ROUTES.payoutDetail(runId))
          }}
        />
      </Stack>

      <TablePagination
        component="div"
        count={total}
        page={page}
        onPageChange={(_, next) => setPage(next)}
        rowsPerPage={size}
        onRowsPerPageChange={(e) => {
          setSize(Number(e.target.value))
          setPage(0)
        }}
        rowsPerPageOptions={[5, 10, 25]}
      />
    </Box>
  )
}
