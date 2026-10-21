import {
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TablePagination,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveContributionSubmission,
  fetchPendingContributionReviews,
  rejectContributionSubmission,
} from '@/shared/api/contributions'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { ApprovalHistory } from '@/shared/components/ApprovalHistory'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { Contribution } from '@/shared/types/contribution'
import { formatMoney } from '@/shared/utils/formatMoney'
import { contributionStatusColor } from './contributionHelpers'

interface ContributionApprovalsPanelProps {
  cooperativeId: string
}

export function ContributionApprovalsPanel({
  cooperativeId,
}: ContributionApprovalsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [reviewTarget, setReviewTarget] = useState<{
    id: string
    action: 'approve' | 'reject'
  } | null>(null)
  const [rejectReason, setRejectReason] = useState('')

  const query = useQuery({
    queryKey: ['contributions', 'pending-review', cooperativeId, page, size],
    queryFn: () =>
      fetchPendingContributionReviews(cooperativeId, {
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId),
  })

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget) throw new Error('Missing review target')
      return reviewTarget.action === 'approve'
        ? approveContributionSubmission(cooperativeId, reviewTarget.id)
        : rejectContributionSubmission(cooperativeId, reviewTarget.id, {
            rejectionReason: rejectReason.trim(),
          })
    },
    onSuccess: () => {
      enqueueSnackbar(
        reviewTarget?.action === 'approve'
          ? t('contributions.approvals.approveSuccess')
          : t('contributions.approvals.rejectSuccess'),
        { variant: 'success' },
      )
      setReviewTarget(null)
      setRejectReason('')
      void queryClient.invalidateQueries({ queryKey: ['contributions'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<Contribution>[] = useMemo(
    () => [
      {
        id: 'member',
        label: t('contributions.fields.member'),
        render: (row) => row.fullName || row.memberName || row.memberUserId,
      },
      {
        id: 'amount',
        label: t('contributions.fields.paid'),
        render: (row) => formatMoney(row.submittedAmount ?? row.paidAmount),
      },
      {
        id: 'date',
        label: t('contributions.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
      },
      {
        id: 'proof',
        label: t('contributions.submit.proof'),
        render: (row) =>
          row.evidenceFileKey ? (
            <AuthenticatedFileLink storageKey={row.evidenceFileKey}>
              {t('contributions.approvals.viewProof')}
            </AuthenticatedFileLink>
          ) : (
            '—'
          ),
      },
      {
        id: 'status',
        label: t('contributions.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={contributionStatusColor(String(row.reviewStatus || row.status))}
            label={t(`contributions.reviewStatus.${row.reviewStatus || row.status}`, {
              defaultValue: String(row.reviewStatus || row.status),
            })}
          />
        ),
      },
      {
        id: 'actions',
        label: t('common.actions'),
        render: (row) => (
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <Button
              size="small"
              variant="outlined"
              color="success"
              onClick={(e) => {
                e.stopPropagation()
                setReviewTarget({ id: row.id, action: 'approve' })
              }}
            >
              {t('contributions.approvals.approve')}
            </Button>
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
              {t('contributions.approvals.reject')}
            </Button>
          </Stack>
        ),
      },
    ],
    [t],
  )

  if (query.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (query.isError) {
    return (
      <ErrorState
        message={getErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const selected = query.data?.content.find((row) => row.id === reviewTarget?.id)

  return (
    <Stack spacing={2}>
      <Typography variant="h6">{t('contributions.approvals.title')}</Typography>
      <ResponsiveTable
        columns={columns}
        rows={query.data?.content ?? []}
        getRowId={(row) => row.id}
        emptyTitle={t('contributions.approvals.emptyTitle')}
        emptyDescription={t('contributions.approvals.emptyDescription')}
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

      {selected ? <ApprovalHistory events={selected.approvalHistory} /> : null}

      <ConfirmDialog
        open={reviewTarget?.action === 'approve'}
        title={t('contributions.approvals.confirmApproveTitle')}
        message={t('contributions.approvals.confirmApproveMessage')}
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
        <DialogTitle>{t('contributions.approvals.confirmRejectTitle')}</DialogTitle>
        <DialogContent>
          <TextField
            label={t('contributions.approvals.rejectionReason')}
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
            {t('contributions.approvals.reject')}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
