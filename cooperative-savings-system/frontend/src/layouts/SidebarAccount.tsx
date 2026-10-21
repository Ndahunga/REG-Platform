import LogoutIcon from '@mui/icons-material/Logout'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlineOutlined'
import {
  Avatar,
  Box,
  ButtonBase,
  Divider,
  IconButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Tooltip,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { ROUTES } from '@/shared/constants/routes'
import { useLogout } from '@/shared/hooks/useLogout'
import { primaryRole } from '@/shared/types/auth'

interface SidebarAccountProps {
  /** Invoked after navigating from the menu (e.g. close the mobile drawer). */
  onNavigate?: () => void
}

/**
 * Account area anchored to the bottom of the sidebar: identity (opens the account menu) and Sign out.
 * Uses the same sign-out flow as the top-bar menu (`useLogout`).
 */
export function SidebarAccount({ onNavigate }: SidebarAccountProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAppSelector((s) => s.auth.user)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const { logout, loggingOut } = useLogout()
  const [anchor, setAnchor] = useState<null | HTMLElement>(null)

  const name = user?.fullName?.trim() || user?.username || user?.firstName || ''
  const roleLabel = !user
    ? ''
    : isSuperAdmin
      ? t('roles.superAdminBadge')
      : t(`roles.${primaryRole(user.roles ?? [])}`, { defaultValue: t('roles.memberBadge') })
  const displayName = name || t('nav.profile')

  const handleLogout = () => {
    if (loggingOut) return
    setAnchor(null)
    onNavigate?.()
    void logout()
  }

  return (
    <Box data-testid="sidebar-account" sx={{ flexShrink: 0 }}>
      <Divider />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, p: 1 }}>
        <ButtonBase
          onClick={(e) => setAnchor(e.currentTarget)}
          aria-haspopup="menu"
          aria-expanded={Boolean(anchor)}
          aria-label={t('nav.accountMenu', { name: displayName })}
          data-testid="sidebar-account-button"
          sx={{
            flex: 1,
            minWidth: 0,
            minHeight: 48,
            gap: 1.25,
            px: 1,
            borderRadius: 2,
            justifyContent: 'flex-start',
            textAlign: 'left',
            '&:hover, &.Mui-focusVisible': { bgcolor: 'action.hover' },
          }}
        >
          <Avatar sx={{ width: 34, height: 34, bgcolor: 'primary.main', fontSize: 14, flexShrink: 0 }}>
            {(displayName[0] || 'U').toUpperCase()}
          </Avatar>
          <Box sx={{ minWidth: 0 }}>
            <Typography noWrap sx={{ fontWeight: 600, fontSize: '0.875rem', lineHeight: 1.25 }}>
              {displayName}
            </Typography>
            {roleLabel ? (
              <Typography noWrap variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                {roleLabel}
              </Typography>
            ) : null}
          </Box>
        </ButtonBase>
        <Tooltip title={t('common.logout')}>
          <span>
            <IconButton
              onClick={handleLogout}
              disabled={loggingOut}
              aria-label={t('common.logout')}
              data-testid="sidebar-signout"
              sx={{ minWidth: 44, minHeight: 44 }}
            >
              <LogoutIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
      </Box>
      <Menu
        anchorEl={anchor}
        open={Boolean(anchor)}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'top', horizontal: 'left' }}
        transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        <MenuItem
          onClick={() => {
            setAnchor(null)
            onNavigate?.()
            navigate(ROUTES.profile)
          }}
        >
          <ListItemIcon>
            <PersonOutlineIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('nav.profile')}</ListItemText>
        </MenuItem>
        <Divider />
        <MenuItem onClick={handleLogout} disabled={loggingOut}>
          <ListItemIcon>
            <LogoutIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('common.logout')}</ListItemText>
        </MenuItem>
      </Menu>
    </Box>
  )
}
