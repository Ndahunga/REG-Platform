import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import {
  Box,
  Button,
  Chip,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageFines } from '@/app/store/authSlice'
import {
  FinePaymentDialog,
  canApproveFinePayment,
  canCancelFine,
  canRejectFinePayment,
  canSubmitFinePayment,
  canWaiveFine,
  finePaymentStatusColor,
  fineStatusColor,
  outstandingFineAmount,
} from '@/features/fines'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveFinePayment,
  cancelFine,
  createFinePayment,
  fetchFine,
  fetchFinePayments,
  rejectFinePayment,
  waiveFine,
} from '@/shared/api/fines'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { FinePayment, FinePaymentCreateRequest } from '@/shared/types/fine'
import { fineDisplayName } from '@/shared/types/fine'
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

export function FineDetailPage() {
  const { fineId = '' } = useParams()
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectCanManageFines)

  const [confirmAction, setConfirmAction] = useState<'waive' | 'cancel' | null>(null)
  const [paymentOpen, setPaymentOpen] = useState(false)
  const [reviewTarget, setReviewTarget] = useState<{
    paymentId: string
    action: 'approve' | 'reject'
  } | null>(null)

  const fineQuery = useQuery({
    queryKey: ['fines', cooperativeId, fineId],
    queryFn: () => fetchFine(cooperativeId!, fineId),
    enabled: Boolean(cooperativeId && fineId),
  })

  const paymentsQuery = useQuery({
    queryKey: ['fines', cooperativeId, fineId, 'payments'],
    queryFn: () => fetchFinePayments(cooperativeId!, fineId),
    enabled: Boolean(cooperativeId && fineId),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['fines'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }

  const waiveMutation = useMutation({
    mutationFn: () => waiveFine(cooperativeId!, fineId),
    onSuccess: () => {
      enqueueSnackbar(t('fines.actions.waiveSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelFine(cooperativeId!, fineId),
    onSuccess: () => {
      enqueueSnackbar(t('fines.actions.cancelSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const paymentMutation = useMutation({
    mutationFn: (payload: FinePaymentCreateRequest) =>
      createFinePayment(cooperativeId!, fineId, payload),
    onSuccess: () => {
      enqueueSnackbar(t('fines.payment.success'), { variant: 'success' })
      setPaymentOpen(false)
      invalidate()
      void paymentsQuery.refetch()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget) throw new Error('No payment selected')
      return reviewTarget.action === 'approve'
        ? approveFinePayment(cooperativeId!, fineId, reviewTarget.paymentId)
        : rejectFinePayment(cooperativeId!, fineId, reviewTarget.paymentId)
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
      void paymentsQuery.refetch()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const paymentColumns: TableColumn<FinePayment>[] = useMemo(
    () => [
      {
        id: 'paymentDate',
        label: t('fines.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
      },
      {
        id: 'amount',
        label: t('fines.payment.amount'),
        render: (row) => formatMoney(row.amount),
      },
      {
        id: 'status',
        label: t('fines.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={finePaymentStatusColor(String(row.status))}
            label={t(`fines.paymentStatus.${row.status}`, {
              defaultValue: row.status,
            })}
          />
        ),
      },
      {
        id: 'reference',
        label: t('fines.fields.reference'),
        render: (row) => row.paymentReference || '—',
        hideOnMobile: true,
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
        id: 'actions',
        label: t('common.actions'),
        render: (row) => {
          const status = String(row.status)
          if (
            !canApproveFinePayment(status, isAdmin) &&
            !canRejectFinePayment(status, isAdmin)
          ) {
            return '—'
          }
          return (
            <Stack direction="row" spacing={1}>
              {canApproveFinePayment(status, isAdmin) ? (
                <Button
                  size="small"
                  variant="contained"
                  onClick={(e) => {
                    e.stopPropagation()
                    setReviewTarget({ paymentId: row.id, action: 'approve' })
                  }}
                >
                  {t('fines.payment.approve')}
                </Button>
              ) : null}
              {canRejectFinePayment(status, isAdmin) ? (
                <Button
                  size="small"
                  variant="outlined"
                  color="error"
                  onClick={(e) => {
                    e.stopPropagation()
                    setReviewTarget({ paymentId: row.id, action: 'reject' })
                  }}
                >
                  {t('fines.payment.reject')}
                </Button>
              ) : null}
            </Stack>
          )
        },
      },
    ],
    [isAdmin, t],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader title={t('pages.fines.title')} />
        <EmptyState
          title={t('fines.selectCooperativeTitle')}
          description={t('fines.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const fine = fineQuery.data
  const status = fine ? String(fine.status) : ''
  const outstanding = outstandingFineAmount(fine?.outstandingAmount)
  const actionPending =
    waiveMutation.isPending || cancelMutation.isPending || reviewMutation.isPending

  return (
    <Box>
      <Button
        component={RouterLink}
        to={ROUTES.fines}
        startIcon={<ArrowBackIcon />}
        sx={{ mb: 1 }}
      >
        {t('fines.backToList')}
      </Button>

      <PageHeader
        title={
          fine
            ? t('fines.detailTitle', { member: fineDisplayName(fine) })
            : t('pages.fines.title')
        }
        description={t('fines.detailDescription')}
        hideBack
      />

      {fineQuery.isLoading ? <LoadingState /> : null}
      {fineQuery.isError ? (
        <ErrorState
          message={getErrorMessage(fineQuery.error)}
          onRetry={() => void fineQuery.refetch()}
        />
      ) : null}

      {fine ? (
        <Stack spacing={2.5}>
          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3.5 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Stack direction="row" spacing={1} sx={{ mb: 2, flexWrap: 'wrap' }} useFlexGap>
              <Chip
                size="small"
                color={fineStatusColor(status)}
                label={t(`fines.status.${status}`, { defaultValue: status })}
              />
              <Chip
                size="small"
                variant="outlined"
                label={t(`fines.fineType.${fine.fineType}`, {
                  defaultValue: String(fine.fineType),
                })}
              />
              <Chip
                size="small"
                variant="outlined"
                label={t(`fines.calculationMode.${fine.calculationMode}`, {
                  defaultValue: String(fine.calculationMode),
                })}
              />
            </Stack>

            <Stack spacing={2}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow label={t('fines.fields.member')} value={fineDisplayName(fine)} />
                <InfoRow
                  label={t('fines.fields.totalAmount')}
                  value={formatMoney(fine.totalAmount)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('fines.fields.paidAmount')}
                  value={formatMoney(fine.paidAmount ?? 0)}
                />
                <InfoRow
                  label={t('fines.fields.outstanding')}
                  value={formatMoney(outstanding)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('fines.fields.baseAmount')}
                  value={formatMoney(fine.baseAmount)}
                />
                <InfoRow
                  label={t('fines.fields.overdueDays')}
                  value={String(fine.overdueDays ?? 0)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('fines.fields.issuedDate')}
                  value={fine.issuedDate ?? ''}
                />
                <InfoRow label={t('fines.fields.dueDate')} value={fine.dueDate ?? ''} />
              </Stack>
              <InfoRow label={t('fines.fields.reason')} value={fine.reason ?? ''} />
              <InfoRow
                label={t('fines.fields.contributionMonth')}
                value={
                  fine.contributionYear && fine.contributionMonth
                    ? `${fine.contributionYear}-${String(fine.contributionMonth).padStart(2, '0')}`
                    : '—'
                }
              />
              {fine.notes ? (
                <InfoRow label={t('fines.fields.notes')} value={fine.notes} />
              ) : null}
            </Stack>

            <Stack
              direction="row"
              spacing={1}
              useFlexGap
              sx={{ mt: 2.5, flexWrap: 'wrap' }}
            >
              {canSubmitFinePayment(status) ? (
                <Button variant="contained" onClick={() => setPaymentOpen(true)}>
                  {t('fines.payment.submitPayment')}
                </Button>
              ) : null}
              {canWaiveFine(status, isAdmin) ? (
                <Button
                  variant="outlined"
                  color="warning"
                  onClick={() => setConfirmAction('waive')}
                >
                  {t('fines.actions.waive')}
                </Button>
              ) : null}
              {canCancelFine(status, isAdmin) ? (
                <Button
                  variant="outlined"
                  color="error"
                  onClick={() => setConfirmAction('cancel')}
                >
                  {t('fines.actions.cancel')}
                </Button>
              ) : null}
            </Stack>
          </Paper>

          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Typography variant="h6" gutterBottom>
              {t('fines.payment.history')}
            </Typography>
            {paymentsQuery.isLoading ? (
              <LoadingState variant="skeleton" rows={3} />
            ) : null}
            {paymentsQuery.isError ? (
              <ErrorState
                message={getErrorMessage(paymentsQuery.error)}
                onRetry={() => void paymentsQuery.refetch()}
              />
            ) : null}
            {!paymentsQuery.isLoading && !paymentsQuery.isError ? (
              <ResponsiveTable
                columns={paymentColumns}
                rows={paymentsQuery.data ?? []}
                getRowId={(row) => row.id}
                emptyTitle={t('fines.payment.emptyTitle')}
                emptyDescription={t('fines.payment.emptyDescription')}
              />
            ) : null}
          </Paper>
        </Stack>
      ) : null}

      <ConfirmDialog
        open={confirmAction === 'waive'}
        title={t('fines.actions.confirmWaiveTitle')}
        message={t('fines.actions.confirmWaiveMessage')}
        loading={waiveMutation.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={() => waiveMutation.mutate()}
      />

      <ConfirmDialog
        open={confirmAction === 'cancel'}
        title={t('fines.actions.confirmCancelTitle')}
        message={t('fines.actions.confirmCancelMessage')}
        loading={cancelMutation.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={() => cancelMutation.mutate()}
      />

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

      <FinePaymentDialog
        open={paymentOpen}
        loading={paymentMutation.isPending || actionPending}
        outstanding={outstanding}
        maxHint={t('fines.payment.maxHint', {
          amount: formatMoney(outstanding),
        })}
        onClose={() => setPaymentOpen(false)}
        onSubmit={(payload) => paymentMutation.mutate(payload)}
      />
    </Box>
  )
}
