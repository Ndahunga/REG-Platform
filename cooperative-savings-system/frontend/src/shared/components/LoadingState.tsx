import { Box, CircularProgress, Skeleton, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

interface LoadingStateProps {
  label?: string
  variant?: 'spinner' | 'skeleton'
  rows?: number
}

export function LoadingState({
  label,
  variant = 'spinner',
  rows = 3,
}: LoadingStateProps) {
  const { t } = useTranslation()

  if (variant === 'skeleton') {
    return (
      <Stack spacing={1.5}>
        {Array.from({ length: rows }).map((_, index) => (
          <Skeleton key={index} variant="rounded" height={56} animation="wave" />
        ))}
      </Stack>
    )
  }

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 2,
        py: 6,
      }}
    >
      <CircularProgress size={36} />
      <Typography color="text.secondary">{label ?? t('common.loading')}</Typography>
    </Box>
  )
}
