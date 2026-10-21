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
import { fetchLoans, fetchMyLoans } from '@/shared/api/loans'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import type { Loan } from '@/shared/types/loan'
import { LOAN_STATUSES, loanDisplayName } from '@/shared/types/loan'
import { formatMoney } from '@/shared/utils/formatMoney'
import { loanStatusColor } from './loanHelpers'

interface LoansListPanelProps {
  cooperativeId: string
  mode: 'mine' | 'all' | 'approvals'
}

export function LoansListPanel({ cooperativeId, mode }: LoansListPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const isAll = mode === 'all'
  const isApprovals = mode === 'approvals'

  const query = useQuery({
    queryKey: [
      'loans',
      mode,
      cooperativeId,
      debouncedSearch,
      status,
      page,
      size,
    ],
    queryFn: () => {
      const params = {
        q: debouncedSearch || undefined,
        status: isApprovals ? undefined : status || undefined,
        pendingApproval: isApprovals ? true : undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }
      return isAll || isApprovals
        ? fetchLoans(cooperativeId, params)
        : fetchMyLoans(cooperativeId, params)
    },
    enabled: Boolean(cooperativeId),
  })

  const columns: TableColumn<Loan>[] = useMemo(
    () => [
      ...(isAll || isApprovals
        ? [
            {
              id: 'member',
              label: t('loans.fields.member'),
              render: (row: Loan) => loanDisplayName(row),
            },
          ]
        : []),
      {
        id: 'amount',
        label: t('loans.fields.amount'),
        render: (row) =>
          formatMoney(row.principalAmount ?? row.approvedAmount ?? row.requestedAmount),
      },
      {
        id: 'outstanding',
        label: t('loans.fields.outstanding'),
        render: (row) =>
          formatMoney(
            (Number(row.outstandingPrincipal) || 0) + (Number(row.outstandingInterest) || 0),
          ),
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('loans.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={loanStatusColor(String(row.status))}
            label={t(`loans.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      {
        id: 'dueDate',
        label: t('loans.fields.maturityDate'),
        render: (row) => row.dueDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'requestDate',
        label: t('loans.fields.requestDate'),
        render: (row) => row.requestDate || '—',
        hideOnMobile: true,
      },
    ],
    [isAll, isApprovals, t],
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
        {isAll ? (
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
        ) : null}
        {isApprovals ? null : (
        <TextField
          select
          size="small"
          label={t('loans.fields.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {LOAN_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`loans.status.${s}`)}
            </MenuItem>
          ))}
        </TextField>
        )}
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
        emptyTitle={t('loans.emptyTitle')}
        emptyDescription={
          isAll ? t('loans.emptyDescription') : t('loans.myEmptyDescription')
        }
        onRowClick={(row) => navigate(ROUTES.loanDetail(row.id))}
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
