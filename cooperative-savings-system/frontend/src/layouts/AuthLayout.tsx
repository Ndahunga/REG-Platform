import { Box, Container, useTheme } from '@mui/material'
import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { AppFooter } from '@/shared/components/AppFooter'
import { LanguageSwitcher } from '@/shared/components/LanguageSwitcher'
import { SupportDock } from '@/shared/components/SupportDock'
import { ThemeSwitcher } from '@/shared/components/ThemeSwitcher'
import { ROUTES } from '@/shared/constants/routes'
import { AuthSurfaceContext } from './authSurface'

/** Login and signup render their own split shell (see AuthSplitShell) and need the wider canvas. */
const SPLIT_WIDTHS: Record<string, number> = {
  [ROUTES.login]: 960,
  [ROUTES.signup]: 960,
}

export function AuthLayout() {
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'
  const location = useLocation()
  const splitWidth = SPLIT_WIDTHS[location.pathname]

  // A newly opened auth page starts at the top.
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [location.pathname])

  return (
    <AuthSurfaceContext.Provider value={{ onDark: dark }}>
      <Box
        sx={{
          minHeight: '100dvh',
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
          background: dark
            ? 'radial-gradient(ellipse at 15% 0%, rgba(27,77,140,0.32) 0%, transparent 52%), radial-gradient(ellipse at 95% 85%, rgba(255,122,0,0.1) 0%, transparent 42%), linear-gradient(160deg, #0A0A0A 0%, #121212 100%)'
            : 'radial-gradient(ellipse at 15% 0%, rgba(27,77,140,0.16) 0%, transparent 52%), radial-gradient(ellipse at 95% 85%, rgba(255,122,0,0.08) 0%, transparent 42%), linear-gradient(160deg, #FFFFFF 0%, #F4F8FD 60%, #E8F1FB 100%)',
        }}
      >
        <Box
          sx={{
            position: 'absolute',
            top: 16,
            right: 16,
            zIndex: 1,
            display: 'flex',
            alignItems: 'center',
            gap: 1,
          }}
        >
          <LanguageSwitcher onDark={dark} />
          <ThemeSwitcher onDark={dark} />
        </Box>
        <Box component="main" sx={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <Container
            maxWidth={false}
            sx={{ maxWidth: splitWidth ?? 600, py: { xs: 8, md: 6 } }}
            data-testid={splitWidth ? 'auth-canvas-split' : 'auth-canvas'}
          >
            <Outlet />
          </Container>
        </Box>
        <AppFooter variant="compact" />
        <SupportDock />
      </Box>
    </AuthSurfaceContext.Provider>
  )
}
