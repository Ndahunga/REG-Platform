import {
  Box,
  Button,
  Chip,
  Stack,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchGuarantorRequests, respondToGuarantorRequest } from '@/shared/api/loans'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { LoanGuarantor } from '@/shared/types/loan'
import { formatMoney } from '@/shared/utils/formatMoney'

interface GuarantorRequestsPanelProps {
  cooperativeId: string
}

export function GuarantorRequestsPanel({ cooperativeId }: GuarantorRequestsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()

  const query = useQuery({
    queryKey: ['loans', 'guarantor-requests', cooperativeId],
    queryFn: () => fetchGuarantorRequests(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const respondMutation = useMutation({
    mutationFn: ({ loanId, accepted }: { loanId: string; accepted: boolean }) =>
      respondToGuarantorRequest(cooperativeId, loanId, accepted),
    onSuccess: (_, variables) => {
      enqueueSnackbar(
        variables.accepted
          ? t('loans.guarantor.acceptSuccess')
          : t('loans.guarantor.rejectSuccess'),
        { variant: 'success' },
      )
      void queryClient.invalidateQueries({ queryKey: ['loans'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<LoanGuarantor>[] = useMemo(
    () => [
      {
        id: 'borrower',
        label: t('loans.guarantor.borrower'),
        render: (row) => row.borrowerName || '—',
      },
      {
        id: 'loan',
        label: t('loans.fields.amount'),
        render: (row) => formatMoney(row.loanAmount ?? 0),
      },
      {
        id: 'guaranteed',
        label: t('loans.guarantor.guaranteedAmount'),
        render: (row) => formatMoney(row.guaranteedAmount),
      },
      {
        id: 'status',
        label: t('loans.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            label={t(`loans.guarantor.status.${row.status}`, {
              defaultValue: String(row.status),
            })}
          />
        ),
      },
      {
        id: 'requestedAt',
        label: t('loans.guarantor.requestedAt'),
        render: (row) => row.requestedAt?.replace('T', ' ').slice(0, 19) || '—',
        hideOnMobile: true,
      },
      {
        id: 'actions',
        label: t('common.actions'),
        render: (row) =>
          row.status === 'PENDING' ? (
            <Stack direction="row" spacing={1}>
              <Button
                size="small"
                variant="contained"
                disabled={respondMutation.isPending}
                onClick={() =>
                  respondMutation.mutate({ loanId: row.loanId, accepted: true })
                }
              >
                {t('loans.guarantor.accept')}
              </Button>
              <Button
                size="small"
                color="error"
                disabled={respondMutation.isPending}
                onClick={() =>
                  respondMutation.mutate({ loanId: row.loanId, accepted: false })
                }
              >
                {t('loans.guarantor.reject')}
              </Button>
            </Stack>
          ) : (
            row.respondedAt?.replace('T', ' ').slice(0, 19) || '—'
          ),
      },
    ],
    [respondMutation, t],
  )

  if (query.isLoading) return <LoadingState variant="skeleton" rows={3} />
  if (query.isError) {
    return (
      <ErrorState
        message={getErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const rows = query.data ?? []
  if (rows.length === 0) {
    return (
      <EmptyState
        title={t('loans.guarantor.emptyTitle')}
        description={t('loans.guarantor.emptyDescription')}
      />
    )
  }

  return (
    <Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('loans.guarantor.description')}
      </Typography>
      <ResponsiveTable columns={columns} rows={rows} getRowId={(row) => row.id} />
    </Box>
  )
}
