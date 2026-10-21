import {
  Box,
  Button,
  Chip,
  Grid,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { finePaymentStatusColor } from '@/features/fines'
import { getErrorMessage } from '@/shared/api/client'
import { fetchDashboardSummary } from '@/shared/api/dashboard'
import { approveFinePayment, fetchFinePaymentQueue, rejectFinePayment } from '@/shared/api/fines'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { DateRangeFields } from '@/shared/components/DateRangeFields'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { MetricCard } from '@/shared/components/MetricCard'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { EmptyState } from '@/shared/components/EmptyState'
import { FINE_PAYMENT_STATUSES, finePaymentDisplayName, type FinePayment } from '@/shared/types/fine'
import { formatMoney } from '@/shared/utils/formatMoney'
import { validateOptionalDateRange } from '@/shared/utils/filterValidation'

export function FinePaymentQueuePage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [filters, setFilters] = useState({ q: '', status: '', fromDate: '', toDate: '' })
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [reviewTarget, setReviewTarget] = useState<{
    fineId: string
    paymentId: string
    action: 'approve' | 'reject'
  } | null>(null)

  const dateIssue = validateOptionalDateRange(fromDate, toDate)
  const appliedDateIssue = validateOptionalDateRange(filters.fromDate, filters.toDate)

  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId!),
    enabled: Boolean(cooperativeId),
  })

  const queueQuery = useQuery({
    queryKey: ['fines', 'payments', 'queue', cooperativeId, filters, page, size],
    queryFn: () =>
      fetchFinePaymentQueue(cooperativeId!, {
        q: filters.q || undefined,
        status: filters.status || undefined,
        fromDate: filters.fromDate || undefined,
        toDate: filters.toDate || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId) && !appliedDateIssue,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['fines'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget || !cooperativeId) throw new Error('No payment selected')
      return reviewTarget.action === 'approve'
        ? approveFinePayment(cooperativeId, reviewTarget.fineId, reviewTarget.paymentId)
        : rejectFinePayment(cooperativeId, reviewTarget.fineId, reviewTarget.paymentId)
    },
    onSuccess: () => {
      enqueueSnackbar(
        reviewTarget?.action === 'approve'
          ? t('fines.payment.approveSuccess')
          : t('fines.payment.rejectSuccess'),
        { variant: 'success' },
      )
      setReviewTarget(null)
      invalidate()
      void queueQuery.refetch()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const applyFilters = () => {
    if (dateIssue) return
    setFilters({ q: search.trim(), status, fromDate, toDate })
    setPage(0)
  }

  const clearFilters = () => {
    setSearch('')
    setStatus('')
    setFromDate('')
    setToDate('')
    setFilters({ q: '', status: '', fromDate: '', toDate: '' })
    setPage(0)
  }

  const columns: TableColumn<FinePayment>[] = useMemo(
    () => [
      {
        id: 'member',
        label: t('fines.fields.member'),
        render: (row) => finePaymentDisplayName(row),
      },
      {
        id: 'reason',
        label: t('finePayments.fields.fineReason'),
        render: (row) => row.fineReason || '—',
        hideOnMobile: true,
      },
      {
        id: 'amount',
        label: t('fines.payment.amount'),
        render: (row) => formatMoney(row.amount, { currency: row.currency }),
      },
      {
        id: 'method',
        label: t('fines.payment.method'),
        render: (row) =>
          row.paymentMethod
            ? t(`fines.payment.methods.${row.paymentMethod}`, {
                defaultValue: String(row.paymentMethod),
              })
            : '—',
        hideOnMobile: true,
      },
      {
        id: 'reference',
        label: t('fines.fields.reference'),
        render: (row) => row.paymentReference || '—',
        hideOnMobile: true,
      },
      {
        id: 'date',
        label: t('fines.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
      },
      {
        id: 'evidence',
        label: t('fines.payment.evidence'),
        render: (row) =>
          row.evidenceFileKey ? (
            <AuthenticatedFileLink storageKey={String(row.evidenceFileKey)} variant="button">
              {t('fines.payment.viewEvidence')}
            </AuthenticatedFileLink>
          ) : (
            '—'
          ),
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('fines.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={finePaymentStatusColor(String(row.status))}
            label={t(`fines.paymentStatus.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      {
        id: 'actions',
        label: t('common.actions'),
        render: (row) => {
          if (String(row.status) !== 'PENDING') return '—'
          return (
            <Stack direction="row" spacing={1}>
              <Button
                size="small"
                variant="contained"
                onClick={(e) => {
                  e.stopPropagation()
                  setReviewTarget({ fineId: row.fineId, paymentId: row.id, action: 'approve' })
                }}
              >
                {t('fines.payment.approve')}
              </Button>
              <Button
                size="small"
                variant="outlined"
                color="error"
                onClick={(e) => {
                  e.stopPropagation()
                  setReviewTarget({ fineId: row.fineId, paymentId: row.id, action: 'reject' })
                }}
              >
                {t('fines.payment.reject')}
              </Button>
            </Stack>
          )
        },
      },
    ],
    [t],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.finePayments.title')}
          description={t('pages.finePayments.description')}
        />
        <EmptyState
          title={t('fines.selectCooperativeTitle')}
          description={t('fines.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const summary = summaryQuery.data
  const currency = summary?.currency || 'RWF'

  return (
    <Box>
      <PageHeader
        title={t('pages.finePayments.title')}
        description={t('pages.finePayments.description')}
      />

      <Grid container spacing={2} sx={{ mb: 3 }}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('finePayments.summary.pending')}
            value={summary?.pendingFinePayments != null ? String(summary.pendingFinePayments) : '—'}
            accent="gold"
            loading={summaryQuery.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('finePayments.summary.approved')}
            value={
              summary?.approvedFinePayments != null ? String(summary.approvedFinePayments) : '—'
            }
            accent="green"
            loading={summaryQuery.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('finePayments.summary.rejected')}
            value={
              summary?.rejectedFinePayments != null ? String(summary.rejectedFinePayments) : '—'
            }
            accent="red"
            loading={summaryQuery.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('finePayments.summary.approvedIncome')}
            value={
              summary?.approvedFineIncome != null
                ? formatMoney(summary.approvedFineIncome, { currency })
                : '—'
            }
            accent="gold"
            loading={summaryQuery.isLoading}
          />
        </Grid>
      </Grid>

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        useFlexGap
        sx={{ mb: 2.5, flexWrap: 'wrap', alignItems: { sm: 'center' } }}
      >
        <TextField
          size="small"
          label={t('common.search')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          sx={{ minWidth: { sm: 200 } }}
        />
        <TextField
          select
          size="small"
          label={t('fines.fields.status')}
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {FINE_PAYMENT_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`fines.paymentStatus.${s}`)}
            </MenuItem>
          ))}
        </TextField>
        <DateRangeFields
          from={fromDate}
          to={toDate}
          onFromChange={setFromDate}
          onToChange={setToDate}
          fromLabel={t('common.fromDate')}
          toLabel={t('common.toDate')}
          issue={dateIssue}
        />
        <Button variant="contained" onClick={applyFilters} disabled={Boolean(dateIssue)}>
          {t('finePayments.filters.apply')}
        </Button>
        <Button variant="text" onClick={clearFilters}>
          {t('finePayments.filters.clear')}
        </Button>
      </Stack>

      {queueQuery.isLoading ? <LoadingState variant="skeleton" rows={5} /> : null}

      {queueQuery.isError ? (
        <ErrorState
          message={getErrorMessage(queueQuery.error)}
          onRetry={() => void queueQuery.refetch()}
        />
      ) : null}

      {!appliedDateIssue && queueQuery.isSuccess ? (
        <Box>
          <ResponsiveTable
            columns={columns}
            rows={queueQuery.data.content ?? []}
            getRowId={(row) => row.id}
            emptyTitle={t('finePayments.emptyTitle')}
            emptyDescription={t('finePayments.emptyDescription')}
          />
          <TablePagination
            component="div"
            count={queueQuery.data.totalElements ?? 0}
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

      <ConfirmDialog
        open={Boolean(reviewTarget)}
        title={
          reviewTarget?.action === 'approve'
            ? t('fines.payment.confirmApproveTitle')
            : t('fines.payment.confirmRejectTitle')
        }
        message={
          reviewTarget?.action === 'approve'
            ? t('fines.payment.confirmApproveMessage')
            : t('fines.payment.confirmRejectMessage')
        }
        loading={reviewMutation.isPending}
        onCancel={() => setReviewTarget(null)}
        onConfirm={() => reviewMutation.mutate()}
      />
    </Box>
  )
}
