import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import DownloadIcon from '@mui/icons-material/Download'
import PrintIcon from '@mui/icons-material/Print'
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import {
  selectCanAuthorizeFunds,
  selectCanPreparePayouts,
} from '@/app/store/authSlice'
import {
  canCancelPayout,
  canConfirmPayout,
  canMarkPaidPayout,
  formatPayoutPercentage,
  payoutStatusColor,
  sumPayoutAmounts,
} from '@/features/payouts'
import { getErrorMessage } from '@/shared/api/client'
import {
  cancelPayout,
  confirmPayout,
  fetchPayoutRun,
  fetchPayoutStatement,
  markPayoutPaid,
} from '@/shared/api/payouts'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { PayoutLine } from '@/shared/types/payout'
import { payoutLineMemberName, payoutRunDisplayName } from '@/shared/types/payout'
import { formatMoney } from '@/shared/utils/formatMoney'

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {label}
      </Typography>
      <Typography variant="body1" sx={{ fontWeight: 500 }}>
        {value || '—'}
      </Typography>
    </Box>
  )
}

export function PayoutDetailPage() {
  const { runId = '' } = useParams()
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const canAuthorizeFunds = useAppSelector(selectCanAuthorizeFunds)
  const canPreparePayouts = useAppSelector(selectCanPreparePayouts)
  const statementRef = useRef<HTMLDivElement>(null)

  const [confirmAction, setConfirmAction] = useState<'confirm' | 'markPaid' | 'cancel' | null>(
    null,
  )

  const runQuery = useQuery({
    queryKey: ['payouts', cooperativeId, runId],
    queryFn: () => fetchPayoutRun(cooperativeId!, runId),
    enabled: Boolean(cooperativeId && runId),
  })

  const statementQuery = useQuery({
    queryKey: ['payouts', cooperativeId, runId, 'statement'],
    queryFn: () => fetchPayoutStatement(cooperativeId!, runId),
    enabled: Boolean(cooperativeId && runId && runQuery.isSuccess),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['payouts'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    void queryClient.invalidateQueries({ queryKey: ['ledger'] })
  }

  const confirmMutation = useMutation({
    mutationFn: () => confirmPayout(cooperativeId!, runId),
    onSuccess: () => {
      enqueueSnackbar(t('payouts.actions.confirmSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const markPaidMutation = useMutation({
    mutationFn: () => markPayoutPaid(cooperativeId!, runId),
    onSuccess: () => {
      enqueueSnackbar(t('payouts.actions.markPaidSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelPayout(cooperativeId!, runId),
    onSuccess: () => {
      enqueueSnackbar(t('payouts.actions.cancelSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<PayoutLine>[] = useMemo(
    () => [
      {
        id: 'member',
        label: t('payouts.fields.member'),
        render: (row) => payoutLineMemberName(row),
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
            color={payoutStatusColor(String(row.status))}
            label={t(`payouts.status.${row.status}`, { defaultValue: String(row.status) })}
          />
        ),
        hideOnMobile: true,
      },
    ],
    [t],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader title={t('pages.payouts.title')} />
        <EmptyState
          title={t('payouts.selectCooperativeTitle')}
          description={t('payouts.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (runQuery.isLoading) return <LoadingState variant="skeleton" rows={5} />
  if (runQuery.isError) {
    return (
      <ErrorState
        message={getErrorMessage(runQuery.error)}
        onRetry={() => void runQuery.refetch()}
      />
    )
  }

  const run = runQuery.data
  if (!run) {
    return (
      <EmptyState
        title={t('payouts.notFoundTitle')}
        description={t('payouts.notFoundDescription')}
      />
    )
  }

  const currency = run.currency || 'RWF'
  const status = String(run.status)
  const lines = run.lines ?? []
  const linesTotal = sumPayoutAmounts(lines)
  const statement = statementQuery.data

  const downloadStatementJson = () => {
    const payload = statement ?? {
      payoutRunId: run.id,
      name: payoutRunDisplayName(run),
      periodFrom: run.periodFrom,
      periodTo: run.periodTo,
      currency,
      payoutPoolAmount: run.payoutPoolAmount,
      totalEligibleContributions: run.totalEligibleContributions,
      totalPayoutAmount: linesTotal,
      status: run.status,
      includeRegular: run.includeRegular,
      includeSpecial: run.includeSpecial,
      lines,
      generatedAt: new Date().toISOString(),
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], {
      type: 'application/json',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `payout-statement-${run.id}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  const printStatement = () => {
    window.print()
  }

  const actionLoading =
    confirmMutation.isPending || markPaidMutation.isPending || cancelMutation.isPending

  return (
    <Box>
      <Button
        component={RouterLink}
        to={ROUTES.payouts}
        startIcon={<ArrowBackIcon />}
        sx={{ mb: 1.5 }}
        className="no-print"
      >
        {t('payouts.backToList')}
      </Button>

      <PageHeader
        title={payoutRunDisplayName(run)}
        description={t('payouts.detailDescription')}
        hideBack
        actions={
          <Chip
            color={payoutStatusColor(status)}
            label={t(`payouts.status.${status}`, { defaultValue: status })}
          />
        }
      />

      <Paper
        elevation={0}
        className="no-print"
        sx={{
          p: { xs: 2, sm: 2.5 },
          mb: 2.5,
          border: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          useFlexGap
          sx={{ flexWrap: 'wrap', mb: 2 }}
        >
          <InfoRow
            label={t('payouts.fields.period')}
            value={`${run.periodFrom || '—'} → ${run.periodTo || '—'}`}
          />
          <InfoRow
            label={t('payouts.fields.payoutPoolAmount')}
            value={formatMoney(run.payoutPoolAmount, { currency })}
          />
          <InfoRow
            label={t('payouts.fields.totalEligible')}
            value={formatMoney(run.totalEligibleContributions ?? 0, { currency })}
          />
          <InfoRow
            label={t('payouts.fields.availableFundSnapshot')}
            value={
              run.availableFundSnapshot != null
                ? formatMoney(run.availableFundSnapshot, { currency })
                : '—'
            }
          />
          <InfoRow
            label={t('payouts.fields.includeRegular')}
            value={run.includeRegular ? t('common.yes') : t('common.no')}
          />
          <InfoRow
            label={t('payouts.fields.includeSpecial')}
            value={run.includeSpecial ? t('common.yes') : t('common.no')}
          />
          <InfoRow
            label={t('payouts.fields.confirmedAt')}
            value={run.confirmedAt || '—'}
          />
          <InfoRow label={t('payouts.fields.paidAt')} value={run.paidAt || '—'} />
        </Stack>

        {run.notes ? (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {run.notes}
          </Typography>
        ) : null}

        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          {canConfirmPayout(status, canAuthorizeFunds) ? (
            <Button variant="contained" onClick={() => setConfirmAction('confirm')}>
              {t('payouts.actions.confirm')}
            </Button>
          ) : null}
          {canMarkPaidPayout(status, canPreparePayouts) ? (
            <Button variant="contained" onClick={() => setConfirmAction('markPaid')}>
              {t('payouts.actions.markPaid')}
            </Button>
          ) : null}
          {canCancelPayout(status, canPreparePayouts) ? (
            <Button
              color="warning"
              variant="outlined"
              onClick={() => setConfirmAction('cancel')}
            >
              {t('payouts.actions.cancel')}
            </Button>
          ) : null}
          <Button
            variant="outlined"
            startIcon={<DownloadIcon />}
            onClick={downloadStatementJson}
          >
            {t('payouts.statement.downloadJson')}
          </Button>
          <Button variant="outlined" startIcon={<PrintIcon />} onClick={printStatement}>
            {t('payouts.statement.print')}
          </Button>
        </Stack>
      </Paper>

      <Typography variant="h6" sx={{ mb: 1.5 }} className="no-print">
        {t('payouts.linesTitle')}
      </Typography>
      <Box className="no-print" sx={{ mb: 3 }}>
        <ResponsiveTable
          columns={columns}
          rows={lines}
          getRowId={(row) => row.id}
          emptyTitle={t('payouts.preview.emptyLinesTitle')}
          emptyDescription={t('payouts.preview.emptyLinesDescription')}
        />
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {t('payouts.linesTotal', {
            amount: formatMoney(linesTotal, { currency }),
          })}
        </Typography>
      </Box>

      <Paper
        ref={statementRef}
        elevation={0}
        sx={{
          p: { xs: 2, sm: 3 },
          border: '1px solid',
          borderColor: 'divider',
          '@media print': {
            border: 'none',
            boxShadow: 'none',
          },
        }}
      >
        <Typography variant="h5" sx={{ mb: 1 }}>
          {t('payouts.statement.title')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {statement?.cooperativeName || t('payouts.statement.cooperativeFallback')}
          {statement?.generatedAt
            ? ` · ${t('payouts.statement.generatedAt', { date: statement.generatedAt })}`
            : ''}
        </Typography>

        {statementQuery.isError ? (
          <Alert severity="warning" sx={{ mb: 2 }} className="no-print">
            {getErrorMessage(statementQuery.error, t('payouts.statement.loadError'))}
          </Alert>
        ) : null}

        <Stack spacing={1} sx={{ mb: 2 }}>
          <Typography>
            <strong>{t('payouts.fields.name')}:</strong> {payoutRunDisplayName(run)}
          </Typography>
          <Typography>
            <strong>{t('payouts.fields.period')}:</strong>{' '}
            {run.periodFrom} → {run.periodTo}
          </Typography>
          <Typography>
            <strong>{t('payouts.fields.payoutPoolAmount')}:</strong>{' '}
            {formatMoney(run.payoutPoolAmount, { currency })}
          </Typography>
          <Typography>
            <strong>{t('payouts.fields.status')}:</strong>{' '}
            {t(`payouts.status.${status}`, { defaultValue: status })}
          </Typography>
        </Stack>

        <Divider sx={{ mb: 2 }} />

        {(statement?.lines ?? lines).map((line) => (
          <Stack
            key={line.id}
            direction={{ xs: 'column', sm: 'row' }}
            spacing={1}
            sx={{
              py: 1,
              borderBottom: '1px solid',
              borderColor: 'divider',
              justifyContent: 'space-between',
            }}
          >
            <Typography>{payoutLineMemberName(line)}</Typography>
            <Typography>
              {formatPayoutPercentage(line.percentage)} ·{' '}
              {formatMoney(line.payoutAmount, { currency: line.currency || currency })}
            </Typography>
          </Stack>
        ))}

        <Typography sx={{ mt: 2, fontWeight: 700 }}>
          {t('payouts.linesTotal', {
            amount: formatMoney(
              statement?.totalPayoutAmount ?? linesTotal,
              { currency },
            ),
          })}
        </Typography>
      </Paper>

      <ConfirmDialog
        open={confirmAction === 'confirm'}
        title={t('payouts.actions.confirmTitle')}
        message={t('payouts.actions.confirmMessage', {
          amount: formatMoney(run.payoutPoolAmount, { currency }),
        })}
        loading={actionLoading}
        onConfirm={() => confirmMutation.mutate()}
        onCancel={() => setConfirmAction(null)}
      />

      <ConfirmDialog
        open={confirmAction === 'markPaid'}
        title={t('payouts.actions.markPaidTitle')}
        message={t('payouts.actions.markPaidMessage')}
        loading={actionLoading}
        onConfirm={() => markPaidMutation.mutate()}
        onCancel={() => setConfirmAction(null)}
      />

      <ConfirmDialog
        open={confirmAction === 'cancel'}
        title={t('payouts.actions.cancelTitle')}
        message={t('payouts.actions.cancelMessage')}
        loading={actionLoading}
        onConfirm={() => cancelMutation.mutate()}
        onCancel={() => setConfirmAction(null)}
      />
    </Box>
  )
}
