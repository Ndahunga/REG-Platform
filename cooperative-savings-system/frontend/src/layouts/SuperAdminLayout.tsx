import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import SupportAgentIcon from '@mui/icons-material/SupportAgent'
import { Box, Button, Divider, Drawer, Paper, Stack, Typography, useMediaQuery, useTheme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom'
import { setSelectedCooperativeId } from '@/app/store/authSlice'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { selectSidebarPinned, setSidebarPinned } from '@/app/store/uiSlice'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { CooperativeSelector } from '@/shared/components/CooperativeSelector'
import { AppFooter } from '@/shared/components/AppFooter'
import { LanguageSwitcher } from '@/shared/components/LanguageSwitcher'
import { OfflineBanner } from '@/shared/components/OfflineBanner'
import { SupportDock } from '@/shared/components/SupportDock'
import { ThemeSwitcher } from '@/shared/components/ThemeSwitcher'
import { SUPER_ADMIN_ROUTES, parseSupportPath } from '@/shared/constants/routes'
import { AppSidebar, APP_SIDEBAR_WIDTH } from './AppSidebar'
import { AppTopBar } from './AppTopBar'
import { getSuperAdminSidebarGroups, getSupportSidebarGroups } from './superAdminNav'

/**
 * Shell of the OuWealth system-administration portal. It shares the visual language of the Saving Scheme
 * `AppLayout` but has its own navigation, no cooperative selector, no notification centre and no
 * subscription banner: a platform administrator is not a customer of any Saving Scheme.
 */
export function SuperAdminLayout() {
  const { t } = useTranslation()
  const theme = useTheme()
  const dispatch = useAppDispatch()
  const isMdUp = useMediaQuery(theme.breakpoints.up('md'))
  const location = useLocation()
  const sidebarPinned = useAppSelector(selectSidebarPinned)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const showPermanentSidebar = isMdUp && sidebarPinned
  const sidebarHidden = !showPermanentSidebar
  const selected = useAppSelector((s) => s.auth.selectedCooperativeId)
  const workspace = parseSupportPath(location.pathname)
  const workspaceId = workspace?.cooperativeId ?? null

  // Shares the cache entry of the workspace route, so this costs no extra request.
  const schemeQuery = useQuery({
    queryKey: ['cooperatives', workspaceId],
    queryFn: () => fetchCooperative(workspaceId as string),
    enabled: Boolean(workspaceId),
    retry: false,
  })
  const schemeName = schemeQuery.data?.name ?? ''
  // A scheme that cannot be opened (bad or inaccessible id) keeps the plain platform chrome.
  const inSupport = Boolean(workspaceId) && !schemeQuery.isError
  const groups = inSupport && workspaceId ? getSupportSidebarGroups(workspaceId) : getSuperAdminSidebarGroups()

  // Outside a support workspace no Saving Scheme is selected: the platform views are cooperative-free.
  useEffect(() => {
    if (!workspaceId && selected) dispatch(setSelectedCooperativeId(null))
  }, [workspaceId, selected, dispatch])

  const sidebarHeader = inSupport ? (
    <Box sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }} data-testid="support-sidebar-header">
      <Typography variant="subtitle2" sx={{ fontWeight: 700 }} noWrap title={schemeName}>
        {schemeName || t('common.loading')}
      </Typography>
      <Typography variant="caption" color="warning.main" sx={{ display: 'block', fontWeight: 600 }}>
        {t('superAdmin.support.badge')}
      </Typography>
      <Button
        component={RouterLink}
        to={SUPER_ADMIN_ROUTES.home}
        size="small"
        startIcon={<ArrowBackIcon />}
        sx={{ mt: 0.75, ml: -1 }}
        data-testid="support-back-to-platform"
      >
        {t('superAdmin.support.backToPlatform')}
      </Button>
    </Box>
  ) : undefined
  const dark = theme.palette.mode === 'dark'
  const closeDrawer = () => setDrawerOpen(false)

  useEffect(() => {
    setDrawerOpen(false)
  }, [location.pathname, location.search])

  useEffect(() => {
    if (showPermanentSidebar) setDrawerOpen(false)
  }, [showPermanentSidebar])

  return (
    <Box sx={{ display: 'flex', minHeight: '100dvh', bgcolor: 'background.default' }}>
      {showPermanentSidebar ? (
        <Box
          component="aside"
          data-testid="superadmin-sidebar-desktop"
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
            userRoles={[]}
            groups={groups}
            header={sidebarHeader}
            homePath={SUPER_ADMIN_ROUTES.home}
            onDarkBrand={dark}
            pinControl={{ pinned: true, onToggle: () => dispatch(setSidebarPinned(false)) }}
          />
        </Box>
      ) : null}

      {sidebarHidden ? (
        <Drawer
          variant="temporary"
          open={drawerOpen}
          onClose={closeDrawer}
          ModalProps={{ keepMounted: !isMdUp }}
          aria-label={t('nav.sidebarAria')}
          sx={{
            ...(isMdUp ? { zIndex: (muiTheme) => muiTheme.zIndex.drawer + 2 } : {}),
            '& .MuiDrawer-paper': { width: APP_SIDEBAR_WIDTH, boxSizing: 'border-box' },
          }}
        >
          <AppSidebar
            userRoles={[]}
            groups={groups}
            header={sidebarHeader}
            homePath={SUPER_ADMIN_ROUTES.home}
            onDarkBrand={dark}
            onNavigate={closeDrawer}
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
          {!isMdUp ? (
            <>
              <Divider />
              <Box sx={{ px: 2, py: 1.5, display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                <LanguageSwitcher />
                <ThemeSwitcher />
                <CooperativeSelector />
              </Box>
            </>
          ) : null}
        </Drawer>
      ) : null}

      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <AppTopBar
          isMdUp={isMdUp}
          showCooperativeSelector
          sidebarHidden={sidebarHidden}
          onOpenNav={() => setDrawerOpen(true)}
          showBackToDashboard={false}
          homePath={SUPER_ADMIN_ROUTES.home}
          hideNotifications
        />
        <OfflineBanner />
        {inSupport ? (
          <Paper
            square
            elevation={0}
            role="status"
            data-testid="support-banner"
            sx={{
              px: { xs: 2, sm: 3 },
              py: 1,
              bgcolor: 'rgba(255, 122, 0, 0.14)',
              color: 'text.primary',
              borderBottom: '1px solid',
              borderColor: 'warning.main',
            }}
          >
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
              <SupportAgentIcon fontSize="small" />
              <Typography variant="body2" sx={{ fontWeight: 600, flex: 1, minWidth: 200 }}>
                {t('superAdmin.support.banner', { name: schemeName || '…' })}
              </Typography>
              <Button
                component={RouterLink}
                to={SUPER_ADMIN_ROUTES.home}
                size="small"
                color="inherit"
                variant="outlined"
                data-testid="support-exit"
              >
                {t('superAdmin.support.exit')}
              </Button>
            </Stack>
          </Paper>
        ) : null}

        <Box component="main" sx={{ flexGrow: 1, width: '100%', minWidth: 0, bgcolor: 'background.default' }}>
          <Box
            data-testid="superadmin-main-content"
            sx={{
              p: { xs: 2, sm: 3 },
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
