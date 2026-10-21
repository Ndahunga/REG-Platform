import { ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_PRESIDENT } from '@/shared/types/auth'
import { BRAND_LOGO_ALT } from '@/shared/components/BrandLogo'
import { darkTheme, lightTheme } from '@/theme/theme'
import { AppLayout } from './AppLayout'

vi.mock('@/shared/api/notifications', () => ({
  fetchUnreadCount: vi.fn().mockResolvedValue(0),
  fetchNotifications: vi.fn().mockResolvedValue([]),
  fetchPendingApprovals: vi.fn().mockResolvedValue([]),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/shared/api/cooperatives', () => ({
  fetchMyCooperatives: vi.fn().mockResolvedValue([]),
}))

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn().mockResolvedValue({
    id: 'sub-1',
    cooperativeId: 'coop-1',
    status: 'TRIAL',
    effectiveStatus: 'TRIAL',
    writeAllowed: true,
    usable: true,
  }),
}))

function stubMatchMedia(mdUp: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: query.includes('900') ? mdUp : false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

function renderAppLayout(mode: 'light' | 'dark', mdUp = false) {
  stubMatchMedia(mdUp)
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'president',
          email: 'p@example.com',
          firstName: 'Pat',
          lastName: 'Leader',
          fullName: 'Pat Leader',
          roles: [ROLE_PRESIDENT],
          permissions: [],
          cooperativeIds: ['coop-1'],
        },
        accessToken: 'test-token',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: mode },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={mode === 'dark' ? darkTheme : lightTheme}>
          <MemoryRouter initialEntries={['/dashboard']}>
            <AppLayout />
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('AppLayout logo surfaces', () => {
  beforeEach(() => {
    stubMatchMedia(false)
  })

  it('uses the brand lockup in the desktop sidebar in Light Mode', () => {
    renderAppLayout('light', true)
    const sidebar = screen.getByTestId('app-sidebar-desktop')
    expect(within(sidebar).getByRole('img', { name: BRAND_LOGO_ALT })).toBeInTheDocument()
    expect(within(sidebar).getByText('Wealth')).toBeInTheDocument()
    expect(within(sidebar).getByText('COMMUNITY')).toBeInTheDocument()
    expect(within(screen.getByRole('banner')).queryByText('Wealth')).not.toBeInTheDocument()
  })

  it('uses the brand lockup in the desktop sidebar in Dark Mode', () => {
    renderAppLayout('dark', true)
    const sidebar = screen.getByTestId('app-sidebar-desktop')
    expect(within(sidebar).getByRole('img', { name: BRAND_LOGO_ALT })).toBeInTheDocument()
    expect(within(sidebar).getByText('COMMUNITY')).toBeInTheDocument()
  })

  it('uses the lockup in the Light Mode top bar and drawer', async () => {
    const user = userEvent.setup()
    renderAppLayout('light', false)
    expect(within(screen.getByRole('banner')).getByText('COMMUNITY')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /open menu/i }))
    const paper = document.querySelector('.MuiDrawer-paper')
    expect(paper).toBeInstanceOf(HTMLElement)
    expect(within(paper as HTMLElement).getByText('Wealth')).toBeInTheDocument()
    expect(within(paper as HTMLElement).getByText('COMMUNITY')).toBeInTheDocument()
  })

  it('uses the lockup in the Dark Mode drawer', async () => {
    const user = userEvent.setup()
    renderAppLayout('dark', false)
    await user.click(screen.getByRole('button', { name: /open menu/i }))
    const paper = document.querySelector('.MuiDrawer-paper')
    expect(paper).toBeInstanceOf(HTMLElement)
    expect(within(paper as HTMLElement).getByText('COMMUNITY')).toBeInTheDocument()
  })
})
