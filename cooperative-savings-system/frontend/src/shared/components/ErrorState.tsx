import ErrorOutlinedIcon from '@mui/icons-material/ErrorOutlined'
import { Box, Button, Paper, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

interface ErrorStateProps {
  title?: string
  message?: string
  onRetry?: () => void
}

export function ErrorState({ title, message, onRetry }: ErrorStateProps) {
  const { t } = useTranslation()

  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 3, md: 4 },
        border: '1px solid',
        borderColor: 'error.light',
        bgcolor: 'background.paper',
      }}
    >
      <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>
        <ErrorOutlinedIcon color="error" />
        <Box sx={{ flex: 1 }}>
          <Typography variant="h6" gutterBottom>
            {title ?? t('common.errorTitle')}
          </Typography>
          <Typography color="text.secondary" sx={{ mb: onRetry ? 2 : 0 }}>
            {message ?? t('errors.generic')}
          </Typography>
          {onRetry ? (
            <Button variant="outlined" color="error" onClick={onRetry}>
              {t('common.retry')}
            </Button>
          ) : null}
        </Box>
      </Box>
    </Paper>
  )
}
