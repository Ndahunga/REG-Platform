import AccountCircleIcon from '@mui/icons-material/AccountCircle'
import LogoutIcon from '@mui/icons-material/Logout'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlineOutlined'
import {
  Avatar,
  Box,
  Button,
  Divider,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { useLogout } from '@/shared/hooks/useLogout'
import { usePortalRoutes } from '@/shared/constants/portal'
import { primaryRole } from '@/shared/types/auth'

export function UserMenu() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const routes = usePortalRoutes()
  const user = useAppSelector((s) => s.auth.user)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const role = primaryRole(user?.roles ?? [])
  const badgeLabel = isSuperAdmin
    ? t('roles.superAdminBadge')
    : t(`roles.${role}`, { defaultValue: t('roles.memberBadge') })
  const [anchor, setAnchor] = useState<null | HTMLElement>(null)
  const { logout, loggingOut } = useLogout()

  const label = user?.username || user?.firstName || t('nav.profile')

  const handleLogout = () => {
    if (loggingOut) return
    setAnchor(null)
    void logout()
  }

  return (
    <>
      <Button
        color="inherit"
        onClick={(e) => setAnchor(e.currentTarget)}
        aria-haspopup="menu"
        aria-label={t('nav.profile')}
        sx={{
          minHeight: 44,
          minWidth: 0,
          maxWidth: { xs: 148, sm: 220 },
          flexShrink: 0,
          px: 1,
          textTransform: 'none',
          color: '#FFFFFF',
          justifyContent: 'flex-start',
          gap: 1,
        }}
      >
        <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 14, flexShrink: 0 }}>
          {(label[0] || 'U').toUpperCase()}
        </Avatar>
        <Box sx={{ minWidth: 0, textAlign: 'left' }}>
          <Typography
            component="span"
            sx={{
              display: 'block',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontWeight: 600,
              fontSize: '0.875rem',
              lineHeight: 1.25,
            }}
          >
            {label}
          </Typography>
          <Typography
            component="span"
            sx={{
              display: { xs: 'none', sm: 'block' },
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontSize: '0.7rem',
              lineHeight: 1.2,
              opacity: 0.72,
              fontWeight: 500,
            }}
          >
            {badgeLabel}
          </Typography>
        </Box>
      </Button>
      <Menu
        anchorEl={anchor}
        open={Boolean(anchor)}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        <Box sx={{ px: 2, py: 1.25, minWidth: 200 }}>
          <Typography sx={{ fontWeight: 700 }} noWrap>
            {label}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {badgeLabel}
          </Typography>
        </Box>
        <Divider />
        <MenuItem
          onClick={() => {
            setAnchor(null)
            navigate(routes.profile)
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
        {!user ? (
          <MenuItem disabled>
            <ListItemIcon>
              <AccountCircleIcon fontSize="small" />
            </ListItemIcon>
            <ListItemText>—</ListItemText>
          </MenuItem>
        ) : null}
      </Menu>
    </>
  )
}
