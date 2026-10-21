import { Alert, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

export function TrialPricingNote() {
  const { t } = useTranslation()

  return (
    <Alert severity="info" data-testid="trial-pricing-note">
      <Typography variant="subtitle2">{t('signup.trialTitle')}</Typography>
      <Typography variant="body2">{t('signup.trialBody')}</Typography>
      <Stack spacing={0.25} sx={{ mt: 1 }}>
        <Typography variant="body2">{t('signup.afterTrial')}</Typography>
        <Typography variant="body2">{t('signup.monthlyPrice')}</Typography>
        <Typography variant="body2">{t('signup.annualPrice')}</Typography>
        <Typography variant="body2" sx={{ mt: 0.5 }}>
          {t('signup.manageLater')}
        </Typography>
      </Stack>
    </Alert>
  )
}
