import { CssBaseline, ThemeProvider } from '@mui/material'
import { QueryClientProvider } from '@tanstack/react-query'
import { SnackbarProvider } from 'notistack'
import { useEffect, useMemo, type ReactNode } from 'react'
import { Provider } from 'react-redux'
import { queryClient } from '@/app/queryClient'
import { store } from '@/app/store/store'
import { useAppSelector } from '@/app/store/hooks'
import { resolveThemeMode } from '@/app/store/uiSlice'
import { PwaUpdateBanner } from '@/pwa/PwaUpdateBanner'
import { darkTheme, lightTheme } from '@/theme/theme'

function ThemedApp({ children }: { children: ReactNode }) {
  const preference = useAppSelector((s) => s.ui.themePreference)
  const mode = resolveThemeMode(preference)
  const theme = useMemo(() => (mode === 'dark' ? darkTheme : lightTheme), [mode])

  useEffect(() => {
    document.documentElement.dataset.theme = mode
    document.documentElement.style.colorScheme = mode
  }, [mode])

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <SnackbarProvider
        maxSnack={3}
        autoHideDuration={4000}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        {children}
        <PwaUpdateBanner />
      </SnackbarProvider>
    </ThemeProvider>
  )
}

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <Provider store={store}>
      <QueryClientProvider client={queryClient}>
        <ThemedApp>{children}</ThemedApp>
      </QueryClientProvider>
    </Provider>
  )
}
