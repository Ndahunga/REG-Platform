import { Box, Divider, Drawer, useMediaQuery, useTheme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation } from 'react-router-dom'
import { selectIsCooperativeAdmin, selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { selectSidebarPinned, setSidebarPinned } from '@/app/store/uiSlice'
import { fetchUnreadCount } from '@/shared/api/notifications'
import { AppFooter } from '@/shared/components/AppFooter'
import { CooperativeSelector } from '@/shared/components/CooperativeSelector'
import { LanguageSwitcher } from '@/shared/components/LanguageSwitcher'
import { OfflineBanner } from '@/shared/components/OfflineBanner'
import { SupportDock } from '@/shared/components/SupportDock'
import { ThemeSwitcher } from '@/shared/components/ThemeSwitcher'
import { NOTIFICATION_POLL_MS } from '@/features/notifications'
import { SubscriptionBanner } from '@/features/subscription/SubscriptionBanner'
import { ROUTES } from '@/shared/constants/routes'
import { AppSidebar, APP_SIDEBAR_WIDTH } from './AppSidebar'
import { AppTopBar } from './AppTopBar'
import { getAppSidebarSections } from './navItems'
import { SidebarAccount } from './SidebarAccount'

export function AppLayout() {
  const { t } = useTranslation()
  const theme = useTheme()
  const dispatch = useAppDispatch()
  const isMdUp = useMediaQuery(theme.breakpoints.up('md'))
  const location = useLocation()
  const userRoles = useAppSelector((s) => s.auth.user?.roles ?? [])
  const isCoopAdmin = useAppSelector(selectIsCooperativeAdmin)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const sidebarPinned = useAppSelector(selectSidebarPinned)
  const isMember = !isCoopAdmin && !isSuperAdmin
  const accessToken = useAppSelector((s) => s.auth.accessToken)
  /** Temporary drawer: mobile always, and desktop while the sidebar is unpinned. */
  const [drawerOpen, setDrawerOpen] = useState(false)
  const showCooperativeSelector = isCoopAdmin || isMember

  // The pin preference only exists on desktop; mobile never has a permanent sidebar.
  const showPermanentSidebar = isMdUp && sidebarPinned
  const sidebarHidden = !showPermanentSidebar

  const unreadQuery = useQuery({
    queryKey: ['notifications-unread-count'],
    queryFn: fetchUnreadCount,
    enabled: Boolean(accessToken),
    refetchInterval: NOTIFICATION_POLL_MS,
    refetchOnWindowFocus: true,
    retry: 1,
  })
  const unreadCount = unreadQuery.data ?? 0

  useEffect(() => {
    setDrawerOpen(false)
  }, [location.pathname, location.search])

  // Re-pinning (or growing to a pinned desktop layout) must never leave a stale overlay open.
  useEffect(() => {
    if (showPermanentSidebar) setDrawerOpen(false)
  }, [showPermanentSidebar])

  const dark = theme.palette.mode === 'dark'
  const sidebarSections = useMemo(() => getAppSidebarSections(userRoles), [userRoles])

  const closeDrawer = () => setDrawerOpen(false)

  const mobileDrawerExtras = (
    <Box sx={{ px: 2, py: 1.5, display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <LanguageSwitcher />
      <ThemeSwitcher />
      {showCooperativeSelector ? <CooperativeSelector /> : null}
    </Box>
  )

  return (
    <Box sx={{ display: 'flex', minHeight: '100dvh', bgcolor: 'background.default' }}>
      {/* Desktop permanent sidebar (pinned) */}
      {showPermanentSidebar ? (
        <Box
          component="aside"
          data-testid="app-sidebar-desktop"
          sx={{
            width: APP_SIDEBAR_WIDTH,
            flexShrink: 0,
            position: 'sticky',
            top: 0,
            alignSelf: 'flex-start',
            height: '100dvh',
          }}
        >
          <AppSidebar
            userRoles={userRoles}
            unreadCount={unreadCount}
            onDarkBrand={dark}
            sections={sidebarSections}
            footer={<SidebarAccount />}
            pinControl={{ pinned: true, onToggle: () => dispatch(setSidebarPinned(false)) }}
          />
        </Box>
      ) : null}

      {/* Temporary drawer: mobile, or desktop while the sidebar is unpinned */}
      {sidebarHidden ? (
        <Drawer
          variant="temporary"
          open={drawerOpen}
          onClose={closeDrawer}
          ModalProps={{ keepMounted: !isMdUp }}
          aria-label={t('nav.sidebarAria')}
          sx={{
            // On desktop the top bar would otherwise cover the drawer header, which holds the Pin action.
            ...(isMdUp ? { zIndex: (muiTheme) => muiTheme.zIndex.drawer + 2 } : {}),
            '& .MuiDrawer-paper': {
              width: APP_SIDEBAR_WIDTH,
              boxSizing: 'border-box',
            },
          }}
        >
          <AppSidebar
            userRoles={userRoles}
            unreadCount={unreadCount}
            onDarkBrand={dark}
            onNavigate={closeDrawer}
            sections={sidebarSections}
            footer={<SidebarAccount onNavigate={closeDrawer} />}
            navExtras={
              !isMdUp ? (
                <>
                  <Divider sx={{ my: 1 }} />
                  {mobileDrawerExtras}
                </>
              ) : undefined
            }
            pinControl={
              isMdUp
                ? {
                    pinned: false,
                    onToggle: () => {
                      dispatch(setSidebarPinned(true))
                      closeDrawer()
                    },
                  }
                : undefined
            }
          />
        </Drawer>
      ) : null}

      <Box
        sx={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <AppTopBar
          isMdUp={isMdUp}
          showCooperativeSelector={showCooperativeSelector}
          sidebarHidden={sidebarHidden}
          onOpenNav={() => setDrawerOpen(true)}
          hideUserMenu
          showBackToDashboard={isMember && location.pathname !== ROUTES.dashboard}
        />
        <OfflineBanner />
        <SubscriptionBanner />

        <Box
          component="main"
          sx={{
            flexGrow: 1,
            width: '100%',
            minWidth: 0,
            bgcolor: 'background.default',
          }}
        >
          <Box
            data-testid="app-main-content"
            sx={{
              p: { xs: 2, sm: 3 },
              // Unpinned desktop is the "no sidebar" mode: let the content use the whole available width.
              maxWidth: isMdUp && !sidebarPinned ? 'none' : 1440,
              mx: 'auto',
              width: '100%',
            }}
          >
            <Outlet />
          </Box>
        </Box>
        <AppFooter variant="compact" />
      </Box>
      <SupportDock />
    </Box>
  )
}
