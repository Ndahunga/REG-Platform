import { Alert, Box, Button, FormControlLabel, Stack, Switch, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { selectCanManageFineSettings } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import { fetchFineSettings, updateFineSettings } from '@/shared/api/fineSettings'

/**
 * Late-contribution policy beyond a flat fine: penalties by bands of days late (e.g. 1-5 days 2,000; 6-15 days 5,000),
 * charged per share, and conversion of a contribution left unpaid too long into a monthly charge (e.g. after 30 days,
 * 2% a month on what is still unpaid).
 */
export function FinePolicyCard({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const canEdit = useAppSelector(selectCanManageFineSettings)
  const query = useQuery({ queryKey: ['fine-settings', cooperativeId], queryFn: () => fetchFineSettings(cooperativeId) })
  const [banded, setBanded] = useState(false)
  const [bands, setBands] = useState('')
  const [perShare, setPerShare] = useState(false)
  const [days, setDays] = useState('')
  const [rate, setRate] = useState('')
  const [term, setTerm] = useState('')

  useEffect(() => {
    const s = query.data
    if (!s) return
    setBanded(s.fineMode === 'BANDED')
    setBands(s.penaltyBands ?? '')
    setPerShare(Boolean(s.perShare))
    setDays(s.creditConversionDays ? String(s.creditConversionDays) : '')
    setRate(s.creditConversionRatePercent ? String(Number(s.creditConversionRatePercent)) : '')
    setTerm(s.creditConversionTermMonths ? String(s.creditConversionTermMonths) : '')
  }, [query.data])

  const mutation = useMutation({
    mutationFn: () => {
      const s = query.data!
      return updateFineSettings(cooperativeId, {
        autoFinesEnabled: s.autoFinesEnabled,
        fineMode: banded ? 'BANDED' : s.fineMode === 'BANDED' ? 'FIXED' : s.fineMode,
        baseFineAmount: s.baseFineAmount,
        dailyIncrement: s.dailyIncrement,
        graceDays: s.graceDays,
        penaltyBands: bands.trim(),
        perShare,
        creditConversionDays: days.trim() ? Number(days) : 0,
        creditConversionRatePercent: rate.trim() ? Number(rate) : 0,
        creditConversionTermMonths: term.trim() ? Number(term) : undefined,
      })
    },
    onSuccess: () => {
      enqueueSnackbar(t('fines.policy.saved'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['fine-settings', cooperativeId] })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  if (!query.data) return null
  return (
    <Box sx={{ maxWidth: 560, p: { xs: 2, sm: 2.5 }, mt: 2, border: '1px solid', borderColor: 'divider', borderRadius: 1 }} data-testid="fine-policy">
      <Typography variant="h6" gutterBottom>
        {t('fines.policy.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('fines.policy.description')}
      </Typography>
      <Stack spacing={2}>
        <FormControlLabel
          control={<Switch checked={banded} onChange={(_, v) => setBanded(v)} disabled={!canEdit} />}
          label={t('fines.policy.banded')}
        />
        <TextField
          label={t('fines.policy.bands')}
          value={bands}
          onChange={(e) => setBands(e.target.value)}
          helperText={t('fines.policy.bandsHint')}
          disabled={!canEdit || !banded}
          fullWidth
        />
        <FormControlLabel
          control={<Switch checked={perShare} onChange={(_, v) => setPerShare(v)} disabled={!canEdit} />}
          label={t('fines.policy.perShare')}
        />
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField
            label={t('fines.policy.conversionDays')}
            value={days}
            onChange={(e) => setDays(e.target.value)}
            helperText={t('fines.policy.conversionDaysHint')}
            disabled={!canEdit}
            fullWidth
          />
          <TextField
            label={t('fines.policy.conversionRate')}
            value={rate}
            onChange={(e) => setRate(e.target.value)}
            helperText={t('fines.policy.conversionRateHint')}
            disabled={!canEdit}
            fullWidth
          />
          <TextField
            label={t('fines.policy.conversionTerm')}
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            helperText={t('fines.policy.conversionTermHint')}
            disabled={!canEdit}
            fullWidth
          />
        </Stack>
        {banded && !bands.trim() ? <Alert severity="warning">{t('fines.policy.bandsRequired')}</Alert> : null}
        <Button
          variant="contained"
          sx={{ alignSelf: 'flex-start' }}
          disabled={!canEdit || mutation.isPending || (banded && !bands.trim())}
          onClick={() => mutation.mutate()}
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Box>
  )
}
