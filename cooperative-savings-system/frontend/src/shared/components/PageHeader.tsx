import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import { Box, Button, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { selectIsCooperativeAdmin } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { ROUTES } from '@/shared/constants/routes'

interface PageHeaderProps {
  title: string
  description?: string
  actions?: ReactNode
  /** Explicit back destination. Members get dashboard by default on non-dashboard pages. */
  backTo?: string
  backLabel?: string
  hideBack?: boolean
}

export function PageHeader({
  title,
  description,
  actions,
  backTo,
  backLabel,
  hideBack,
}: PageHeaderProps) {
  const { t } = useTranslation()
  const location = useLocation()
  const isAdmin = useAppSelector(selectIsCooperativeAdmin)
  const onDashboard = location.pathname === ROUTES.dashboard

  const resolvedBackTo = hideBack
    ? undefined
    : backTo ?? (!isAdmin && !onDashboard ? ROUTES.dashboard : undefined)

  const resolvedLabel =
    backLabel ??
    (resolvedBackTo === ROUTES.dashboard
      ? t('common.backToDashboard')
      : t('common.back'))

  return (
    <Box sx={{ mb: 3 }}>
      {resolvedBackTo ? (
        <Button
          component={RouterLink}
          to={resolvedBackTo}
          startIcon={<ArrowBackIcon />}
          sx={{ mb: 1, ml: -1, minHeight: 40, color: 'text.secondary' }}
        >
          {resolvedLabel}
        </Button>
      ) : null}

      <Box
        sx={{
          display: 'flex',
          flexDirection: { xs: 'column', sm: 'row' },
          alignItems: { xs: 'stretch', sm: 'flex-start' },
          justifyContent: 'space-between',
          gap: 2,
        }}
      >
        <Box>
          <Typography variant="h4" component="h1" gutterBottom={Boolean(description)}>
            {title}
          </Typography>
          {description ? (
            <Typography variant="body1" color="text.secondary" sx={{ maxWidth: 640 }}>
              {description}
            </Typography>
          ) : null}
        </Box>
        {actions ? <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>{actions}</Box> : null}
      </Box>
    </Box>
  )
}
