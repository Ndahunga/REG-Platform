import { Box, Paper, Stack, TablePagination, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { fetchMemberFinancialSummaries } from '@/shared/api/members'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import type { MemberFinancialSummary } from '@/shared/types/member'
import { formatMoney } from '@/shared/utils/formatMoney'
import { dashboardSectionTitleSx } from './dashboardTypography'

interface MemberFinancialSummarySectionProps {
  cooperativeId: string
}

/** Admin-only, searchable/paged breakdown of every member's contributions, loans, and fines. */
export function MemberFinancialSummarySection({
  cooperativeId,
}: MemberFinancialSummarySectionProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const query = useQuery({
    queryKey: ['members', 'financial-summaries', cooperativeId, debouncedSearch, page, size],
    queryFn: () =>
      fetchMemberFinancialSummaries(cooperativeId, {
        q: debouncedSearch || undefined,
        page,
        size,
      }),
    enabled: Boolean(cooperativeId),
  })

  const columns: TableColumn<MemberFinancialSummary>[] = useMemo(
    () => [
      {
        id: 'member',
        label: t('members.fields.fullName'),
        render: (row) => row.memberName || row.memberUserId || '—',
      },
      {
        id: 'actual',
        label: t('members.financialSummary.actual'),
        render: (row) => formatMoney(row.actualContributions ?? 0, { currency: row.currency }),
      },
      {
        id: 'outstandingLoan',
        label: t('members.financialSummary.outstandingPrincipal'),
        render: (row) =>
          formatMoney(
            (Number(row.outstandingLoanPrincipal) || 0) +
              (Number(row.outstandingLoanInterest) || 0),
            { currency: row.currency },
          ),
        hideOnMobile: true,
      },
      {
        id: 'unpaidFines',
        label: t('members.financialSummary.unpaidFines'),
        render: (row) => formatMoney(row.unpaidFines ?? 0, { currency: row.currency }),
      },
      {
        id: 'contributionPercentage',
        label: t('members.financialSummary.contributionPercentage'),
        render: (row) =>
          row.contributionPercentage != null && row.contributionPercentage !== ''
            ? `${Number(row.contributionPercentage).toFixed(2)}%`
            : '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  return (
    <Paper elevation={0} sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2, justifyContent: 'space-between', alignItems: { sm: 'center' } }}
      >
        <Typography variant="h6" component="h2" sx={dashboardSectionTitleSx}>
          {t('dashboard.memberSummaries.title')}
        </Typography>
        <TextField
          size="small"
          label={t('common.search')}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: { sm: 240 } }}
        />
      </Stack>

      {query.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}

      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {query.isSuccess ? (
        <Box>
          <ResponsiveTable
            columns={columns}
            rows={query.data.content ?? []}
            getRowId={(row) => row.memberUserId ?? row.memberName ?? row.membershipDate ?? ''}
            emptyTitle={t('dashboard.memberSummaries.emptyTitle')}
            emptyDescription={t('dashboard.memberSummaries.emptyDescription')}
            onRowClick={(row) =>
              row.memberUserId ? navigate(ROUTES.memberDetail(row.memberUserId)) : undefined
            }
          />
          <TablePagination
            component="div"
            count={query.data.totalElements ?? 0}
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
      ) : null}
    </Paper>
  )
}
