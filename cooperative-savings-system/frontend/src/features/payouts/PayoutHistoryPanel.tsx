import {
  Box,
  Chip,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { fetchPayoutRuns } from '@/shared/api/payouts'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { PayoutRun } from '@/shared/types/payout'
import { PAYOUT_RUN_STATUSES, payoutRunDisplayName } from '@/shared/types/payout'
import { formatMoney } from '@/shared/utils/formatMoney'
import { payoutStatusColor } from './payoutHelpers'

interface PayoutHistoryPanelProps {
  cooperativeId: string
}

export function PayoutHistoryPanel({ cooperativeId }: PayoutHistoryPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const query = useQuery({
    queryKey: ['payouts', 'runs', cooperativeId, status, page, size],
    queryFn: () =>
      fetchPayoutRuns(cooperativeId, {
        status: status || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId),
  })

  const columns: TableColumn<PayoutRun>[] = useMemo(
    () => [
      {
        id: 'name',
        label: t('payouts.fields.name'),
        render: (row) => payoutRunDisplayName(row),
      },
      {
        id: 'period',
        label: t('payouts.fields.period'),
        render: (row) => `${row.periodFrom || '—'} → ${row.periodTo || '—'}`,
      },
      {
        id: 'pool',
        label: t('payouts.fields.payoutPoolAmount'),
        render: (row) => formatMoney(row.payoutPoolAmount, { currency: row.currency }),
      },
      {
        id: 'status',
        label: t('payouts.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={payoutStatusColor(String(row.status))}
            label={t(`payouts.status.${row.status}`, {
              defaultValue: String(row.status),
            })}
          />
        ),
      },
      {
        id: 'createdAt',
        label: t('payouts.fields.createdAt'),
        render: (row) => row.createdAt || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  const rows = query.data?.content ?? []

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2 }}
      >
        <TextField
          select
          size="small"
          label={t('payouts.fields.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {PAYOUT_RUN_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`payouts.status.${s}`, { defaultValue: s })}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <ListQueryBody
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        onRetry={() => void query.refetch()}
      >
      <ResponsiveTable
        columns={columns}
        rows={rows}
        getRowId={(row) => row.id}
        emptyTitle={t('payouts.historyEmptyTitle')}
        emptyDescription={t('payouts.historyEmptyDescription')}
        onRowClick={(row) => navigate(ROUTES.payoutDetail(row.id))}
      />

      <TablePagination
        component="div"
        count={query.data?.totalElements ?? 0}
        page={page}
        onPageChange={(_, next) => setPage(next)}
        rowsPerPage={size}
        onRowsPerPageChange={(e) => {
          setSize(Number(e.target.value))
          setPage(0)
        }}
        rowsPerPageOptions={[5, 10, 25]}
      />
      </ListQueryBody>
    </Box>
  )
}
