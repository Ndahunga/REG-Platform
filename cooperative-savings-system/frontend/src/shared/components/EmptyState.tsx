import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined'
import { Box, Button, Paper, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

interface EmptyStateProps {
  title?: string
  description?: string
  actionLabel?: string
  onAction?: () => void
  icon?: ReactNode
  phase?: number | string
}

export function EmptyState({
  title,
  description,
  actionLabel,
  onAction,
  icon,
  phase,
}: EmptyStateProps) {
  const { t } = useTranslation()

  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 3, md: 5 },
        textAlign: 'center',
        border: '1px dashed',
        borderColor: 'divider',
        bgcolor: 'background.paper',
      }}
    >
      <Box sx={{ color: 'primary.main', mb: 1.5 }}>
        {icon ?? <InboxOutlinedIcon sx={{ fontSize: 42 }} />}
      </Box>
      <Typography variant="h6" gutterBottom>
        {title ?? t('common.emptyTitle')}
      </Typography>
      {description === '' ? null : (
        <Typography color="text.secondary" sx={{ maxWidth: 420, mx: 'auto', mb: 2 }}>
          {description ?? t('common.emptyDescription')}
        </Typography>
      )}
      {phase != null ? (
        <Typography variant="body2" color="primary" sx={{ mb: onAction ? 2 : 0, fontWeight: 600 }}>
          {t('common.comingPhase', { phase })}
        </Typography>
      ) : null}
      {onAction && actionLabel ? (
        <Button variant="contained" onClick={onAction}>
          {actionLabel}
        </Button>
      ) : null}
    </Paper>
  )
}
