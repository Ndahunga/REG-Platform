import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { createRotationConfiguration, deleteRotationConfiguration, fetchRotationConfigurations } from '@/shared/api/rotation'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { localizedApiError } from '@/shared/utils/localizedApiError'
import type { RotationConfiguration } from '@/shared/types/rotation'
import { LoadingState } from '@/shared/components/LoadingState'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import { bpsToPercent, firstSelectableMonth, monthKey, parseMonthInput } from './rotationHelpers'

type SplitProblem = 'required' | 'range'

interface RotationConfigPanelProps {
  cooperativeId: string
  model: SchemeOperatingModel
  canManage: boolean
}

/**
 * Effective-dated split of contributions. History is read-only: a saved configuration is never edited, a new one takes
 * effect from a later month that has not begun. ROTATING is always 100% rotating; HYBRID splits rotating and retained.
 */
export function RotationConfigPanel({ cooperativeId, model, canManage }: RotationConfigPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const minMonth = firstSelectableMonth()
  const [month, setMonth] = useState(minMonth)
  const [rotatingAmount, setRotatingAmount] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<RotationConfiguration | null>(null)

  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'configurations'],
    queryFn: () => fetchRotationConfigurations(cooperativeId),
  })

  const schemeQuery = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId),
  })
  const unit = Number(schemeQuery.data?.monthlyContributionAmount ?? 0)

  const mutation = useMutation({
    mutationFn: (payload: { year: number; month: number; rotatingAmount: number | null }) =>
      createRotationConfiguration(
        cooperativeId,
        payload.rotatingAmount === null
          ? { effectiveYear: payload.year, effectiveMonth: payload.month, rotatingBps: 10000, retainedBps: 0 }
          : { effectiveYear: payload.year, effectiveMonth: payload.month, rotatingUnitAmount: payload.rotatingAmount },
      ),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.config.saved'), { variant: 'success' })
      setSubmitted(false)
      void queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId, 'configurations'] })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  const removal = useMutation({
    mutationFn: (id: string) => deleteRotationConfiguration(cooperativeId, id),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.config.deleted'), { variant: 'success' })
      setDeleteTarget(null)
      void queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId, 'configurations'] })
    },
    onError: (error) => {
      setDeleteTarget(null)
      enqueueSnackbar(localizedApiError(t, error, 'rotation.config.errorCodes'), { variant: 'error' })
    },
  })

  const parsedMonth = parseMonthInput(month)
  const monthProblem = !parsedMonth ? 'required' : month < minMonth ? 'future' : null
  // Hybrid: the officers state, in RWF per share, how much of each contribution rotates; the rest is kept as capital.
  const amount = Number(rotatingAmount.replace(/[,\s]/g, ''))
  const splitProblem: SplitProblem | null =
    model === 'ROTATING'
      ? null
      : !rotatingAmount.trim() || !Number.isFinite(amount)
        ? 'required'
        : amount <= 0 || (unit > 0 && amount >= unit)
          ? 'range'
          : null
  const retainedAmount = splitProblem || model === 'ROTATING' ? null : unit - amount

  const submit = () => {
    setSubmitted(true)
    if (monthProblem || splitProblem || !parsedMonth) return
    mutation.mutate({ year: parsedMonth.year, month: parsedMonth.month, rotatingAmount: model === 'ROTATING' ? null : amount })
  }

  const rows = query.data ?? []

  return (
    <Stack spacing={2.5}>
      <Alert severity="info">{t('rotation.config.help')}</Alert>

      {canManage ? (
        <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
          <Typography variant="h6" gutterBottom>
            {t('rotation.config.newTitle')}
          </Typography>
          <Stack spacing={2}>
            <TextField
              label={t('rotation.config.effectiveFrom')}
              type="month"
              required
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              error={submitted && Boolean(monthProblem)}
              helperText={
                submitted && monthProblem
                  ? t('rotation.config.monthInvalid')
                  : t('rotation.config.effectiveFromHint', { month: minMonth })
              }
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: minMonth } }}
            />
            {model === 'ROTATING' ? (
              <Typography variant="body2">{t('rotation.config.rotatingFixed')}</Typography>
            ) : (
              <>
                <TextField
                  label={t('rotation.config.rotatingAmount')}
                  required
                  value={rotatingAmount}
                  onChange={(e) => setRotatingAmount(e.target.value)}
                  error={submitted && Boolean(splitProblem)}
                  helperText={
                    submitted && splitProblem
                      ? t(`rotation.config.amountProblem.${splitProblem}`, { unit: unit.toLocaleString() })
                      : t('rotation.config.rotatingAmountHint', { unit: unit.toLocaleString() })
                  }
                  slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                />
                <Typography variant="body2" color="text.secondary" data-testid="retained-preview">
                  {retainedAmount === null
                    ? t('rotation.config.retainedPending')
                    : t('rotation.config.retainedAmountPreview', {
                        amount: retainedAmount.toLocaleString(),
                        unit: unit.toLocaleString(),
                      })}
                </Typography>
              </>
            )}
            <Box>
              <Button variant="contained" onClick={submit} disabled={mutation.isPending}>
                {t('rotation.config.save')}
              </Button>
            </Box>
          </Stack>
        </Paper>
      ) : null}

      <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
        <Typography variant="h6" gutterBottom>
          {t('rotation.config.historyTitle')}
        </Typography>
        {query.isLoading ? <LoadingState /> : null}
        {query.isSuccess && rows.length === 0 ? (
          <Typography color="text.secondary">{t('rotation.config.none')}</Typography>
        ) : null}
        <Stack spacing={1}>
          {rows.map((row) => (
            <Box
              key={row.id}
              data-testid="rotation-config-row"
              sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'baseline' }}
            >
              <Typography sx={{ fontWeight: 600 }}>{monthKey(row.effectiveYear, row.effectiveMonth)}</Typography>
              <Typography variant="body2">
                {t('rotation.config.rotatingLabel')}:{' '}
                {row.rotatingUnitAmount != null
                  ? `${Number(row.rotatingUnitAmount).toLocaleString()} RWF`
                  : `${bpsToPercent(row.rotatingBps)}%`}
              </Typography>
              <Typography variant="body2">
                {t('rotation.config.retainedLabel')}:{' '}
                {row.rotatingUnitAmount != null && unit > 0
                  ? `${(unit - Number(row.rotatingUnitAmount)).toLocaleString()} RWF`
                  : `${bpsToPercent(row.retainedBps)}%`}
              </Typography>
              {canManage && row.canDelete ? (
                <Button
                  size="small"
                  color="error"
                  aria-label={t('rotation.config.deleteNamed', { month: monthKey(row.effectiveYear, row.effectiveMonth) })}
                  onClick={() => setDeleteTarget(row)}
                >
                  {t('rotation.config.delete')}
                </Button>
              ) : null}
              {row.canDelete === false && row.deletionBlockedReason ? (
                <Typography variant="caption" color="text.secondary">
                  {t(`rotation.config.errorCodes.${row.deletionBlockedReason}`)}
                </Typography>
              ) : null}
            </Box>
          ))}
        </Stack>
      </Paper>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title={t('rotation.config.deleteTitle')}
        message={t('rotation.config.deleteMessage')}
        confirmLabel={t('rotation.config.deleteConfirm')}
        loading={removal.isPending}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => deleteTarget && removal.mutate(deleteTarget.id)}
      />
    </Stack>
  )
}
