import { Alert, Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import {
  cancelOperatingModelChange,
  fetchOperatingModelChanges,
  scheduleOperatingModelChange,
} from '@/shared/api/operatingModelChanges'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'

const MODELS: SchemeOperatingModel[] = ['ACCUMULATING', 'ROTATING', 'HYBRID']

function nextMonth(): string {
  const d = new Date()
  d.setMonth(d.getMonth() + 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** Change the scheme's operating model from a month that has not begun (a running scheme's rules can change). */
export function ModelChangeSection({ cooperativeId, current, canManage }: { cooperativeId: string; current: SchemeOperatingModel; canManage: boolean }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [model, setModel] = useState<SchemeOperatingModel | ''>('')
  const [month, setMonth] = useState(nextMonth())
  const [reason, setReason] = useState('')
  const query = useQuery({
    queryKey: ['cooperatives', cooperativeId, 'model-changes'],
    queryFn: () => fetchOperatingModelChanges(cooperativeId),
  })
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['cooperatives', cooperativeId] })
  const onError = (error: unknown) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
  const schedule = useMutation({
    mutationFn: () => {
      const [y, m] = month.split('-').map(Number)
      return scheduleOperatingModelChange(cooperativeId, { operatingModel: model as SchemeOperatingModel, effectiveYear: y, effectiveMonth: m, reason })
    },
    onSuccess: () => {
      enqueueSnackbar(t('operatingModel.change.scheduled'), { variant: 'success' })
      setModel('')
      setReason('')
      void refresh()
    },
    onError,
  })
  const cancel = useMutation({
    mutationFn: (id: string) => cancelOperatingModelChange(cooperativeId, id),
    onSuccess: () => void refresh(),
    onError,
  })
  const scheduled = (query.data ?? []).find((c) => c.status === 'SCHEDULED')
  return (
    <Box sx={{ mt: 1 }} data-testid="model-change">
      {scheduled ? (
        <Alert
          severity="info"
          action={
            canManage ? (
              <Button color="inherit" size="small" onClick={() => cancel.mutate(scheduled.id)}>
                {t('operatingModel.change.cancel')}
              </Button>
            ) : undefined
          }
        >
          {t('operatingModel.change.pending', {
            model: t(`operatingModel.options.${scheduled.toModel}.title`),
            month: `${scheduled.effectiveYear}-${String(scheduled.effectiveMonth).padStart(2, '0')}`,
          })}
        </Alert>
      ) : canManage ? (
        <Stack spacing={1.5}>
          <Typography variant="subtitle2">{t('operatingModel.change.title')}</Typography>
          <Typography variant="body2" color="text.secondary">
            {t('operatingModel.change.help')}
          </Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField select label={t('operatingModel.change.newModel')} value={model} onChange={(e) => setModel(e.target.value as SchemeOperatingModel)} fullWidth>
              {MODELS.filter((m) => m !== current).map((m) => (
                <MenuItem key={m} value={m}>
                  {t(`operatingModel.options.${m}.title`)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label={t('operatingModel.change.from')}
              type="month"
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: nextMonth() } }}
              fullWidth
            />
          </Stack>
          <TextField label={t('operatingModel.change.reason')} value={reason} onChange={(e) => setReason(e.target.value)} fullWidth />
          <Box>
            <Button variant="outlined" disabled={!model || !month || schedule.isPending} onClick={() => schedule.mutate()}>
              {t('operatingModel.change.submit')}
            </Button>
          </Box>
        </Stack>
      ) : null}
    </Box>
  )
}
