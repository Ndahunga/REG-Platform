import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { createRotationConfiguration, deleteRotationConfiguration, fetchRotationConfigurations } from '@/shared/api/rotation'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { localizedApiError } from '@/shared/utils/localizedApiError'
import type { RotationConfiguration } from '@/shared/types/rotation'
import { LoadingState } from '@/shared/components/LoadingState'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import {
  bpsToPercent,
  firstSelectableMonth,
  monthKey,
  parseMonthInput,
  resolveRotatingBps,
  type SplitProblem,
} from './rotationHelpers'

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
  const [rotatingPercent, setRotatingPercent] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<RotationConfiguration | null>(null)

  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'configurations'],
    queryFn: () => fetchRotationConfigurations(cooperativeId),
  })

  const mutation = useMutation({
    mutationFn: (payload: { year: number; month: number; rotatingBps: number }) =>
      createRotationConfiguration(cooperativeId, {
        effectiveYear: payload.year,
        effectiveMonth: payload.month,
        rotatingBps: payload.rotatingBps,
        retainedBps: 10000 - payload.rotatingBps,
      }),
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
  const split = resolveRotatingBps(model, rotatingPercent)
  const splitProblem: SplitProblem | null = 'problem' in split ? split.problem : null
  const rotatingBps = 'bps' in split ? split.bps : null

  const submit = () => {
    setSubmitted(true)
    if (monthProblem || splitProblem || !parsedMonth || rotatingBps === null) return
    mutation.mutate({ year: parsedMonth.year, month: parsedMonth.month, rotatingBps })
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
                  label={t('rotation.config.rotatingPercent')}
                  required
                  value={rotatingPercent}
                  onChange={(e) => setRotatingPercent(e.target.value)}
                  error={submitted && Boolean(splitProblem)}
                  helperText={
                    submitted && splitProblem
                      ? t(`rotation.config.problem.${splitProblem}`)
                      : t('rotation.config.rotatingPercentHint')
                  }
                  slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                />
                <Typography variant="body2" color="text.secondary" data-testid="retained-preview">
                  {rotatingBps === null
                    ? t('rotation.config.retainedPending')
                    : t('rotation.config.retainedPreview', { percent: bpsToPercent(10000 - rotatingBps) })}
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
                {t('rotation.config.rotatingLabel')}: {bpsToPercent(row.rotatingBps)}%
              </Typography>
              <Typography variant="body2">
                {t('rotation.config.retainedLabel')}: {bpsToPercent(row.retainedBps)}%
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
