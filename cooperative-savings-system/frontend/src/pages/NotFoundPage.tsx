import { Button, Paper, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { ROUTES } from '@/shared/constants/routes'

export function NotFoundPage() {
  const { t } = useTranslation()

  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 3, md: 5 },
        textAlign: 'center',
        border: '1px solid',
        borderColor: 'divider',
        maxWidth: 520,
        mx: 'auto',
        mt: { xs: 4, md: 8 },
      }}
    >
      <Typography variant="h3" sx={{ fontFamily: 'var(--font-brand)', mb: 1 }}>
        404
      </Typography>
      <Typography variant="h6" gutterBottom>
        {t('errors.notFound')}
      </Typography>
      <Button component={RouterLink} to={ROUTES.dashboard} variant="contained" sx={{ mt: 2 }}>
        {t('nav.dashboard')}
      </Button>
    </Paper>
  )
}
