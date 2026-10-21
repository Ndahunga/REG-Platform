import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  TablePagination,
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
import { getErrorMessage } from '@/shared/api/client'
import { uploadCooperativeFile } from '@/shared/api/files'
import { fetchMembers } from '@/shared/api/members'
import {
  approveSocialDisbursement,
  cancelSocialDisbursement,
  createSocialDisbursement,
  fetchSocialDisbursements,
  fetchSocialFundSummary,
  rejectSocialDisbursement,
} from '@/shared/api/socialFund'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { SocialDisbursement } from '@/shared/types/socialFund'
import {
  SOCIAL_DISBURSEMENT_STATUSES,
  socialDisbursementDisplayName,
} from '@/shared/types/socialFund'
import { memberDisplayName } from '@/shared/types/member'
import { formatMoney } from '@/shared/utils/formatMoney'
import {
  canApproveSocialDisbursement,
  canCancelSocialDisbursement,
  canRejectSocialDisbursement,
  socialStatusColor,
} from './socialFundHelpers'
import {
  socialDisbursementDefaults,
  socialDisbursementSchema,
  toSocialDisbursementPayload,
  type SocialDisbursementFormValues,
} from './socialFundFormSchemas'

interface SocialDisbursementsPanelProps {
  cooperativeId: string
  isAdmin: boolean
}

export function SocialDisbursementsPanel({
  cooperativeId,
  isAdmin,
}: SocialDisbursementsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [reviewTarget, setReviewTarget] = useState<{
    id: string
    action: 'approve' | 'reject' | 'cancel'
  } | null>(null)

  const summaryQuery = useQuery({
    queryKey: ['social-fund', 'summary', cooperativeId],
    queryFn: () => fetchSocialFundSummary(cooperativeId),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const listQuery = useQuery({
    queryKey: ['social-fund', 'disbursements', cooperativeId, status, page, size],
    queryFn: () =>
      fetchSocialDisbursements(cooperativeId, {
        status: status || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId),
  })

  const membersQuery = useQuery({
    queryKey: ['members', cooperativeId, 'social-disbursement-select'],
    queryFn: () => fetchMembers(cooperativeId, { status: 'ACTIVE', size: 200 }),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<SocialDisbursementFormValues>({
    defaultValues: socialDisbursementDefaults,
    resolver: yupResolver(socialDisbursementSchema),
  })

  const evidenceKey = watch('evidenceFileKey')
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadedName, setUploadedName] = useState<string | null>(null)

  const uploadMutation = useMutation({
    mutationFn: (file: File) =>
      uploadCooperativeFile(cooperativeId, file, 'SOCIAL_EVIDENCE'),
    onSuccess: (file) => {
      setValue('evidenceFileKey', file.storageKey)
      setUploadedName(file.originalFilename)
      setUploadError(null)
    },
    onError: (error) => {
      setUploadError(getErrorMessage(error, t('fines.payment.uploadFailed')))
    },
  })

  const createMutation = useMutation({
    mutationFn: (values: SocialDisbursementFormValues) =>
      createSocialDisbursement(cooperativeId, toSocialDisbursementPayload(values)),
    onSuccess: () => {
      enqueueSnackbar(t('socialFund.disbursements.createSuccess'), { variant: 'success' })
      reset(socialDisbursementDefaults)
      setUploadedName(null)
      setUploadError(null)
      void queryClient.invalidateQueries({ queryKey: ['social-fund'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget) throw new Error('Missing review target')
      if (reviewTarget.action === 'approve') {
        return approveSocialDisbursement(cooperativeId, reviewTarget.id)
      }
      if (reviewTarget.action === 'reject') {
        return rejectSocialDisbursement(cooperativeId, reviewTarget.id)
      }
      return cancelSocialDisbursement(cooperativeId, reviewTarget.id)
    },
    onSuccess: () => {
      const key =
        reviewTarget?.action === 'approve'
          ? 'socialFund.disbursements.approveSuccess'
          : reviewTarget?.action === 'reject'
            ? 'socialFund.disbursements.rejectSuccess'
            : 'socialFund.disbursements.cancelSuccess'
      enqueueSnackbar(t(key), { variant: 'success' })
      setReviewTarget(null)
      void queryClient.invalidateQueries({ queryKey: ['social-fund'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const currency = summaryQuery.data?.currency || 'RWF'
  const balance = summaryQuery.data?.balance

  const columns: TableColumn<SocialDisbursement>[] = useMemo(
    () => [
      {
        id: 'beneficiary',
        label: t('socialFund.fields.beneficiary'),
        render: (row) => socialDisbursementDisplayName(row),
      },
      {
        id: 'amount',
        label: t('socialFund.fields.amount'),
        render: (row) => formatMoney(row.amount),
      },
      {
        id: 'reason',
        label: t('socialFund.fields.reason'),
        render: (row) => row.reason || '—',
        hideOnMobile: true,
      },
      {
        id: 'date',
        label: t('socialFund.fields.disbursementDate'),
        render: (row) => row.disbursementDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('socialFund.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={socialStatusColor(String(row.status))}
            label={t(`socialFund.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      ...(isAdmin
        ? [
            {
              id: 'actions',
              label: t('common.actions'),
              render: (row: SocialDisbursement) => {
                const canApprove = canApproveSocialDisbursement(String(row.status), isAdmin)
                const canReject = canRejectSocialDisbursement(String(row.status), isAdmin)
                const canCancel = canCancelSocialDisbursement(String(row.status), isAdmin)
                if (!canApprove && !canReject && !canCancel) return '—'
                return (
                  <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    {canApprove ? (
                      <Button
                        size="small"
                        variant="outlined"
                        color="success"
                        onClick={(e) => {
                          e.stopPropagation()
                          setReviewTarget({ id: row.id, action: 'approve' })
                        }}
                      >
                        {t('socialFund.actions.approve')}
                      </Button>
                    ) : null}
                    {canReject ? (
                      <Button
                        size="small"
                        variant="outlined"
                        color="error"
                        onClick={(e) => {
                          e.stopPropagation()
                          setReviewTarget({ id: row.id, action: 'reject' })
                        }}
                      >
                        {t('socialFund.actions.reject')}
                      </Button>
                    ) : null}
                    {canCancel ? (
                      <Button
                        size="small"
                        variant="text"
                        onClick={(e) => {
                          e.stopPropagation()
                          setReviewTarget({ id: row.id, action: 'cancel' })
                        }}
                      >
                        {t('socialFund.actions.cancel')}
                      </Button>
                    ) : null}
                  </Stack>
                )
              },
            },
          ]
        : []),
    ],
    [isAdmin, t],
  )

  if (!isAdmin) {
    return (
      <Stack spacing={2}>
        <Alert severity="info">{t('socialFund.disbursements.memberViewHint')}</Alert>
        {listQuery.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
        {listQuery.isError ? (
          <ErrorState
            message={getErrorMessage(listQuery.error)}
            onRetry={() => void listQuery.refetch()}
          />
        ) : null}
        {!listQuery.isLoading && !listQuery.isError ? (
          <>
            <ResponsiveTable
              columns={columns}
              rows={listQuery.data?.content ?? []}
              getRowId={(row) => row.id}
              emptyTitle={t('socialFund.disbursements.emptyTitle')}
              emptyDescription={t('socialFund.disbursements.memberEmptyDescription')}
            />
            <TablePagination
              component="div"
              count={listQuery.data?.totalElements ?? 0}
              page={page}
              onPageChange={(_, next) => setPage(next)}
              rowsPerPage={size}
              onRowsPerPageChange={(e) => {
                setSize(Number(e.target.value))
                setPage(0)
              }}
              rowsPerPageOptions={[5, 10, 25]}
            />
          </>
        ) : null}
      </Stack>
    )
  }

  return (
    <Stack spacing={2.5}>
      <Alert severity="warning">
        {t('socialFund.disbursements.balanceCheckHint', {
          balance:
            balance != null ? formatMoney(balance, { currency }) : t('common.loading'),
        })}
      </Alert>

      <Box
        component="form"
        noValidate
        onSubmit={handleSubmit((values) => createMutation.mutate(values))}
        sx={{
          maxWidth: 560,
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1,
        }}
      >
        <Typography variant="h6" gutterBottom>
          {t('socialFund.disbursements.createTitle')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('socialFund.disbursements.createDescription')}
        </Typography>

        {membersQuery.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
        {membersQuery.isError ? (
          <ErrorState
            message={getErrorMessage(membersQuery.error)}
            onRetry={() => void membersQuery.refetch()}
          />
        ) : null}

        {!membersQuery.isLoading && !membersQuery.isError ? (
          <Stack spacing={2}>
            <TextField
              select
              label={t('socialFund.fields.beneficiary')}
              error={Boolean(errors.beneficiaryMemberUserId)}
              helperText={errors.beneficiaryMemberUserId?.message}
              {...register('beneficiaryMemberUserId')}
              fullWidth
            >
              {(membersQuery.data?.content ?? []).map((member) => (
                <MenuItem key={member.userId} value={member.userId}>
                  {memberDisplayName(member)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label={t('socialFund.fields.amount')}
              error={Boolean(errors.amount)}
              helperText={errors.amount?.message}
              {...register('amount')}
              fullWidth
            />
            <TextField
              label={t('socialFund.fields.reason')}
              error={Boolean(errors.reason)}
              helperText={errors.reason?.message}
              {...register('reason')}
              fullWidth
            />
            <TextField
              type="date"
              label={t('socialFund.fields.disbursementDate')}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
              error={Boolean(errors.disbursementDate)}
              helperText={errors.disbursementDate?.message}
              {...register('disbursementDate')}
              fullWidth
            />
            <TextField
              label={t('socialFund.fields.notes')}
              multiline
              minRows={2}
              error={Boolean(errors.notes)}
              helperText={errors.notes?.message}
              {...register('notes')}
              fullWidth
            />
            <Stack spacing={1}>
              <Typography variant="subtitle2">{t('fines.payment.evidence')}</Typography>
              <Button
                variant="outlined"
                component="label"
                disabled={!cooperativeId || uploadMutation.isPending}
              >
                {uploadMutation.isPending
                  ? t('common.loading')
                  : t('fines.payment.uploadEvidence')}
                <input
                  hidden
                  type="file"
                  accept=".pdf,.png,.jpg,.jpeg,.webp,.xlsx,application/pdf,image/*"
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    if (file) uploadMutation.mutate(file)
                    e.target.value = ''
                  }}
                />
              </Button>
              {uploadedName || evidenceKey ? (
                <Typography variant="body2" color="text.secondary">
                  {uploadedName || evidenceKey}
                </Typography>
              ) : (
                <Typography variant="caption" color="text.secondary">
                  {t('fines.payment.evidenceOptional')}
                </Typography>
              )}
              {uploadError ? <Alert severity="error">{uploadError}</Alert> : null}
            </Stack>
            <Button type="submit" variant="contained" disabled={createMutation.isPending}>
              {t('socialFund.disbursements.submit')}
            </Button>
          </Stack>
        ) : null}
      </Box>

      <Box>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          useFlexGap
          sx={{ mb: 2, flexWrap: 'wrap', alignItems: { sm: 'center' } }}
        >
          <Typography variant="h6" sx={{ flex: 1 }}>
            {t('socialFund.disbursements.listTitle')}
          </Typography>
          <TextField
            select
            size="small"
            label={t('socialFund.fields.status')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value)
              setPage(0)
            }}
            sx={{ minWidth: 160 }}
          >
            <MenuItem value="">{t('common.all')}</MenuItem>
            {SOCIAL_DISBURSEMENT_STATUSES.map((s) => (
              <MenuItem key={s} value={s}>
                {t(`socialFund.status.${s}`)}
              </MenuItem>
            ))}
          </TextField>
        </Stack>

        {listQuery.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}
        {listQuery.isError ? (
          <ErrorState
            message={getErrorMessage(listQuery.error)}
            onRetry={() => void listQuery.refetch()}
          />
        ) : null}
        {!listQuery.isLoading && !listQuery.isError ? (
          (listQuery.data?.content?.length ?? 0) === 0 && !status ? (
            <EmptyState
              title={t('socialFund.disbursements.emptyTitle')}
              description={t('socialFund.disbursements.emptyDescription')}
            />
          ) : (
            <>
              <ResponsiveTable
                columns={columns}
                rows={listQuery.data?.content ?? []}
                getRowId={(row) => row.id}
                emptyTitle={t('socialFund.disbursements.emptyTitle')}
                emptyDescription={t('socialFund.disbursements.emptyDescription')}
              />
              <TablePagination
                component="div"
                count={listQuery.data?.totalElements ?? 0}
                page={page}
                onPageChange={(_, next) => setPage(next)}
                rowsPerPage={size}
                onRowsPerPageChange={(e) => {
                  setSize(Number(e.target.value))
                  setPage(0)
                }}
                rowsPerPageOptions={[5, 10, 25]}
              />
            </>
          )
        ) : null}
      </Box>

      <ConfirmDialog
        open={Boolean(reviewTarget)}
        title={
          reviewTarget?.action === 'approve'
            ? t('socialFund.disbursements.confirmApproveTitle')
            : reviewTarget?.action === 'reject'
              ? t('socialFund.disbursements.confirmRejectTitle')
              : t('socialFund.disbursements.confirmCancelTitle')
        }
        message={
          reviewTarget?.action === 'approve'
            ? t('socialFund.disbursements.confirmApproveMessage')
            : reviewTarget?.action === 'reject'
              ? t('socialFund.disbursements.confirmRejectMessage')
              : t('socialFund.disbursements.confirmCancelMessage')
        }
        loading={reviewMutation.isPending}
        onCancel={() => setReviewTarget(null)}
        onConfirm={() => reviewMutation.mutate()}
      />
    </Stack>
  )
}
