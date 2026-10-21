import {
  Alert,
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  FormGroup,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage } from '@/shared/api/client'
import { fetchDashboardSummary } from '@/shared/api/dashboard'
import { confirmPayout, previewPayout } from '@/shared/api/payouts'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { PayoutLine, PayoutRun } from '@/shared/types/payout'
import { payoutLineMemberName } from '@/shared/types/payout'
import { formatMoney } from '@/shared/utils/formatMoney'
import {
  payoutPreviewDefaults,
  payoutPreviewSchema,
  toPayoutPreviewPayload,
  type PayoutPreviewFormValues,
} from './payoutFormSchemas'
import { formatPayoutPercentage, sumPayoutAmounts } from './payoutHelpers'

interface PayoutNewPanelProps {
  cooperativeId: string
}

export function PayoutNewPanel({ cooperativeId }: PayoutNewPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [preview, setPreview] = useState<PayoutRun | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)

  const fundsQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const form = useForm<PayoutPreviewFormValues>({
    defaultValues: payoutPreviewDefaults(),
    resolver: yupResolver(payoutPreviewSchema),
  })

  const availableFunds = fundsQuery.data?.availableGroupFunds
  const currency = fundsQuery.data?.currency || preview?.currency || 'RWF'

  const previewMutation = useMutation({
    mutationFn: (values: PayoutPreviewFormValues) =>
      previewPayout(cooperativeId, toPayoutPreviewPayload(values)),
    onSuccess: (run) => {
      setPreview(run)
      enqueueSnackbar(t('payouts.preview.success'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['payouts'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const confirmMutation = useMutation({
    mutationFn: () => confirmPayout(cooperativeId, preview!.id),
    onSuccess: (run) => {
      enqueueSnackbar(t('payouts.actions.confirmSuccess'), { variant: 'success' })
      setConfirmOpen(false)
      setPreview(run)
      void queryClient.invalidateQueries({ queryKey: ['payouts'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      void queryClient.invalidateQueries({ queryKey: ['ledger'] })
      navigate(ROUTES.payoutDetail(run.id))
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
          formatMoney(row.eligibleContributionAmount, {
            currency: row.currency || currency,
          }),
      },
      {
        id: 'percentage',
        label: t('payouts.fields.percentage'),
        render: (row) => formatPayoutPercentage(row.percentage),
      },
      {
        id: 'payout',
        label: t('payouts.fields.payoutAmount'),
        render: (row) =>
          formatMoney(row.payoutAmount, { currency: row.currency || currency }),
      },
    ],
    [currency, t],
  )

  const lines = preview?.lines ?? []
  const linesTotal = sumPayoutAmounts(lines)
  const rootError =
    form.formState.errors.root?.message ||
    (form.formState.errors as { ''?: { message?: string } })['']?.message

  return (
    <Box>
      <Paper
        elevation={0}
        sx={{
          p: { xs: 2, sm: 2.5 },
          mb: 2.5,
          border: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Typography variant="h6" sx={{ mb: 0.5 }}>
          {t('payouts.preview.title')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('payouts.preview.description')}
        </Typography>

        {availableFunds != null ? (
          <Alert severity="info" sx={{ mb: 2 }}>
            {t('payouts.preview.availableFundHint', {
              amount: formatMoney(availableFunds, { currency }),
            })}
          </Alert>
        ) : null}

        <Stack
          component="form"
          spacing={2}
          onSubmit={form.handleSubmit((values) => previewMutation.mutate(values))}
        >
          <TextField
            label={t('payouts.fields.name')}
            {...form.register('name')}
            error={Boolean(form.formState.errors.name)}
            helperText={form.formState.errors.name?.message}
            fullWidth
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label={t('payouts.fields.periodFrom')}
              type="date"
              {...form.register('periodFrom')}
              error={Boolean(form.formState.errors.periodFrom)}
              helperText={form.formState.errors.periodFrom?.message}
              fullWidth
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              label={t('payouts.fields.periodTo')}
              type="date"
              {...form.register('periodTo')}
              error={Boolean(form.formState.errors.periodTo)}
              helperText={form.formState.errors.periodTo?.message}
              fullWidth
              slotProps={{ inputLabel: { shrink: true } }}
            />
          </Stack>

          <FormGroup row>
            <Controller
              name="includeRegular"
              control={form.control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                    />
                  }
                  label={t('payouts.fields.includeRegular')}
                />
              )}
            />
            <Controller
              name="includeSpecial"
              control={form.control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                    />
                  }
                  label={t('payouts.fields.includeSpecial')}
                />
              )}
            />
          </FormGroup>

          {(form.formState.errors.includeRegular ||
            form.formState.errors.includeSpecial ||
            rootError) && (
            <Alert severity="error">
              {rootError ||
                form.formState.errors.includeRegular?.message ||
                form.formState.errors.includeSpecial?.message ||
                t('payouts.preview.includeRequired')}
            </Alert>
          )}

          <TextField
            label={t('payouts.fields.payoutPoolAmount')}
            {...form.register('payoutPoolAmount')}
            error={Boolean(form.formState.errors.payoutPoolAmount)}
            helperText={
              form.formState.errors.payoutPoolAmount?.message ||
              t('payouts.preview.poolOptionalHint')
            }
            fullWidth
          />
          <TextField
            label={t('payouts.fields.notes')}
            {...form.register('notes')}
            error={Boolean(form.formState.errors.notes)}
            helperText={form.formState.errors.notes?.message}
            fullWidth
            multiline
            minRows={2}
          />

          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <Button type="submit" variant="contained" disabled={previewMutation.isPending}>
              {t('payouts.preview.submit')}
            </Button>
            {preview ? (
              <Button
                variant="outlined"
                onClick={() => {
                  setPreview(null)
                  form.reset(payoutPreviewDefaults())
                }}
              >
                {t('payouts.preview.reset')}
              </Button>
            ) : null}
          </Stack>
        </Stack>
      </Paper>

      {previewMutation.isPending && !preview ? (
        <LoadingState variant="skeleton" rows={4} />
      ) : null}

      {preview ? (
        <Paper
          elevation={0}
          sx={{
            p: { xs: 2, sm: 2.5 },
            border: '1px solid',
            borderColor: 'divider',
          }}
        >
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={1.5}
            sx={{ mb: 2, justifyContent: 'space-between', alignItems: { sm: 'center' } }}
          >
            <Box>
              <Typography variant="h6">{t('payouts.preview.resultTitle')}</Typography>
              <Typography variant="body2" color="text.secondary">
                {t('payouts.preview.resultSummary', {
                  pool: formatMoney(preview.payoutPoolAmount, {
                    currency: preview.currency || currency,
                  }),
                  eligible: formatMoney(preview.totalEligibleContributions ?? 0, {
                    currency: preview.currency || currency,
                  }),
                  total: formatMoney(linesTotal, {
                    currency: preview.currency || currency,
                  }),
                })}
              </Typography>
            </Box>
            {preview.status === 'PREVIEWED' ? (
              <Button variant="contained" onClick={() => setConfirmOpen(true)}>
                {t('payouts.actions.confirm')}
              </Button>
            ) : (
              <Button
                variant="outlined"
                onClick={() => navigate(ROUTES.payoutDetail(preview.id))}
              >
                {t('payouts.actions.viewDetail')}
              </Button>
            )}
          </Stack>

          <ResponsiveTable
            columns={columns}
            rows={lines}
            getRowId={(row) => row.id}
            emptyTitle={t('payouts.preview.emptyLinesTitle')}
            emptyDescription={t('payouts.preview.emptyLinesDescription')}
          />
        </Paper>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title={t('payouts.actions.confirmTitle')}
        message={t('payouts.actions.confirmMessage', {
          amount: formatMoney(preview?.payoutPoolAmount ?? 0, {
            currency: preview?.currency || currency,
          }),
        })}
        loading={confirmMutation.isPending}
        onConfirm={() => confirmMutation.mutate()}
        onCancel={() => setConfirmOpen(false)}
      />
    </Box>
  )
}
