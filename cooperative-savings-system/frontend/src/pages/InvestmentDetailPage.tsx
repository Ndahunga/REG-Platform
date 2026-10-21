import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import {
  selectCanAuthorizeFunds,
  selectIsCooperativeAdmin,
} from '@/app/store/authSlice'
import {
  canActivateInvestment,
  canCancelInvestment,
  canRecordInvestmentLoss,
  canRecordInvestmentReturn,
  investmentStatusColor,
} from '@/features/investments'
import {
  investmentLossDefaults,
  investmentLossSchema,
  investmentReturnDefaults,
  investmentReturnSchema,
  toInvestmentLossPayload,
  toInvestmentReturnPayload,
  type InvestmentLossFormValues,
  type InvestmentReturnFormValues,
} from '@/features/investments/investmentFormSchemas'
import { getErrorMessage } from '@/shared/api/client'
import { fetchDashboardSummary } from '@/shared/api/dashboard'
import {
  activateInvestment,
  cancelInvestment,
  fetchInvestment,
  fetchInvestmentReturns,
  recordInvestmentLoss,
  recordInvestmentReturn,
} from '@/shared/api/investments'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { InvestmentReturn } from '@/shared/types/investment'
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

export function InvestmentDetailPage() {
  const { investmentId = '' } = useParams()
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectIsCooperativeAdmin)
  const canAuthorizeFunds = useAppSelector(selectCanAuthorizeFunds)

  const [confirmAction, setConfirmAction] = useState<'activate' | 'cancel' | null>(null)
  const [returnOpen, setReturnOpen] = useState(false)
  const [lossOpen, setLossOpen] = useState(false)

  const investmentQuery = useQuery({
    queryKey: ['investments', cooperativeId, investmentId],
    queryFn: () => fetchInvestment(cooperativeId!, investmentId),
    enabled: Boolean(cooperativeId && investmentId),
  })

  const returnsQuery = useQuery({
    queryKey: ['investments', cooperativeId, investmentId, 'returns'],
    queryFn: () => fetchInvestmentReturns(cooperativeId!, investmentId),
    enabled: Boolean(cooperativeId && investmentId),
  })

  const fundsQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId!),
    enabled: Boolean(cooperativeId && isAdmin),
  })

  const returnForm = useForm<InvestmentReturnFormValues>({
    defaultValues: investmentReturnDefaults(),
    resolver: yupResolver(investmentReturnSchema),
  })

  const lossForm = useForm<InvestmentLossFormValues>({
    defaultValues: investmentLossDefaults,
    resolver: yupResolver(investmentLossSchema),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['investments'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    void queryClient.invalidateQueries({ queryKey: ['ledger'] })
  }

  const activateMutation = useMutation({
    mutationFn: () => activateInvestment(cooperativeId!, investmentId),
    onSuccess: () => {
      enqueueSnackbar(t('investments.actions.activateSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelInvestment(cooperativeId!, investmentId),
    onSuccess: () => {
      enqueueSnackbar(t('investments.actions.cancelSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const returnMutation = useMutation({
    mutationFn: (values: InvestmentReturnFormValues) =>
      recordInvestmentReturn(
        cooperativeId!,
        investmentId,
        toInvestmentReturnPayload(values),
      ),
    onSuccess: () => {
      enqueueSnackbar(t('investments.actions.returnSuccess'), { variant: 'success' })
      setReturnOpen(false)
      returnForm.reset(investmentReturnDefaults())
      invalidate()
      void returnsQuery.refetch()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const lossMutation = useMutation({
    mutationFn: (values: InvestmentLossFormValues) =>
      recordInvestmentLoss(
        cooperativeId!,
        investmentId,
        toInvestmentLossPayload(values),
      ),
    onSuccess: () => {
      enqueueSnackbar(t('investments.actions.lossSuccess'), { variant: 'success' })
      setLossOpen(false)
      lossForm.reset(investmentLossDefaults)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const returnColumns: TableColumn<InvestmentReturn>[] = useMemo(
    () => [
      {
        id: 'returnDate',
        label: t('investments.fields.returnDate'),
        render: (row) => row.returnDate || '—',
      },
      {
        id: 'capital',
        label: t('investments.fields.capitalPortion'),
        render: (row) => formatMoney(row.capitalPortion, { currency: row.currency }),
      },
      {
        id: 'profit',
        label: t('investments.fields.profitPortion'),
        render: (row) => formatMoney(row.profitPortion, { currency: row.currency }),
      },
      {
        id: 'total',
        label: t('investments.fields.amountTotal'),
        render: (row) => formatMoney(row.amountTotal, { currency: row.currency }),
      },
      {
        id: 'reference',
        label: t('investments.fields.reference'),
        render: (row) => row.reference || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader title={t('pages.investments.title')} />
        <EmptyState
          title={t('investments.selectCooperativeTitle')}
          description={t('investments.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (investmentQuery.isLoading) return <LoadingState variant="skeleton" rows={5} />
  if (investmentQuery.isError) {
    return (
      <ErrorState
        message={getErrorMessage(investmentQuery.error)}
        onRetry={() => void investmentQuery.refetch()}
      />
    )
  }

  const investment = investmentQuery.data
  if (!investment) {
    return (
      <EmptyState
        title={t('investments.notFoundTitle')}
        description={t('investments.notFoundDescription')}
      />
    )
  }

  const currency = investment.currency || 'RWF'
  const status = String(investment.status)
  const availableFunds = fundsQuery.data?.availableGroupFunds
  const amount = Number(investment.amount) || 0
  const fundInsufficient =
    availableFunds != null && amount > (Number(availableFunds) || 0)

  return (
    <Box>
      <Button
        component={RouterLink}
        to={ROUTES.investments}
        startIcon={<ArrowBackIcon />}
        sx={{ mb: 1.5 }}
      >
        {t('investments.backToList')}
      </Button>

      <PageHeader
        title={investment.name}
        description={t('investments.detailDescription')}
        hideBack
        actions={
          <Chip
            color={investmentStatusColor(status)}
            label={t(`investments.status.${status}`, { defaultValue: status })}
          />
        }
      />

      {canActivateInvestment(status, canAuthorizeFunds) && fundInsufficient ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t('investments.activateFundWarning', {
            amount: formatMoney(investment.amount, { currency }),
            available: formatMoney(availableFunds ?? 0, { currency }),
          })}
        </Alert>
      ) : null}

      {canActivateInvestment(status, canAuthorizeFunds) && !fundInsufficient && availableFunds != null ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          {t('investments.activateFundInfo', {
            available: formatMoney(availableFunds, { currency }),
          })}
        </Alert>
      ) : null}

      <Paper
        elevation={0}
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
            label={t('investments.fields.amount')}
            value={formatMoney(investment.amount, { currency })}
          />
          <InfoRow
            label={t('investments.fields.remainingCapital')}
            value={formatMoney(investment.remainingCapital ?? 0, { currency })}
          />
          <InfoRow
            label={t('investments.fields.totalCapitalReturned')}
            value={formatMoney(investment.totalCapitalReturned ?? 0, { currency })}
          />
          <InfoRow
            label={t('investments.fields.totalProfitReturned')}
            value={formatMoney(investment.totalProfitReturned ?? 0, { currency })}
          />
          <InfoRow
            label={t('investments.fields.expectedReturnAmount')}
            value={
              investment.expectedReturnAmount != null
                ? formatMoney(investment.expectedReturnAmount, { currency })
                : '—'
            }
          />
          <InfoRow
            label={t('investments.fields.expectedReturnDate')}
            value={investment.expectedReturnDate || '—'}
          />
          <InfoRow
            label={t('investments.fields.activatedAt')}
            value={investment.activatedAt || '—'}
          />
        </Stack>

        {investment.description ? (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {investment.description}
          </Typography>
        ) : null}

        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          {canActivateInvestment(status, canAuthorizeFunds) ? (
            <Button variant="contained" onClick={() => setConfirmAction('activate')}>
              {t('investments.actions.activate')}
            </Button>
          ) : null}
          {canRecordInvestmentReturn(status, canAuthorizeFunds) ? (
            <Button variant="contained" onClick={() => setReturnOpen(true)}>
              {t('investments.actions.recordReturn')}
            </Button>
          ) : null}
          {canRecordInvestmentLoss(status, canAuthorizeFunds) ? (
            <Button color="error" variant="outlined" onClick={() => setLossOpen(true)}>
              {t('investments.actions.recordLoss')}
            </Button>
          ) : null}
          {canCancelInvestment(status, isAdmin) ? (
            <Button color="warning" variant="outlined" onClick={() => setConfirmAction('cancel')}>
              {t('investments.actions.cancel')}
            </Button>
          ) : null}
        </Stack>
      </Paper>

      <Typography variant="h6" sx={{ mb: 1.5 }}>
        {t('investments.returnsTitle')}
      </Typography>
      {returnsQuery.isLoading ? (
        <LoadingState variant="skeleton" rows={2} />
      ) : returnsQuery.isError ? (
        <ErrorState
          message={getErrorMessage(returnsQuery.error)}
          onRetry={() => void returnsQuery.refetch()}
        />
      ) : (
        <ResponsiveTable
          columns={returnColumns}
          rows={returnsQuery.data ?? []}
          getRowId={(row) => row.id}
          emptyTitle={t('investments.returnsEmptyTitle')}
          emptyDescription={t('investments.returnsEmptyDescription')}
        />
      )}

      <ConfirmDialog
        open={confirmAction === 'activate'}
        title={t('investments.actions.confirmActivateTitle')}
        message={
          fundInsufficient
            ? t('investments.actions.confirmActivateInsufficientMessage', {
                amount: formatMoney(investment.amount, { currency }),
                available: formatMoney(availableFunds ?? 0, { currency }),
              })
            : t('investments.actions.confirmActivateMessage', {
                amount: formatMoney(investment.amount, { currency }),
              })
        }
        loading={activateMutation.isPending}
        onConfirm={() => activateMutation.mutate()}
        onCancel={() => setConfirmAction(null)}
      />

      <ConfirmDialog
        open={confirmAction === 'cancel'}
        title={t('investments.actions.confirmCancelTitle')}
        message={t('investments.actions.confirmCancelMessage')}
        loading={cancelMutation.isPending}
        onConfirm={() => cancelMutation.mutate()}
        onCancel={() => setConfirmAction(null)}
      />

      <Dialog
        open={returnOpen}
        onClose={() => setReturnOpen(false)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>{t('investments.returnDialog.title')}</DialogTitle>
        <DialogContent>
          <Stack
            component="form"
            noValidate
            id="investment-return-form"
            spacing={2}
            sx={{ mt: 1 }}
            onSubmit={returnForm.handleSubmit((values) => returnMutation.mutate(values))}
          >
            <TextField
              label={t('investments.fields.returnDate')}
              type="date"
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
              {...returnForm.register('returnDate')}
              error={Boolean(returnForm.formState.errors.returnDate)}
              helperText={returnForm.formState.errors.returnDate?.message}
              fullWidth
            />
            <TextField
              label={t('investments.fields.capitalPortion')}
              {...returnForm.register('capitalPortion')}
              error={Boolean(returnForm.formState.errors.capitalPortion)}
              helperText={
                returnForm.formState.errors.capitalPortion?.message ||
                t('investments.returnDialog.remainingHint', {
                  amount: formatMoney(investment.remainingCapital ?? 0, { currency }),
                })
              }
              fullWidth
            />
            <TextField
              label={t('investments.fields.profitPortion')}
              {...returnForm.register('profitPortion')}
              error={Boolean(returnForm.formState.errors.profitPortion)}
              helperText={returnForm.formState.errors.profitPortion?.message}
              fullWidth
            />
            {returnForm.formState.errors.root ? (
              <Alert severity="error">{returnForm.formState.errors.root.message}</Alert>
            ) : null}
            <TextField
              label={t('investments.fields.reference')}
              error={Boolean(returnForm.formState.errors.reference)}
              helperText={returnForm.formState.errors.reference?.message}
              {...returnForm.register('reference')}
              fullWidth
            />
            <TextField
              label={t('investments.fields.notes')}
              error={Boolean(returnForm.formState.errors.notes)}
              helperText={returnForm.formState.errors.notes?.message}
              {...returnForm.register('notes')}
              fullWidth
              multiline
              minRows={2}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setReturnOpen(false)} disabled={returnMutation.isPending}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            form="investment-return-form"
            variant="contained"
            disabled={returnMutation.isPending}
          >
            {t('investments.returnDialog.submit')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={lossOpen} onClose={() => setLossOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{t('investments.lossDialog.title')}</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mt: 1, mb: 2 }}>
            {t('investments.lossDialog.warning', {
              amount: formatMoney(investment.remainingCapital ?? 0, { currency }),
            })}
          </Alert>
          <Stack
            component="form"
            noValidate
            id="investment-loss-form"
            spacing={2}
            onSubmit={lossForm.handleSubmit((values) => lossMutation.mutate(values))}
          >
            <TextField
              label={t('investments.fields.reference')}
              error={Boolean(lossForm.formState.errors.reference)}
              helperText={lossForm.formState.errors.reference?.message}
              {...lossForm.register('reference')}
              fullWidth
            />
            <TextField
              label={t('investments.fields.notes')}
              error={Boolean(lossForm.formState.errors.notes)}
              helperText={lossForm.formState.errors.notes?.message}
              {...lossForm.register('notes')}
              fullWidth
              multiline
              minRows={2}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setLossOpen(false)} disabled={lossMutation.isPending}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            form="investment-loss-form"
            color="error"
            variant="contained"
            disabled={lossMutation.isPending}
          >
            {t('investments.lossDialog.submit')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
