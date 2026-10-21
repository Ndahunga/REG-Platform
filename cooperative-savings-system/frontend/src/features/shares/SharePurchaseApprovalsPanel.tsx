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
import { contributionStatusColor } from '@/features/contributions'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveSharePurchase,
  fetchPendingSharePurchases,
  rejectSharePurchase,
} from '@/shared/api/shares'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { SharePurchase } from '@/shared/types/share'
import { formatMoney } from '@/shared/utils/formatMoney'

interface SharePurchaseApprovalsPanelProps {
  cooperativeId: string
}

export function SharePurchaseApprovalsPanel({
  cooperativeId,
}: SharePurchaseApprovalsPanelProps) {
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
    queryKey: ['shares', 'pending', cooperativeId, page, size],
    queryFn: () => fetchPendingSharePurchases(cooperativeId, { page, size }),
    enabled: Boolean(cooperativeId),
  })

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget) throw new Error('Missing review target')
      return reviewTarget.action === 'approve'
        ? approveSharePurchase(cooperativeId, reviewTarget.id)
        : rejectSharePurchase(cooperativeId, reviewTarget.id, {
            rejectionReason: rejectReason.trim(),
          })
    },
    onSuccess: () => {
      enqueueSnackbar(
        reviewTarget?.action === 'approve'
          ? t('shares.approvals.approveSuccess')
          : t('shares.approvals.rejectSuccess'),
        { variant: 'success' },
      )
      setReviewTarget(null)
      setRejectReason('')
      void queryClient.invalidateQueries({ queryKey: ['shares'] })
      void queryClient.invalidateQueries({ queryKey: ['members'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      void queryClient.invalidateQueries({ queryKey: ['notifications-pending-approvals'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<SharePurchase>[] = useMemo(
    () => [
      {
        id: 'member',
        label: t('shares.fields.member'),
        render: (row) => row.memberName || row.memberUserId,
      },
      {
        id: 'shares',
        label: t('shares.fields.numberOfShares'),
        render: (row) => String(row.numberOfShares),
      },
      {
        id: 'price',
        label: t('shares.fields.pricePerShare'),
        render: (row) => formatMoney(row.pricePerShare, { currency: row.currency }),
      },
      {
        id: 'total',
        label: t('shares.fields.totalAmount'),
        render: (row) => formatMoney(row.totalAmount, { currency: row.currency }),
      },
      {
        id: 'paymentDate',
        label: t('contributions.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
      },
      {
        id: 'reference',
        label: t('contributions.fields.reference'),
        render: (row) => row.paymentReference || '—',
        hideOnMobile: true,
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
        id: 'date',
        label: t('shares.fields.requestedAt'),
        render: (row) => (row.requestedAt ? row.requestedAt.slice(0, 10) : '—'),
      },
      {
        id: 'status',
        label: t('shares.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={contributionStatusColor(String(row.status))}
            label={t(`shares.status.${row.status}`, { defaultValue: String(row.status) })}
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
              {t('shares.approvals.approve')}
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
              {t('shares.approvals.reject')}
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

  return (
    <Stack spacing={2}>
      <Typography variant="h6">{t('shares.approvals.title')}</Typography>
      <ResponsiveTable
        columns={columns}
        rows={query.data?.content ?? []}
        getRowId={(row) => row.id}
        emptyTitle={t('shares.approvals.emptyTitle')}
        emptyDescription={t('shares.approvals.emptyDescription')}
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

      <ConfirmDialog
        open={reviewTarget?.action === 'approve'}
        title={t('shares.approvals.confirmApproveTitle')}
        message={t('shares.approvals.confirmApproveMessage')}
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
        <DialogTitle>{t('shares.approvals.confirmRejectTitle')}</DialogTitle>
        <DialogContent>
          <TextField
            label={t('shares.approvals.rejectionReason')}
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
            {t('shares.approvals.reject')}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
