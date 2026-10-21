import { Alert, Paper, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { primaryRole } from '@/shared/types/auth'
import { normalizeRoleInCooperative } from '@/shared/types/member'

interface RoleDutiesNoteProps {
  /** JWT roles or a single cooperative office code. */
  role?: string | null
  roles?: string[]
  compact?: boolean
  variant?: 'alert' | 'card'
}

export function dutyRoleCode(role?: string | null, roles?: string[]): string {
  if (roles?.length) {
    return primaryRole(roles)
  }
  if (!role) return 'MEMBER'
  if (role === 'SUPER_ADMIN') return 'SUPER_ADMIN'
  return normalizeRoleInCooperative(role)
}

export function RoleDutiesNote({
  role,
  roles,
  compact = false,
  variant = 'alert',
}: RoleDutiesNoteProps) {
  const { t } = useTranslation()
  const code = dutyRoleCode(role, roles)
  const duties = t(`roles.duties.${code}`, { defaultValue: '' })
  if (!duties) return null

  const title = t(`members.roles.${code}`, {
    defaultValue: t(`roles.${code}`, { defaultValue: code }),
  })

  if (variant === 'card') {
    return (
      <Paper
        elevation={0}
        sx={{
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          borderLeft: '4px solid',
          borderLeftColor: 'secondary.main',
        }}
      >
        <Typography
          variant="overline"
          color="text.secondary"
          sx={{ fontWeight: 700, letterSpacing: 0.6 }}
        >
          {t('roles.yourRole')}
        </Typography>
        <Typography variant="h6" sx={{ fontWeight: 700, mt: 0.25, mb: 0.75 }}>
          {title}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {duties}
        </Typography>
      </Paper>
    )
  }

  return (
    <Alert severity="info" sx={{ alignItems: 'flex-start' }}>
      {compact ? null : (
        <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 0.5 }}>
          {t('roles.dutiesTitle', { role: title })}
        </Typography>
      )}
      <Typography variant="body2">{duties}</Typography>
    </Alert>
  )
}
