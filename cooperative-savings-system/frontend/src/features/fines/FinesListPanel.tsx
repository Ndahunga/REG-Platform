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
import { fetchFines, fetchMyFines } from '@/shared/api/fines'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { Fine } from '@/shared/types/fine'
import { FINE_STATUSES, fineDisplayName } from '@/shared/types/fine'
import { formatMoney } from '@/shared/utils/formatMoney'
import { fineStatusColor } from './fineHelpers'

interface FinesListPanelProps {
  cooperativeId: string
  mode: 'mine' | 'all'
}

export function FinesListPanel({ cooperativeId, mode }: FinesListPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const isAll = mode === 'all'

  const query = useQuery({
    queryKey: ['fines', mode, cooperativeId, status, page, size],
    queryFn: async () => {
      if (isAll) {
        return fetchFines(cooperativeId, {
          status: status || undefined,
          page,
          size,
          sort: 'createdAt,desc',
        })
      }
      return fetchMyFines(cooperativeId, {
        status: status || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      })
    },
    enabled: Boolean(cooperativeId),
  })

  const columns: TableColumn<Fine>[] = useMemo(
    () => [
      ...(isAll
        ? [
            {
              id: 'member',
              label: t('fines.fields.member'),
              render: (row: Fine) => fineDisplayName(row),
            },
          ]
        : []),
      {
        id: 'total',
        label: t('fines.fields.totalAmount'),
        render: (row) => formatMoney(row.totalAmount),
      },
      {
        id: 'period',
        label: t('fines.fields.contributionMonth'),
        render: (row) =>
          row.contributionYear && row.contributionMonth
            ? `${row.contributionYear}-${String(row.contributionMonth).padStart(2, '0')}`
            : '—',
        hideOnMobile: true,
      },
      {
        id: 'outstanding',
        label: t('fines.fields.outstanding'),
        render: (row) => formatMoney(row.outstandingAmount ?? 0),
        hideOnMobile: true,
      },
      {
        id: 'type',
        label: t('fines.fields.fineType'),
        render: (row) => (
          <Chip
            size="small"
            variant="outlined"
            label={t(`fines.fineType.${row.fineType}`, {
              defaultValue: String(row.fineType),
            })}
          />
        ),
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('fines.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={fineStatusColor(String(row.status))}
            label={t(`fines.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      {
        id: 'issuedDate',
        label: t('fines.fields.issuedDate'),
        render: (row) => row.issuedDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'dueDate',
        label: t('fines.fields.dueDate'),
        render: (row) => row.dueDate || '—',
        hideOnMobile: true,
      },
    ],
    [isAll, t],
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
          select
          size="small"
          label={t('fines.fields.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {FINE_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`fines.status.${s}`)}
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
        emptyTitle={t('fines.emptyTitle')}
        emptyDescription={
          isAll ? t('fines.emptyDescription') : t('fines.myEmptyDescription')
        }
        onRowClick={(row) => navigate(ROUTES.fineDetail(row.id))}
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
