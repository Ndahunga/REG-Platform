import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import MenuIcon from '@mui/icons-material/Menu'
import { AppBar, Box, IconButton, Toolbar } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { PwaInstallButton } from '@/pwa/PwaInstallButton'
import { BrandLogo } from '@/shared/components/BrandLogo'
import { CooperativeSelector } from '@/shared/components/CooperativeSelector'
import { LanguageSwitcher } from '@/shared/components/LanguageSwitcher'
import { ThemeSwitcher } from '@/shared/components/ThemeSwitcher'
import { UserMenu } from '@/shared/components/UserMenu'
import { NotificationBell } from '@/features/notifications'
import { ROUTES } from '@/shared/constants/routes'
import { APP_SIDEBAR_WIDTH } from './AppSidebar'

interface AppTopBarProps {
  isMdUp: boolean
  showCooperativeSelector: boolean
  /** True whenever the sidebar is not permanently visible: on mobile, or on desktop while unpinned. */
  sidebarHidden: boolean
  onOpenNav: () => void
  showBackToDashboard: boolean
  /** Logo and back-link target. Defaults to the Saving Scheme dashboard. */
  homePath?: string
  /** Hide the in-app notification bell (the system portal has no notification centre). */
  hideNotifications?: boolean
  /** Hide the account menu (the Saving Scheme layout shows it at the bottom of the sidebar instead). */
  hideUserMenu?: boolean
}

export function AppTopBar({
  isMdUp,
  showCooperativeSelector,
  sidebarHidden,
  onOpenNav,
  showBackToDashboard,
  homePath = ROUTES.dashboard,
  hideNotifications = false,
  hideUserMenu = false,
}: AppTopBarProps) {
  const { t } = useTranslation()

  return (
    <AppBar
      position="sticky"
      elevation={0}
      data-testid="app-top-bar"
      sx={{
        borderBottom: '1px solid',
        borderColor: 'rgba(255,255,255,0.12)',
        bgcolor: '#0A0A0A',
        color: '#FFFFFF',
        zIndex: (theme) => theme.zIndex.drawer + 1,
      }}
    >
      <Toolbar sx={{ gap: { xs: 0.5, md: 1.5 }, minHeight: { xs: 64, sm: 68 } }}>
        {sidebarHidden ? (
          <IconButton
            edge="start"
            aria-label={isMdUp ? t('nav.openNavigation') : t('common.openMenu')}
            onClick={onOpenNav}
            sx={{ minWidth: 44, minHeight: 44, color: '#FFFFFF' }}
          >
            <MenuIcon />
          </IconButton>
        ) : null}

        {!isMdUp && showBackToDashboard ? (
          <IconButton
            component={NavLink}
            to={homePath}
            aria-label={t('common.backToDashboard')}
            sx={{ minWidth: 44, minHeight: 44, color: '#FFFFFF' }}
          >
            <ArrowBackIcon />
          </IconButton>
        ) : null}

        {sidebarHidden ? (
          <Box
            component={NavLink}
            to={homePath}
            aria-label={t('app.name')}
            sx={{
              display: 'flex',
              alignItems: 'center',
              flexShrink: 0,
              minWidth: 'max-content',
              textDecoration: 'none',
              mr: 1,
            }}
          >
            <BrandLogo variant="lockup" size={36} onDark />
          </Box>
        ) : (
          <Box
            aria-hidden
            sx={{ width: 0, minWidth: 0, display: 'none' }}
            data-sidebar-offset={APP_SIDEBAR_WIDTH}
          />
        )}

        <Box sx={{ flex: 1, minWidth: 0 }} />

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: { xs: 0.25, md: 0.5 },
            flexShrink: 0,
            minWidth: 0,
          }}
        >
          {isMdUp ? (
            <>
              <LanguageSwitcher onDark />
              <ThemeSwitcher onDark />
              <PwaInstallButton />
              {showCooperativeSelector ? <CooperativeSelector onDark /> : null}
            </>
          ) : (
            <>
              <ThemeSwitcher onDark />
              <PwaInstallButton />
            </>
          )}

          {hideNotifications ? null : <NotificationBell />}
          {hideUserMenu ? null : <UserMenu />}
        </Box>
      </Toolbar>
    </AppBar>
  )
}
