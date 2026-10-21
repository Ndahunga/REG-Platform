import {
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchContributions, fetchMyContributions } from '@/shared/api/contributions'
import { ApprovalHistory } from '@/shared/components/ApprovalHistory'
import { DateRangeFields } from '@/shared/components/DateRangeFields'
import { EmptyState } from '@/shared/components/EmptyState'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import type { Contribution } from '@/shared/types/contribution'
import { CONTRIBUTION_STATUSES } from '@/shared/types/contribution'
import { ObligationDetails } from './ObligationDetails'
import { formatMoney } from '@/shared/utils/formatMoney'
import { validateOptionalDateRange } from '@/shared/utils/filterValidation'
import { contributionStatusColor } from './contributionHelpers'

interface HistoryPanelProps {
  cooperativeId: string
  isAdmin: boolean
}

export function HistoryPanel({ cooperativeId, isAdmin }: HistoryPanelProps) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [status, setStatus] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const dateIssue = validateOptionalDateRange(fromDate, toDate)
  const filtersValid = !dateIssue

  const query = useQuery({
    queryKey: [
      'contributions',
      isAdmin ? 'history' : 'my',
      cooperativeId,
      debouncedSearch,
      status,
      fromDate,
      toDate,
      page,
      size,
    ],
    queryFn: () => {
      const params = {
        q: debouncedSearch || undefined,
        status: status || undefined,
        fromDate: fromDate || undefined,
        toDate: toDate || undefined,
        page,
        size,
      }
      return isAdmin
        ? fetchContributions(cooperativeId, params)
        : fetchMyContributions(cooperativeId, params)
    },
    enabled: Boolean(cooperativeId) && filtersValid,
  })

  const columns: TableColumn<Contribution>[] = useMemo(
    () => [
      ...(isAdmin
        ? [
            {
              id: 'member',
              label: t('contributions.fields.member'),
              render: (row: Contribution) => row.fullName || row.username || row.memberUserId,
            },
          ]
        : []),
      {
        id: 'period',
        label: t('contributions.fields.period'),
        render: (row) => `${row.year}-${String(row.month).padStart(2, '0')}`,
      },
      {
        id: 'shares',
        label: t('contributions.fields.shares'),
        render: (row) => String(row.shareCount ?? 1),
      },
      {
        id: 'expected',
        label: t('contributions.fields.required'),
        render: (row) => formatMoney(row.expectedAmount),
        hideOnMobile: true,
      },
      {
        id: 'paid',
        label: t('contributions.fields.paid'),
        render: (row) => formatMoney(row.paidAmount),
      },
      {
        id: 'outstanding',
        label: t('contributions.fields.remaining'),
        render: (row) => formatMoney(row.remainingAmount ?? row.outstandingAmount),
        hideOnMobile: true,
      },
      {
        id: 'obligation',
        label: t('contributions.obligation.column'),
        render: (row) => <ObligationDetails fields={row} />,
      },
      {
        id: 'status',
        label: t('contributions.fields.status'),
        render: (row) => (
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <Chip
              size="small"
              color={contributionStatusColor(String(row.status))}
              label={t(`contributions.status.${row.status}`, { defaultValue: row.status })}
            />
            {row.reviewStatus ? (
              <Chip
                size="small"
                variant="outlined"
                color={contributionStatusColor(String(row.reviewStatus))}
                label={t(`contributions.reviewStatus.${row.reviewStatus}`, {
                  defaultValue: String(row.reviewStatus),
                })}
              />
            ) : null}
          </Stack>
        ),
      },
      {
        id: 'paymentDate',
        label: t('contributions.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
        hideOnMobile: true,
      },
    ],
    [isAdmin, t],
  )

  const rows = query.data?.content ?? []

  const resetPage = () => setPage(0)

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        useFlexGap
        sx={{ mb: 2, flexWrap: 'wrap' }}
      >
        {isAdmin ? (
          <TextField
            size="small"
            label={t('common.search')}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              resetPage()
            }}
            sx={{ minWidth: 200 }}
          />
        ) : null}
        <TextField
          select
          size="small"
          label={t('contributions.fields.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            resetPage()
          }}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {CONTRIBUTION_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`contributions.status.${s}`)}
            </MenuItem>
          ))}
        </TextField>
        <DateRangeFields
          from={fromDate}
          to={toDate}
          onFromChange={(value) => {
            setFromDate(value)
            resetPage()
          }}
          onToChange={(value) => {
            setToDate(value)
            resetPage()
          }}
          fromLabel={t('common.fromDate')}
          toLabel={t('common.toDate')}
          issue={dateIssue}
        />
        <Button
          variant="outlined"
          onClick={() => {
            setSearch('')
            setStatus('')
            setFromDate('')
            setToDate('')
            resetPage()
          }}
          sx={{ minHeight: 40 }}
        >
          {t('common.clearFilters')}
        </Button>
      </Stack>

      {!isAdmin ? (
        <Box sx={{ mb: 1.5 }}>
          <Chip size="small" label={t('contributions.myContributions')} color="primary" />
        </Box>
      ) : null}

      <ListQueryBody
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        onRetry={() => void query.refetch()}
        enabled={filtersValid}
      >
        {rows.length === 0 ? (
          <EmptyState
            title={t('contributions.historyEmptyTitle')}
            description={t('contributions.historyEmptyDescription')}
          />
        ) : (
          <>
            <ResponsiveTable
              columns={columns}
              rows={rows}
              getRowId={(row) => row.id}
              onRowClick={(row) => setSelectedId(row.id)}
            />
            <TablePagination
              component="div"
              count={query.data?.totalElements ?? 0}
              page={page}
              onPageChange={(_, next) => setPage(next)}
              rowsPerPage={size}
              onRowsPerPageChange={(e) => {
                setSize(Number(e.target.value))
                resetPage()
              }}
              rowsPerPageOptions={[5, 10, 25]}
            />
            {selectedId ? (
              <Box sx={{ mt: 2 }}>
                <ApprovalHistory
                  events={rows.find((row) => row.id === selectedId)?.approvalHistory}
                />
              </Box>
            ) : null}
          </>
        )}
      </ListQueryBody>
    </Box>
  )
}
