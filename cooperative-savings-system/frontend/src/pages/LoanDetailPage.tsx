import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import {
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
  Tooltip,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { RouteErrorBoundary } from '@/shared/components/RouteErrorBoundary'
import { useAppSelector } from '@/app/store/hooks'
import {
  selectAuthUser,
  selectCanApproveLoans,
  selectCanAuthorizeFunds,
  selectCanRecordLoans,
} from '@/app/store/authSlice'
import {
  LoanApplicationFormView,
  LoanSchedulePreview,
  RepaymentDialog,
  canShowApprove,
  canShowDisburse,
  canShowReject,
  canShowRepayment,
  canShowWriteOff,
  loanStatusColor,
} from '@/features/loans'
import {
  loanApproveDefaults,
  loanApproveSchema,
  loanRejectDefaults,
  loanRejectSchema,
  toLoanApprovePayload,
  type LoanApproveFormValues,
  type LoanRejectFormValues,
} from '@/features/loans/loanFormSchemas'
import { isValidReportWhatsAppRecipient } from '@/features/reports/reportHelpers'
import {
  getErrorMessage,
  isForbiddenError,
  isNotFoundError,
  isUnauthorizedError,
} from '@/shared/api/client'
import {
  approveLoan,
  createLoanRepayment,
  disburseLoan,
  exportLoanSchedule,
  fetchLoan,
  fetchLoanRepayments,
  fetchLoanScheduleWhatsAppStatus,
  rejectLoan,
  shareLoanScheduleViaWhatsApp,
  writeOffLoan,
} from '@/shared/api/loans'
import { ApprovalHistory } from '@/shared/components/ApprovalHistory'
import { WhatsAppShareDialog } from '@/shared/components/WhatsAppShareDialog'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { LoanRepayment, LoanRepaymentCreateRequest } from '@/shared/types/loan'
import { loanDisplayName } from '@/shared/types/loan'
import { formatMoney, formatOptionalMoney } from '@/shared/utils/formatMoney'

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

export function LoanDetailPage() {
  const { loanId = '' } = useParams()
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const canApproveLoans = useAppSelector(selectCanApproveLoans)
  const canRecordLoans = useAppSelector(selectCanRecordLoans)
  const canAuthorizeFunds = useAppSelector(selectCanAuthorizeFunds)
  const currentUserId = useAppSelector(selectAuthUser)?.id
  const { canWrite, readOnly } = useCooperativeSubscription(cooperativeId)
  const shareBlocked = readOnly || !canWrite

  const [confirmAction, setConfirmAction] = useState<
    'approve' | 'disburse' | 'writeOff' | null
  >(null)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [approveOpen, setApproveOpen] = useState(false)
  const [repayOpen, setRepayOpen] = useState(false)
  const [shareOpen, setShareOpen] = useState(false)
  const [recipientPhone, setRecipientPhone] = useState('')

  const loanQuery = useQuery({
    queryKey: ['loans', cooperativeId, loanId],
    queryFn: async () => {
      const result = await fetchLoan(cooperativeId!, loanId)
      if (result == null) {
        throw new Error('LOAN_UNAVAILABLE')
      }
      return result
    },
    enabled: Boolean(cooperativeId && loanId),
  })

  const repaymentsQuery = useQuery({
    queryKey: ['loans', cooperativeId, loanId, 'repayments'],
    queryFn: () => fetchLoanRepayments(cooperativeId!, loanId),
    enabled: Boolean(cooperativeId && loanId),
  })

  const whatsappStatusQuery = useQuery({
    queryKey: ['loans', cooperativeId, loanId, 'whatsapp-status'],
    queryFn: async () => {
      try {
        return await fetchLoanScheduleWhatsAppStatus(cooperativeId!, loanId)
      } catch (error) {
        if (isUnauthorizedError(error)) {
          throw error
        }
        return { configured: false }
      }
    },
    enabled: Boolean(cooperativeId && loanId),
    staleTime: 60_000,
    retry: false,
  })
  const whatsappConfigured = whatsappStatusQuery.data?.configured === true

  const approveForm = useForm<LoanApproveFormValues>({
    defaultValues: loanApproveDefaults,
    resolver: yupResolver(loanApproveSchema),
  })

  const rejectForm = useForm<LoanRejectFormValues>({
    defaultValues: loanRejectDefaults,
    resolver: yupResolver(loanRejectSchema),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['loans'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }

  const approveMutation = useMutation({
    mutationFn: (values: LoanApproveFormValues) =>
      approveLoan(cooperativeId!, loanId, toLoanApprovePayload(values)),
    onSuccess: () => {
      enqueueSnackbar(t('loans.actions.approveSuccess'), { variant: 'success' })
      setApproveOpen(false)
      setConfirmAction(null)
      approveForm.reset(loanApproveDefaults)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const rejectMutation = useMutation({
    mutationFn: (values: LoanRejectFormValues) =>
      rejectLoan(cooperativeId!, loanId, {
        rejectionReason: values.rejectionReason.trim(),
      }),
    onSuccess: () => {
      enqueueSnackbar(t('loans.actions.rejectSuccess'), { variant: 'success' })
      setRejectOpen(false)
      rejectForm.reset(loanRejectDefaults)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const disburseMutation = useMutation({
    mutationFn: () => disburseLoan(cooperativeId!, loanId),
    onSuccess: () => {
      enqueueSnackbar(t('loans.actions.disburseSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const writeOffMutation = useMutation({
    mutationFn: () => writeOffLoan(cooperativeId!, loanId),
    onSuccess: () => {
      enqueueSnackbar(t('loans.actions.writeOffSuccess'), { variant: 'success' })
      setConfirmAction(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const repayMutation = useMutation({
    mutationFn: (payload: LoanRepaymentCreateRequest) =>
      createLoanRepayment(cooperativeId!, loanId, payload),
    onSuccess: () => {
      enqueueSnackbar(t('loans.repayment.success'), { variant: 'success' })
      setRepayOpen(false)
      invalidate()
      void repaymentsQuery.refetch()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const repaymentColumns: TableColumn<LoanRepayment>[] = useMemo(
    () => [
      {
        id: 'paymentDate',
        label: t('loans.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
      },
      {
        id: 'total',
        label: t('loans.repayment.amount'),
        render: (row) => formatOptionalMoney(row.amountTotal),
      },
      {
        id: 'principal',
        label: t('loans.repayment.principal'),
        render: (row) => formatOptionalMoney(row.principalPortion),
        hideOnMobile: true,
      },
      {
        id: 'interest',
        label: t('loans.repayment.interest'),
        render: (row) => formatOptionalMoney(row.interestPortion),
        hideOnMobile: true,
      },
      {
        id: 'penalty',
        label: t('loans.repayment.penalty'),
        render: (row) => formatOptionalMoney(row.penaltyPortion),
        hideOnMobile: true,
      },
      {
        id: 'reference',
        label: t('loans.fields.reference'),
        render: (row) => row.paymentReference || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  const downloadMutation = useMutation({
    mutationFn: () => {
      if (!cooperativeId) {
        throw new Error(t('loans.selectCooperativeTitle'))
      }
      return exportLoanSchedule(cooperativeId, loanId, 'pdf')
    },
    onSuccess: () => {
      enqueueSnackbar(t('loans.schedule.downloadSuccess'), { variant: 'success' })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const shareMutation = useMutation({
    mutationFn: (phone: string) => {
      if (!cooperativeId) {
        throw new Error(t('loans.selectCooperativeTitle'))
      }
      if (!isValidReportWhatsAppRecipient(phone)) {
        throw new Error(t('reports.whatsapp.phoneInvalid'))
      }
      return shareLoanScheduleViaWhatsApp(cooperativeId, loanId, phone.trim())
    },
    onSuccess: ({ filename }) => {
      enqueueSnackbar(t('loans.schedule.whatsappSuccess', { filename }), { variant: 'success' })
      setShareOpen(false)
      setRecipientPhone('')
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('loans.schedule.whatsappFailed')), {
        variant: 'error',
      })
    },
  })

  const openShare = () => {
    if (shareBlocked) {
      enqueueSnackbar(t('subscription.readOnlyShare'), { variant: 'warning' })
      return
    }
    if (!whatsappConfigured) {
      enqueueSnackbar(t('reports.whatsapp.notConfigured'), { variant: 'warning' })
      return
    }
    setShareOpen(true)
  }

  const loan = loanQuery.data
  const status = loan ? String(loan.status) : ''
  const canActOnApproval =
    Boolean(loan) &&
    ((status === 'PENDING' && canApproveLoans) ||
      (status === 'AWAITING_SECOND_APPROVAL' &&
        canAuthorizeFunds &&
        currentUserId !== loan?.firstApprovedBy))
  const actionPending =
    approveMutation.isPending ||
    rejectMutation.isPending ||
    disburseMutation.isPending ||
    writeOffMutation.isPending

  const outstandingPrincipal = Number(loan?.outstandingPrincipal) || 0
  const outstandingInterest = Number(loan?.outstandingInterest) || 0
  const outstandingPenalty = Number(loan?.outstandingPenalty) || 0
  const outstandingSum = outstandingPrincipal + outstandingInterest + outstandingPenalty
  const canDownloadSchedule =
    Boolean(loan?.scheduleFinalized) || (loan?.repaymentSchedule?.length ?? 0) > 0

  const backLink = (
    <Button
      component={RouterLink}
      to={ROUTES.loans}
      startIcon={<ArrowBackIcon />}
      sx={{ mb: 1 }}
    >
      {t('loans.backToList')}
    </Button>
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader title={t('pages.loans.title')} />
        <EmptyState
          title={t('loans.selectCooperativeTitle')}
          description={t('loans.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (loanQuery.isLoading) {
    return (
      <Box>
        {backLink}
        <PageHeader title={t('pages.loans.title')} description={t('loans.detailDescription')} hideBack />
        <LoadingState />
      </Box>
    )
  }

  if (loanQuery.isError) {
    const missingData =
      loanQuery.error instanceof Error && loanQuery.error.message === 'LOAN_UNAVAILABLE'
    const title = isForbiddenError(loanQuery.error)
      ? t('loans.accessDeniedTitle')
      : isNotFoundError(loanQuery.error)
        ? t('loans.notFoundTitle')
        : missingData
          ? t('loans.unavailableTitle')
          : t('common.errorTitle')
    const message = isForbiddenError(loanQuery.error)
      ? t('loans.accessDeniedDescription')
      : isNotFoundError(loanQuery.error)
        ? t('loans.notFoundDescription')
        : missingData
          ? t('loans.unavailableDescription')
          : getErrorMessage(loanQuery.error, t('errors.generic'))
    return (
      <Box>
        {backLink}
        <PageHeader title={t('pages.loans.title')} description={t('loans.detailDescription')} hideBack />
        <ErrorState title={title} message={message} onRetry={() => void loanQuery.refetch()} />
      </Box>
    )
  }

  if (!loan) {
    return (
      <Box>
        {backLink}
        <PageHeader title={t('pages.loans.title')} description={t('loans.detailDescription')} hideBack />
        <ErrorState
          title={t('loans.unavailableTitle')}
          message={t('loans.unavailableDescription')}
          onRetry={() => void loanQuery.refetch()}
        />
      </Box>
    )
  }

  return (
    <Box>
      {backLink}

      <PageHeader
        title={t('loans.detailTitle', { member: loanDisplayName(loan) })}
        description={t('loans.detailDescription')}
        hideBack
      />

      <Stack spacing={2.5}>
          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3.5 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Stack direction="row" spacing={1} sx={{ mb: 2, flexWrap: 'wrap' }} useFlexGap>
              <Chip
                size="small"
                color={loanStatusColor(status)}
                label={t(`loans.status.${status}`, { defaultValue: status })}
              />
              <Chip
                size="small"
                variant="outlined"
                label={t(`loans.interestType.${loan.interestType}`, {
                  defaultValue: String(loan.interestType),
                })}
              />
            </Stack>

            <Stack spacing={2}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow label={t('loans.fields.member')} value={loanDisplayName(loan)} />
                <InfoRow
                  label={t('loans.fields.requestedAmount')}
                  value={formatOptionalMoney(loan.requestedAmount)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.approvedAmount')}
                  value={formatOptionalMoney(loan.approvedAmount)}
                />
                <InfoRow
                  label={t('loans.fields.principal')}
                  value={formatOptionalMoney(loan.principalAmount)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.interestAmount')}
                  value={formatOptionalMoney(loan.interestAmount)}
                />
                <InfoRow
                  label={t('loans.fields.interestRate')}
                  value={
                    loan.interestRatePercent != null && loan.interestRatePercent !== ''
                      ? `${loan.interestRatePercent}%`
                      : '—'
                  }
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.outstandingPrincipal')}
                  value={formatMoney(outstandingPrincipal)}
                />
                <InfoRow
                  label={t('loans.fields.outstandingInterest')}
                  value={formatMoney(outstandingInterest)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.outstandingPenalty')}
                  value={formatMoney(outstandingPenalty)}
                />
                <InfoRow
                  label={t('loans.fields.repaymentDateModel')}
                  value={
                    loan.repaymentDateModel
                      ? t(`loans.schedule.repaymentModel.${loan.repaymentDateModel}`, {
                          defaultValue: String(loan.repaymentDateModel),
                        })
                      : '—'
                  }
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.request.guaranteeMode')}
                  value={t(
                    loan.guaranteeMode === 'GUARANTOR'
                      ? 'loans.request.guaranteeModeGuarantor'
                      : 'loans.request.guaranteeModeSelf',
                  )}
                />
                <InfoRow
                  label={t('loans.eligibility.shares')}
                  value={
                    loan.shareCount != null
                      ? loan.sharePercent != null
                        ? `${loan.shareCount} (${loan.sharePercent}%)`
                        : String(loan.shareCount)
                      : '—'
                  }
                />
              </Stack>
              {loan.guarantor ? (
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <InfoRow
                    label={t('loans.guarantor.guarantor')}
                    value={loan.guarantor.guarantorName ?? ''}
                  />
                  <InfoRow
                    label={t('loans.guarantor.guaranteedAmount')}
                    value={formatOptionalMoney(loan.guarantor.guaranteedAmount)}
                  />
                </Stack>
              ) : null}
              {loan.guarantor ? (
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <InfoRow
                    label={t('loans.guarantor.response')}
                    value={
                      loan.guarantor.status
                        ? t(`loans.guarantor.status.${loan.guarantor.status}`, {
                            defaultValue: String(loan.guarantor.status),
                          })
                        : '—'
                    }
                  />
                  <InfoRow
                    label={t('loans.guarantor.respondedAt')}
                    value={loan.guarantor.respondedAt?.replace('T', ' ').slice(0, 19) ?? ''}
                  />
                </Stack>
              ) : null}
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.termMonths')}
                  value={String(loan.termMonths ?? '—')}
                />
                <InfoRow label={t('loans.fields.maturityDate')} value={loan.dueDate ?? ''} />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.prorataEnabled')}
                  value={
                    loan.prorataEnabled
                      ? t('loans.schedule.prorataYes')
                      : t('loans.schedule.prorataNo')
                  }
                />
                <InfoRow
                  label={t('loans.fields.firstPeriodDays')}
                  value={
                    loan.firstPeriodDays != null ? String(loan.firstPeriodDays) : '—'
                  }
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.schedule.equalInstallment')}
                  value={formatOptionalMoney(loan.equalInstallmentAmount)}
                />
                <InfoRow
                  label={t('loans.schedule.totalRepayment')}
                  value={formatOptionalMoney(loan.totalRepayment)}
                />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow
                  label={t('loans.fields.requestDate')}
                  value={loan.requestDate ?? ''}
                />
                <InfoRow
                  label={t('loans.fields.disbursementDate')}
                  value={loan.disbursementDate ?? ''}
                />
              </Stack>
              <InfoRow label={t('loans.fields.purpose')} value={loan.purpose ?? ''} />
              {loan.rejectionReason ? (
                <InfoRow
                  label={t('loans.fields.rejectionReason')}
                  value={loan.rejectionReason}
                />
              ) : null}
            </Stack>

            <Stack
              direction="row"
              spacing={1}
              useFlexGap
              sx={{ mt: 2.5, flexWrap: 'wrap' }}
            >
              {canShowApprove(status, canActOnApproval) ? (
                <Button
                  variant="contained"
                  onClick={() => {
                    approveForm.reset({
                      approvedAmount: String(
                        loan.approvedAmount ?? loan.requestedAmount ?? '',
                      ),
                      termMonths: loan.termMonths ? String(loan.termMonths) : '',
                    })
                    setApproveOpen(true)
                  }}
                >
                  {t('loans.actions.approve')}
                </Button>
              ) : null}
              {canShowReject(status, canActOnApproval) ? (
                <Button
                  variant="outlined"
                  color="error"
                  onClick={() => setRejectOpen(true)}
                >
                  {t('loans.actions.reject')}
                </Button>
              ) : null}
              {canShowDisburse(status, canRecordLoans) ? (
                <Button variant="contained" onClick={() => setConfirmAction('disburse')}>
                  {t('loans.actions.disburse')}
                </Button>
              ) : null}
              {canShowRepayment(status, canRecordLoans) ? (
                <Button variant="contained" onClick={() => setRepayOpen(true)}>
                  {t('loans.repayment.record')}
                </Button>
              ) : null}
              {canShowWriteOff(status, canAuthorizeFunds) ? (
                <Button
                  variant="outlined"
                  color="warning"
                  onClick={() => setConfirmAction('writeOff')}
                >
                  {t('loans.actions.writeOff')}
                </Button>
              ) : null}
            </Stack>
          </Paper>

          {loan.equalInstallmentAmount != null || (loan.repaymentSchedule?.length ?? 0) > 0 ? (
            <Paper
              elevation={0}
              sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}
            >
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ mb: 1, justifyContent: 'space-between' }}
              >
                <Typography variant="h6">{t('loans.schedule.title')}</Typography>
                {canDownloadSchedule ? (
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    <Button
                      size="small"
                      variant="outlined"
                      disabled={downloadMutation.isPending || shareMutation.isPending}
                      onClick={() => downloadMutation.mutate()}
                    >
                      {t('loans.schedule.downloadPdf')}
                    </Button>
                    {(() => {
                      const shareButton = (
                        <Button
                          size="small"
                          variant="outlined"
                          disabled={
                            downloadMutation.isPending ||
                            shareMutation.isPending ||
                            !whatsappConfigured ||
                            shareBlocked
                          }
                          onClick={openShare}
                        >
                          {shareMutation.isPending
                            ? t('loans.schedule.whatsappSending')
                            : t('loans.schedule.shareWhatsApp')}
                        </Button>
                      )
                      const tooltip = shareBlocked
                        ? t('subscription.readOnlyShare')
                        : !whatsappConfigured
                          ? t('reports.whatsapp.notConfigured')
                          : ''
                      if (!tooltip) return shareButton
                      return (
                        <Tooltip title={tooltip}>
                          <span>{shareButton}</span>
                        </Tooltip>
                      )
                    })()}
                  </Stack>
                ) : null}
              </Stack>
              <LoanSchedulePreview
                schedule={{
                  principal: loan.principalAmount ?? loan.approvedAmount ?? loan.requestedAmount,
                  monthlyInterestRatePercent: loan.interestRatePercent,
                  numberOfInstallments: loan.termMonths,
                  repaymentDateModel: loan.repaymentDateModel,
                  prorataEnabled: Boolean(loan.prorataEnabled),
                  firstPeriodDays: loan.firstPeriodDays,
                  regularMonthlyInterest: loan.regularMonthlyInterest,
                  firstPeriodInterest: loan.firstPeriodInterest,
                  totalInterest: loan.interestAmount,
                  totalRepayment: loan.totalRepayment,
                  equalInstallmentAmount: loan.equalInstallmentAmount,
                  scheduleFinalized: loan.scheduleFinalized,
                  installments: loan.repaymentSchedule,
                }}
              />
            </Paper>
          ) : null}

          {loan.applicationForm ? (
            <LoanApplicationFormView form={loan.applicationForm} />
          ) : null}

          <ApprovalHistory events={loan.approvalHistory} />

          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Typography variant="h6" gutterBottom>
              {t('loans.repayment.history')}
            </Typography>
            {repaymentsQuery.isLoading ? (
              <LoadingState variant="skeleton" rows={3} />
            ) : null}
            {repaymentsQuery.isError ? (
              <ErrorState
                message={getErrorMessage(repaymentsQuery.error)}
                onRetry={() => void repaymentsQuery.refetch()}
              />
            ) : null}
            {!repaymentsQuery.isLoading && !repaymentsQuery.isError ? (
              <ResponsiveTable
                columns={repaymentColumns}
                rows={repaymentsQuery.data ?? []}
                getRowId={(row) => row.id}
                emptyTitle={t('loans.repayment.emptyTitle')}
                emptyDescription={t('loans.repayment.emptyDescription')}
              />
            ) : null}
          </Paper>
        </Stack>

      <Dialog
        open={approveOpen}
        onClose={() => setApproveOpen(false)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>{t('loans.actions.approveTitle')}</DialogTitle>
        <form
          onSubmit={approveForm.handleSubmit((values) => {
            setApproveOpen(false)
            setConfirmAction('approve')
            // stash values on form; confirm will submit
            approveForm.reset(values)
          })}
        >
          <DialogContent>
            <Stack spacing={2} sx={{ pt: 1 }}>
              <TextField
                label={t('loans.fields.approvedAmount')}
                error={Boolean(approveForm.formState.errors.approvedAmount)}
                helperText={approveForm.formState.errors.approvedAmount?.message}
                {...approveForm.register('approvedAmount')}
                fullWidth
              />
              <TextField
                label={t('loans.fields.termMonths')}
                error={Boolean(approveForm.formState.errors.termMonths)}
                helperText={approveForm.formState.errors.termMonths?.message}
                {...approveForm.register('termMonths')}
                fullWidth
              />
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={() => setApproveOpen(false)}>{t('common.cancel')}</Button>
            <Button type="submit" variant="contained">
              {t('common.confirm')}
            </Button>
          </DialogActions>
        </form>
      </Dialog>

      <Dialog open={rejectOpen} onClose={() => setRejectOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{t('loans.actions.rejectTitle')}</DialogTitle>
        <form
          onSubmit={rejectForm.handleSubmit((values) => rejectMutation.mutate(values))}
        >
          <DialogContent>
            <TextField
              label={t('loans.fields.rejectionReason')}
              error={Boolean(rejectForm.formState.errors.rejectionReason)}
              helperText={rejectForm.formState.errors.rejectionReason?.message}
              {...rejectForm.register('rejectionReason')}
              fullWidth
              multiline
              minRows={2}
              sx={{ mt: 1 }}
            />
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={() => setRejectOpen(false)} disabled={rejectMutation.isPending}>
              {t('common.cancel')}
            </Button>
            <Button
              type="submit"
              variant="contained"
              color="error"
              disabled={rejectMutation.isPending}
            >
              {t('loans.actions.reject')}
            </Button>
          </DialogActions>
        </form>
      </Dialog>

      <ConfirmDialog
        open={confirmAction === 'approve'}
        title={t('loans.actions.confirmApproveTitle')}
        message={t('loans.actions.confirmApproveMessage')}
        loading={approveMutation.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={() => approveMutation.mutate(approveForm.getValues())}
      />

      <ConfirmDialog
        open={confirmAction === 'disburse'}
        title={t('loans.actions.confirmDisburseTitle')}
        message={t('loans.actions.confirmDisburseMessage')}
        loading={disburseMutation.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={() => disburseMutation.mutate()}
      />

      <ConfirmDialog
        open={confirmAction === 'writeOff'}
        title={t('loans.actions.confirmWriteOffTitle')}
        message={t('loans.actions.confirmWriteOffMessage')}
        loading={writeOffMutation.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={() => writeOffMutation.mutate()}
      />

      <RepaymentDialog
        open={repayOpen}
        loading={repayMutation.isPending || actionPending}
        maxHint={t('loans.repayment.maxHint', {
          amount: formatMoney(outstandingSum),
        })}
        onClose={() => setRepayOpen(false)}
        onSubmit={(payload) => repayMutation.mutate(payload)}
      />

      <WhatsAppShareDialog
        open={shareOpen}
        pending={shareMutation.isPending}
        phone={recipientPhone}
        title={t('loans.schedule.whatsappDialogTitle')}
        description={t('loans.schedule.whatsappDialogDescription')}
        sendingLabel={t('loans.schedule.whatsappSending')}
        onPhoneChange={setRecipientPhone}
        onClose={() => setShareOpen(false)}
        onSend={() => {
          if (shareBlocked) return
          shareMutation.mutate(recipientPhone)
        }}
      />
    </Box>
  )
}

export function LoanDetailRoute() {
  const { loanId = '' } = useParams()
  return (
    <RouteErrorBoundary resetKey={loanId}>
      <LoanDetailPage />
    </RouteErrorBoundary>
  )
}
