import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
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
import { fetchMembers } from '@/shared/api/members'
import { uploadCooperativeFile } from '@/shared/api/files'
import {
  approveSocialContribution,
  createSocialContribution,
  fetchMySocialContributions,
  fetchSocialContributions,
  rejectSocialContribution,
} from '@/shared/api/socialFund'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { ApprovalHistory } from '@/shared/components/ApprovalHistory'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { SocialContribution } from '@/shared/types/socialFund'
import {
  SOCIAL_CONTRIBUTION_STATUSES,
  socialContributionDisplayName,
} from '@/shared/types/socialFund'
import { memberDisplayName } from '@/shared/types/member'
import { formatMoney } from '@/shared/utils/formatMoney'
import {
  canApproveSocialContribution,
  canRejectSocialContribution,
  socialStatusColor,
} from './socialFundHelpers'
import {
  socialContributionDefaults,
  socialContributionSchema,
  toSocialContributionPayload,
  type SocialContributionFormValues,
} from './socialFundFormSchemas'

interface SocialContributionsPanelProps {
  cooperativeId: string
  isAdmin: boolean
  canWrite?: boolean
  defaultStatus?: string
  hideSubmit?: boolean
}

export function SocialContributionsPanel({
  cooperativeId,
  isAdmin,
  canWrite = isAdmin,
  defaultStatus = '',
  hideSubmit = false,
}: SocialContributionsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [status, setStatus] = useState(defaultStatus)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [reviewTarget, setReviewTarget] = useState<{
    id: string
    action: 'approve' | 'reject'
  } | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [uploadedName, setUploadedName] = useState<string | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const listQuery = useQuery({
    queryKey: ['social-fund', 'contributions', cooperativeId, isAdmin, status, page, size],
    queryFn: async () => {
      if (isAdmin) {
        return fetchSocialContributions(cooperativeId, {
          status: status || undefined,
          page,
          size,
          sort: 'createdAt,desc',
        })
      }
      const list = await fetchMySocialContributions(cooperativeId)
      const filtered = status
        ? list.filter((row) => String(row.status) === status)
        : list
      const start = page * size
      const totalElements = filtered.length
      const totalPages = Math.ceil(totalElements / size) || 0
      return {
        content: filtered.slice(start, start + size),
        page,
        size,
        totalElements,
        totalPages,
        first: page === 0,
        last: page >= totalPages - 1 || totalElements === 0,
      }
    },
    enabled: Boolean(cooperativeId),
  })

  const membersQuery = useQuery({
    queryKey: ['members', cooperativeId, 'social-contrib-select'],
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
  } = useForm<SocialContributionFormValues>({
    defaultValues: socialContributionDefaults,
    resolver: yupResolver(socialContributionSchema),
  })

  const evidenceKey = watch('evidenceFileKey')

  const uploadMutation = useMutation({
    mutationFn: (file: File) =>
      uploadCooperativeFile(cooperativeId, file, 'SOCIAL_EVIDENCE'),
    onSuccess: (file) => {
      setValue('evidenceFileKey', file.storageKey)
      setUploadedName(file.originalFilename)
      setUploadError(null)
    },
    onError: (error) => {
      setUploadError(getErrorMessage(error, t('socialFund.contributions.uploadFailed')))
    },
  })

  const submitMutation = useMutation({
    mutationFn: (values: SocialContributionFormValues) =>
      createSocialContribution(
        cooperativeId,
        toSocialContributionPayload(values, { includeMember: isAdmin }),
      ),
    onSuccess: () => {
      enqueueSnackbar(t('socialFund.contributions.submitSuccess'), { variant: 'success' })
      reset(socialContributionDefaults)
      setUploadedName(null)
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
      return reviewTarget.action === 'approve'
        ? approveSocialContribution(cooperativeId, reviewTarget.id)
        : rejectSocialContribution(cooperativeId, reviewTarget.id, {
            reviewNotes: rejectReason.trim(),
          })
    },
    onSuccess: () => {
      enqueueSnackbar(
        reviewTarget?.action === 'approve'
          ? t('socialFund.contributions.approveSuccess')
          : t('socialFund.contributions.rejectSuccess'),
        { variant: 'success' },
      )
      setReviewTarget(null)
      setRejectReason('')
      void queryClient.invalidateQueries({ queryKey: ['social-fund'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<SocialContribution>[] = useMemo(
    () => [
      ...(isAdmin
        ? [
            {
              id: 'member',
              label: t('socialFund.fields.member'),
              render: (row: SocialContribution) => socialContributionDisplayName(row),
            },
          ]
        : []),
      {
        id: 'amount',
        label: t('socialFund.fields.amount'),
        render: (row) => formatMoney(row.amount),
      },
      {
        id: 'date',
        label: t('socialFund.fields.contributionDate'),
        render: (row) => row.contributionDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'reference',
        label: t('socialFund.fields.reference'),
        render: (row) => row.paymentReference || '—',
        hideOnMobile: true,
      },
      {
        id: 'proof',
        label: t('socialFund.fields.proof'),
        render: (row: SocialContribution) =>
          row.evidenceFileKey ? (
            <AuthenticatedFileLink storageKey={row.evidenceFileKey}>
              {t('socialFund.contributions.viewProof')}
            </AuthenticatedFileLink>
          ) : (
            '—'
          ),
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
      ...(canWrite
        ? [
            {
              id: 'actions',
              label: t('common.actions'),
              render: (row: SocialContribution) => {
                const canApprove = canApproveSocialContribution(String(row.status), canWrite)
                const canReject = canRejectSocialContribution(String(row.status), canWrite)
                if (!canApprove && !canReject) return '—'
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
                          setRejectReason('')
                          setReviewTarget({ id: row.id, action: 'reject' })
                        }}
                      >
                        {t('socialFund.actions.reject')}
                      </Button>
                    ) : null}
                  </Stack>
                )
              },
            },
          ]
        : []),
    ],
    [canWrite, t],
  )

  return (
    <Stack spacing={2.5}>
      <Alert severity="info">{t('socialFund.contributions.hint')}</Alert>

      {hideSubmit ? null : (
      <Box
        component="form"
        noValidate
        onSubmit={handleSubmit((values) => submitMutation.mutate(values))}
        sx={{
          maxWidth: 560,
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1,
        }}
      >
        <Typography variant="h6" gutterBottom>
          {t('socialFund.contributions.submitTitle')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('socialFund.contributions.submitDescription')}
        </Typography>
        <Stack spacing={2}>
          {isAdmin ? (
            <TextField
              select
              label={t('socialFund.fields.member')}
              error={Boolean(errors.memberUserId)}
              helperText={
                errors.memberUserId?.message || t('socialFund.contributions.memberOptional')
              }
              {...register('memberUserId')}
              fullWidth
            >
              <MenuItem value="">{t('socialFund.contributions.selfSubmit')}</MenuItem>
              {(membersQuery.data?.content ?? []).map((member) => (
                <MenuItem key={member.userId} value={member.userId}>
                  {memberDisplayName(member)}
                </MenuItem>
              ))}
            </TextField>
          ) : null}
          <TextField
            label={t('socialFund.fields.amount')}
            error={Boolean(errors.amount)}
            helperText={errors.amount?.message}
            {...register('amount')}
            fullWidth
          />
          <TextField
            type="date"
            label={t('socialFund.fields.contributionDate')}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
            error={Boolean(errors.contributionDate)}
            helperText={errors.contributionDate?.message}
            {...register('contributionDate')}
            fullWidth
          />
          <TextField
            label={t('socialFund.fields.reference')}
            error={Boolean(errors.paymentReference)}
            helperText={errors.paymentReference?.message}
            {...register('paymentReference')}
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
            <Typography variant="subtitle2">{t('socialFund.fields.proof')}</Typography>
            <Button variant="outlined" component="label" disabled={uploadMutation.isPending}>
              {uploadMutation.isPending
                ? t('common.loading')
                : t('socialFund.contributions.uploadProof')}
              <input
                hidden
                type="file"
                accept=".pdf,.png,.jpg,.jpeg,.webp,application/pdf,image/*"
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
                {t('socialFund.contributions.proofHint')}
              </Typography>
            )}
            {uploadError ? <Alert severity="error">{uploadError}</Alert> : null}
          </Stack>
          <Button type="submit" variant="contained" disabled={submitMutation.isPending}>
            {t('socialFund.contributions.submit')}
          </Button>
        </Stack>
      </Box>
      )}

      <Box>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          useFlexGap
          sx={{ mb: 2, flexWrap: 'wrap', alignItems: { sm: 'center' } }}
        >
          <Typography variant="h6" sx={{ flex: 1 }}>
            {isAdmin
              ? t('socialFund.contributions.allTitle')
              : t('socialFund.contributions.myTitle')}
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
            {SOCIAL_CONTRIBUTION_STATUSES.map((s) => (
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
          <>
            <ResponsiveTable
              columns={columns}
              rows={listQuery.data?.content ?? []}
              getRowId={(row) => row.id}
              onRowClick={(row) => setSelectedId(row.id)}
              emptyTitle={t('socialFund.contributions.emptyTitle')}
              emptyDescription={
                isAdmin
                  ? t('socialFund.contributions.emptyDescription')
                  : t('socialFund.contributions.myEmptyDescription')
              }
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
            {(reviewTarget?.id ?? selectedId) ? (
              <Box sx={{ mt: 2 }}>
                <ApprovalHistory
                  events={
                    (listQuery.data?.content ?? []).find(
                      (row) => row.id === (reviewTarget?.id ?? selectedId),
                    )?.approvalHistory
                  }
                />
              </Box>
            ) : null}
          </>
        ) : null}
      </Box>

      <ConfirmDialog
        open={reviewTarget?.action === 'approve'}
        title={t('socialFund.contributions.confirmApproveTitle')}
        message={t('socialFund.contributions.confirmApproveMessage')}
        loading={reviewMutation.isPending}
        onCancel={() => setReviewTarget(null)}
        onConfirm={() => reviewMutation.mutate()}
      />

      <Dialog
        open={reviewTarget?.action === 'reject'}
        onClose={() => setReviewTarget(null)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>{t('socialFund.contributions.confirmRejectTitle')}</DialogTitle>
        <DialogContent>
          <TextField
            label={t('socialFund.contributions.rejectionReason')}
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            required
            error={rejectReason.trim().length > 2000}
            helperText={
              rejectReason.trim().length > 2000 ? t('common.validation.reasonTooLong') : undefined
            }
            fullWidth
            multiline
            minRows={2}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setReviewTarget(null)} disabled={reviewMutation.isPending}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color="error"
            disabled={!rejectReason.trim() || rejectReason.trim().length > 2000 || reviewMutation.isPending}
            onClick={() => reviewMutation.mutate()}
          >
            {t('socialFund.actions.reject')}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
