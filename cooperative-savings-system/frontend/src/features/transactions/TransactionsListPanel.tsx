import {
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
  ToggleButton,
  ToggleButtonGroup,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveTransaction,
  fetchTransactions,
  rejectTransaction,
} from '@/shared/api/transactions'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { DateRangeFields } from '@/shared/components/DateRangeFields'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { IncomeExpenseTransaction } from '@/shared/types/incomeExpense'
import {
  INCOME_EXPENSE_APPROVAL_STATUSES,
  INCOME_EXPENSE_CATEGORIES,
} from '@/shared/types/incomeExpense'
import { formatMoney } from '@/shared/utils/formatMoney'
import { validateOptionalDateRange } from '@/shared/utils/filterValidation'
import {
  canApproveTransaction,
  canRejectTransaction,
  matchesTransactionBucket,
  transactionCategoryColor,
  transactionStatusColor,
  type TransactionBucket,
} from './transactionHelpers'
import {
  transactionRejectDefaults,
  transactionRejectSchema,
  type TransactionRejectFormValues,
} from './transactionFormSchemas'

interface TransactionsListPanelProps {
  cooperativeId: string
  isAdmin: boolean
}

export function TransactionsListPanel({
  cooperativeId,
  isAdmin,
}: TransactionsListPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [bucket, setBucket] = useState<TransactionBucket>('all')
  const [category, setCategory] = useState('')
  const [status, setStatus] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [approveId, setApproveId] = useState<string | null>(null)
  const [rejectId, setRejectId] = useState<string | null>(null)

  const rejectForm = useForm<TransactionRejectFormValues>({
    defaultValues: transactionRejectDefaults,
    resolver: yupResolver(transactionRejectSchema),
  })

  const dateIssue = validateOptionalDateRange(from, to)
  const filtersValid = !dateIssue

  const query = useQuery({
    queryKey: [
      'transactions',
      cooperativeId,
      category,
      status,
      from,
      to,
      page,
      size,
    ],
    queryFn: () =>
      fetchTransactions(cooperativeId, {
        category: category || undefined,
        status: status || undefined,
        from: from || undefined,
        to: to || undefined,
        page,
        size,
        sort: 'transactionDate,desc',
      }),
    enabled: Boolean(cooperativeId) && filtersValid,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['transactions'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    void queryClient.invalidateQueries({ queryKey: ['ledger'] })
  }

  const approveMutation = useMutation({
    mutationFn: (id: string) => approveTransaction(cooperativeId, id),
    onSuccess: () => {
      enqueueSnackbar(t('transactions.actions.approveSuccess'), { variant: 'success' })
      setApproveId(null)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const rejectMutation = useMutation({
    mutationFn: ({
      id,
      values,
    }: {
      id: string
      values: TransactionRejectFormValues
    }) =>
      rejectTransaction(cooperativeId, id, {
        rejectionReason: values.rejectionReason.trim(),
      }),
    onSuccess: () => {
      enqueueSnackbar(t('transactions.actions.rejectSuccess'), { variant: 'success' })
      setRejectId(null)
      rejectForm.reset(transactionRejectDefaults)
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const rows = useMemo(() => {
    const content = query.data?.content ?? []
    if (category) return content
    return content.filter((row) => matchesTransactionBucket(String(row.category), bucket))
  }, [bucket, category, query.data?.content])

  const columns: TableColumn<IncomeExpenseTransaction>[] = useMemo(
    () => [
      {
        id: 'date',
        label: t('transactions.fields.transactionDate'),
        render: (row) => row.transactionDate || '—',
      },
      {
        id: 'category',
        label: t('transactions.fields.category'),
        render: (row) => (
          <Chip
            size="small"
            color={transactionCategoryColor(String(row.category))}
            label={t(`transactions.category.${row.category}`, {
              defaultValue: String(row.category),
            })}
          />
        ),
      },
      {
        id: 'amount',
        label: t('transactions.fields.amount'),
        render: (row) => formatMoney(row.amount, { currency: row.currency }),
      },
      {
        id: 'status',
        label: t('transactions.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={transactionStatusColor(String(row.approvalStatus))}
            label={t(`transactions.status.${row.approvalStatus}`, {
              defaultValue: String(row.approvalStatus),
            })}
          />
        ),
      },
      {
        id: 'description',
        label: t('transactions.fields.description'),
        render: (row) => row.description || row.notes || '—',
        hideOnMobile: true,
      },
      {
        id: 'reference',
        label: t('transactions.fields.reference'),
        render: (row) => row.reference || '—',
        hideOnMobile: true,
      },
      {
        id: 'actions',
        label: t('common.actions'),
        render: (row) => {
          const rowStatus = String(row.approvalStatus)
          const showApprove = canApproveTransaction(rowStatus, isAdmin)
          const showReject = canRejectTransaction(rowStatus, isAdmin)
          if (!showApprove && !showReject) {
            return rowStatus === 'APPROVED' ? t('transactions.approvedLocked') : '—'
          }
          return (
            <Stack direction="row" spacing={0.5}>
              {showApprove ? (
                <Button size="small" onClick={() => setApproveId(row.id)}>
                  {t('transactions.actions.approve')}
                </Button>
              ) : null}
              {showReject ? (
                <Button size="small" color="error" onClick={() => setRejectId(row.id)}>
                  {t('transactions.actions.reject')}
                </Button>
              ) : null}
            </Stack>
          )
        },
      },
    ],
    [isAdmin, t],
  )

  return (
    <Box>
      <Stack spacing={1.5} sx={{ mb: 2 }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={bucket}
          onChange={(_, value: TransactionBucket | null) => {
            if (!value) return
            setBucket(value)
            setCategory('')
            setPage(0)
          }}
        >
          <ToggleButton value="all">{t('transactions.filters.all')}</ToggleButton>
          <ToggleButton value="income">{t('transactions.filters.income')}</ToggleButton>
          <ToggleButton value="expenses">{t('transactions.filters.expenses')}</ToggleButton>
        </ToggleButtonGroup>

        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1.5}
          useFlexGap
          sx={{ flexWrap: 'wrap' }}
        >
          <TextField
            select
            size="small"
            label={t('transactions.fields.category')}
            value={category}
            onChange={(e) => {
              setCategory(e.target.value)
              setPage(0)
            }}
            sx={{ minWidth: 180 }}
          >
            <MenuItem value="">{t('common.all')}</MenuItem>
            {INCOME_EXPENSE_CATEGORIES.map((c) => (
              <MenuItem key={c} value={c}>
                {t(`transactions.category.${c}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            label={t('transactions.fields.status')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value)
              setPage(0)
            }}
            sx={{ minWidth: 160 }}
          >
            <MenuItem value="">{t('common.all')}</MenuItem>
            {INCOME_EXPENSE_APPROVAL_STATUSES.map((s) => (
              <MenuItem key={s} value={s}>
                {t(`transactions.status.${s}`)}
              </MenuItem>
            ))}
          </TextField>
          <DateRangeFields
            from={from}
            to={to}
            onFromChange={(value) => {
              setFrom(value)
              setPage(0)
            }}
            onToChange={(value) => {
              setTo(value)
              setPage(0)
            }}
            fromLabel={t('transactions.fields.from')}
            toLabel={t('transactions.fields.to')}
            issue={dateIssue}
          />
        </Stack>
      </Stack>

      <ListQueryBody
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        onRetry={() => void query.refetch()}
        enabled={filtersValid}
      >
      <ResponsiveTable
        columns={columns}
        rows={rows}
        getRowId={(row) => row.id}
        emptyTitle={t('transactions.emptyTitle')}
        emptyDescription={t('transactions.emptyDescription')}
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
      </ListQueryBody>

      <ConfirmDialog
        open={Boolean(approveId)}
        title={t('transactions.actions.confirmApproveTitle')}
        message={t('transactions.actions.confirmApproveMessage')}
        loading={approveMutation.isPending}
        onConfirm={() => {
          if (approveId) approveMutation.mutate(approveId)
        }}
        onCancel={() => setApproveId(null)}
      />

      <Dialog
        open={Boolean(rejectId)}
        onClose={() => setRejectId(null)}
        fullWidth
        maxWidth="xs"
      >
        <DialogTitle>{t('transactions.actions.confirmRejectTitle')}</DialogTitle>
        <DialogContent>
          <TextField
            sx={{ mt: 1 }}
            label={t('transactions.fields.rejectionReason')}
            {...rejectForm.register('rejectionReason')}
            error={Boolean(rejectForm.formState.errors.rejectionReason)}
            helperText={rejectForm.formState.errors.rejectionReason?.message}
            fullWidth
            multiline
            minRows={2}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setRejectId(null)} disabled={rejectMutation.isPending}>
            {t('common.cancel')}
          </Button>
          <Button
            color="error"
            variant="contained"
            disabled={rejectMutation.isPending}
            onClick={rejectForm.handleSubmit((values) => {
              if (rejectId) rejectMutation.mutate({ id: rejectId, values })
            })}
          >
            {t('transactions.actions.reject')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
