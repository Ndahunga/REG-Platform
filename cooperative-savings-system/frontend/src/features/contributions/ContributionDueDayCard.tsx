import { Alert, Button, Checkbox, FormControlLabel, Paper, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchContributionDueDay, setContributionDueDay } from '@/shared/api/contributions'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { parseDueDay } from './obligationHelpers'

interface ContributionDueDayCardProps {
  cooperativeId: string
  canManage: boolean
}

/**
 * The monthly contribution due day. Every member of the scheme can read it; only authorized administrators can change
 * it, with an explicit confirmation, and a change applies from next month (earlier obligations keep their due date).
 */
export function ContributionDueDayCard({ cooperativeId, canManage }: ContributionDueDayCardProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [day, setDay] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [submitted, setSubmitted] = useState(false)

  const query = useQuery({
    queryKey: ['contribution-due-day', cooperativeId],
    queryFn: () => fetchContributionDueDay(cooperativeId),
  })

  const mutation = useMutation({
    mutationFn: (dueDay: number) => setContributionDueDay(cooperativeId, { dueDay, confirmed: true }),
    onSuccess: () => {
      enqueueSnackbar(t('contributions.dueDay.saved'), { variant: 'success' })
      setDay('')
      setConfirmed(false)
      setSubmitted(false)
      void queryClient.invalidateQueries({ queryKey: ['contribution-due-day', cooperativeId] })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  const parsed = parseDueDay(day)
  const submit = () => {
    setSubmitted(true)
    if (parsed === null || !confirmed) return
    mutation.mutate(parsed)
  }

  const data = query.data

  return (
    <Paper
      elevation={0}
      sx={{ p: { xs: 2, md: 3 }, mt: 3, border: '1px solid', borderColor: 'divider' }}
      data-testid="contribution-due-day-card"
    >
      <Typography variant="h6" gutterBottom>
        {t('contributions.dueDay.title')}
      </Typography>
      {query.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
      {query.isError ? (
        <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
      ) : null}
      {data ? (
        <Stack spacing={2}>
          <Typography variant="body2" data-testid="contribution-due-day-current">
            {data.currentDueDay != null
              ? t('contributions.dueDay.current', { day: data.currentDueDay })
              : t('contributions.dueDay.notConfigured')}
          </Typography>
          {data.currentDueDay == null ? <Alert severity="info">{t('contributions.dueDay.legacyHint')}</Alert> : null}
          {data.pending ? (
            <Alert severity="info" data-testid="contribution-due-day-pending">
              {t('contributions.dueDay.pending', {
                day: data.pending.dueDay,
                month: `${data.pending.effectiveYear}-${String(data.pending.effectiveMonth).padStart(2, '0')}`,
              })}
            </Alert>
          ) : null}
          <Typography variant="caption" color="text.secondary">
            {t('contributions.dueDay.shortMonthHint')}
          </Typography>
          {canManage ? (
            <Stack spacing={1.5}>
              <TextField
                label={t('contributions.dueDay.newDay')}
                value={day}
                onChange={(e) => setDay(e.target.value)}
                error={submitted && parsed === null}
                helperText={
                  submitted && parsed === null
                    ? t('contributions.dueDay.invalid')
                    : t('contributions.dueDay.appliesNextMonth')
                }
                slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 2 } }}
                sx={{ maxWidth: 240 }}
              />
              <FormControlLabel
                control={<Checkbox checked={confirmed} onChange={(_, v) => setConfirmed(v)} />}
                label={t('contributions.dueDay.confirm')}
              />
              {submitted && !confirmed ? (
                <Typography variant="caption" color="error">
                  {t('contributions.dueDay.confirmRequired')}
                </Typography>
              ) : null}
              <Button
                variant="contained"
                onClick={submit}
                disabled={mutation.isPending}
                sx={{ minHeight: 44, alignSelf: 'flex-start' }}
              >
                {t('contributions.dueDay.save')}
              </Button>
            </Stack>
          ) : null}
        </Stack>
      ) : null}
    </Paper>
  )
}
