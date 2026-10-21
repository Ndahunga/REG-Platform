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
import { fetchInvestments } from '@/shared/api/investments'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import type { Investment } from '@/shared/types/investment'
import { INVESTMENT_STATUSES } from '@/shared/types/investment'
import { formatMoney } from '@/shared/utils/formatMoney'
import { investmentStatusColor } from './investmentHelpers'

interface InvestmentsListPanelProps {
  cooperativeId: string
}

export function InvestmentsListPanel({ cooperativeId }: InvestmentsListPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const query = useQuery({
    queryKey: [
      'investments',
      cooperativeId,
      debouncedSearch,
      status,
      page,
      size,
    ],
    queryFn: () =>
      fetchInvestments(cooperativeId, {
        q: debouncedSearch || undefined,
        status: status || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId),
  })

  const columns: TableColumn<Investment>[] = useMemo(
    () => [
      {
        id: 'name',
        label: t('investments.fields.name'),
        render: (row) => row.name,
      },
      {
        id: 'amount',
        label: t('investments.fields.amount'),
        render: (row) => formatMoney(row.amount, { currency: row.currency }),
      },
      {
        id: 'remaining',
        label: t('investments.fields.remainingCapital'),
        render: (row) =>
          formatMoney(row.remainingCapital ?? 0, { currency: row.currency }),
      },
      {
        id: 'status',
        label: t('investments.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={investmentStatusColor(String(row.status))}
            label={t(`investments.status.${row.status}`, {
              defaultValue: String(row.status),
            })}
          />
        ),
      },
      {
        id: 'expectedReturnDate',
        label: t('investments.fields.expectedReturnDate'),
        render: (row) => row.expectedReturnDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'profit',
        label: t('investments.fields.totalProfitReturned'),
        render: (row) =>
          formatMoney(row.totalProfitReturned ?? 0, { currency: row.currency }),
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
        useFlexGap
        sx={{ mb: 2, flexWrap: 'wrap' }}
      >
        <TextField
          size="small"
          label={t('common.search')}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 180 }}
        />
        <TextField
          select
          size="small"
          label={t('investments.fields.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {INVESTMENT_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`investments.status.${s}`)}
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
        emptyTitle={t('investments.emptyTitle')}
        emptyDescription={t('investments.emptyDescription')}
        onRowClick={(row) => navigate(ROUTES.investmentDetail(row.id))}
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
