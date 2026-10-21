import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import {
  ROLE_ACCOUNTANT,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SUPER_ADMIN,
  type AuthUser,
} from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { AppRouter } from './AppRouter'

vi.mock('@/shared/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/auth')>()
  return { ...actual, refresh: vi.fn().mockRejectedValue(new Error('no session')), logout: vi.fn() }
})
vi.mock('@/shared/api/notifications', () => ({
  fetchUnreadCount: vi.fn().mockResolvedValue(0),
}))
vi.mock('@/shared/api/cooperatives', () => ({
  fetchCooperatives: vi.fn().mockResolvedValue({
    content: [
      {
        id: 'coop-1',
        name: 'Portal Scheme',
        currency: 'RWF',
        status: 'ACTIVE',
        onboardingState: 'COMPLETE',
        financialYearStartMonth: 1,
        monthlyContributionAmount: '1000',
        contributionDueDay: 1,
      },
    ],
    totalElements: 1,
    totalPages: 1,
    page: 0,
    size: 10,
  }),
  createCooperative: vi.fn(),
  fetchMyCooperatives: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/shared/api/health', () => ({
  fetchHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
  fetchSystemInfo: vi.fn().mockResolvedValue({ name: 'csams', version: '1.0.0' }),
}))
vi.mock('@/shared/api/dashboard', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/dashboard')>()
  return { ...actual, fetchPlatformOverviewIfAvailable: vi.fn().mockResolvedValue(null) }
})
vi.mock('@/shared/api/superAdmins', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/superAdmins')>()
  return { ...actual, fetchSuperAdmins: vi.fn().mockResolvedValue([]) }
})

// Pages that are not under test are replaced by markers so the test checks routing, not page internals.
vi.mock('@/pages/DashboardPage', () => ({ DashboardPage: () => <div>client-dashboard</div> }))
vi.mock('@/pages/ProfilePage', () => ({ ProfilePage: () => <div>profile-page</div> }))
vi.mock('@/pages/ChangePasswordPage', () => ({ ChangePasswordPage: () => <div>change-password-page</div> }))
vi.mock('@/pages/LoginPage', () => ({ LoginPage: () => <div>login-page</div> }))
vi.mock('@/pages/CooperativeDetailPage', async () => {
  const { useParams } = await import('react-router-dom')
  return {
    CooperativeDetailPage: () => <div>cooperative-detail:{useParams().id}</div>,
  }
})
vi.mock('@/pages/MembersPage', () => ({ MembersPage: () => <div>members-page</div> }))
vi.mock('@/pages/LoansPage', () => ({ LoansPage: () => <div>loans-page</div> }))
vi.mock('@/layouts/AppLayout', async () => {
  const { Outlet } = await import('react-router-dom')
  return { AppLayout: () => <div data-testid="client-layout"><Outlet /></div> }
})

function userWith(roles: string[]): AuthUser {
  return {
    id: 'u1',
    username: 'tester',
    email: 't@example.com',
    firstName: 'Test',
    lastName: 'User',
    fullName: 'Test User',
    roles,
    permissions: [],
    cooperativeIds: [],
  }
}

function renderAt(path: string, roles: string[] | null) {
  window.history.pushState({}, '', path)
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: roles
        ? {
            user: userWith(roles),
            accessToken: 'token',
            selectedCooperativeId: null,
            status: 'authenticated' as const,
          }
        : { user: null, accessToken: null, selectedCooperativeId: null, status: 'anonymous' as const },
      ui: { sidebarOpen: false, themePreference: 'light' as const, sidebarPinned: true },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: query.includes('900'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
  const view = render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <SnackbarProvider>
            <AppRouter />
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
  return view
}

describe('system-administration portal routing', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('sends an anonymous visitor of /superadmin to login', async () => {
    renderAt('/superadmin', null)
    expect(await screen.findByText('login-page')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/login')
  })

  it.each([ROLE_PRESIDENT, ROLE_COOPERATIVE_ADMIN, ROLE_MEMBER, ROLE_ACCOUNTANT])(
    'denies %s access to every /superadmin route',
    async (role) => {
      for (const path of [
        '/superadmin',
        '/superadmin/admins',
        '/superadmin/cooperatives',
        '/superadmin/cooperatives/coop-1',
        '/superadmin/system',
        '/superadmin/profile',
        '/superadmin/change-password',
      ]) {
        const { unmount } = renderAt(path, [role])
        expect(await screen.findByText('404')).toBeInTheDocument()
        expect(screen.queryByTestId('superadmin-main-content')).not.toBeInTheDocument()
        unmount()
      }
    },
  )

  it('lets a SUPER_ADMIN into the portal home with the dedicated layout', async () => {
    renderAt('/superadmin', [ROLE_SUPER_ADMIN])
    expect(await screen.findByText('OuWealth Administration', { selector: 'h1' })).toBeInTheDocument()
    expect(screen.getByTestId('superadmin-sidebar-desktop')).toBeInTheDocument()
    expect(screen.queryByTestId('client-layout')).not.toBeInTheDocument()
  })

  it('sends a SUPER_ADMIN who opens the client /dashboard to /superadmin', async () => {
    renderAt('/dashboard', [ROLE_SUPER_ADMIN])
    expect(await screen.findByText('OuWealth Administration', { selector: 'h1' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin')
    expect(screen.queryByText('client-dashboard')).not.toBeInTheDocument()
  })

  it.each(['/members', '/loans'])('sends a SUPER_ADMIN away from the client route %s', async (path) => {
    renderAt(path, [ROLE_SUPER_ADMIN])
    await waitFor(() => expect(window.location.pathname).toBe('/superadmin'))
    expect(screen.queryByText('members-page')).not.toBeInTheDocument()
    expect(screen.queryByText('loans-page')).not.toBeInTheDocument()
  })

  it('maps legacy platform and account URLs of a SUPER_ADMIN to their portal equivalents', async () => {
    let view = renderAt('/cooperatives', [ROLE_SUPER_ADMIN])
    await waitFor(() => expect(window.location.pathname).toBe('/superadmin/cooperatives'))

    view.unmount()
    view = renderAt('/cooperatives/coop-1', [ROLE_SUPER_ADMIN])
    expect(await screen.findByText('cooperative-detail:coop-1')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin/cooperatives/coop-1')

    view.unmount()
    view = renderAt('/system', [ROLE_SUPER_ADMIN])
    await waitFor(() => expect(window.location.pathname).toBe('/superadmin/system'))

    view.unmount()
    view = renderAt('/profile', [ROLE_SUPER_ADMIN])
    expect(await screen.findByText('profile-page')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin/profile')
  })

  it('does not give Saving Scheme users the legacy platform URLs', async () => {
    renderAt('/cooperatives', [ROLE_PRESIDENT])
    expect(await screen.findByText('client-dashboard')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/dashboard')
  })

  it('leaves the normal client routes untouched for Saving Scheme roles', async () => {
    let view = renderAt('/dashboard', [ROLE_PRESIDENT])
    expect(await screen.findByText('client-dashboard')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/dashboard')

    view.unmount()
    view = renderAt('/loans', [ROLE_MEMBER])
    expect(await screen.findByText('loans-page')).toBeInTheDocument()
    expect(screen.getByTestId('client-layout')).toBeInTheDocument()
  })

  it('reuses the Saving Schemes and System pages inside the portal layout', async () => {
    let view = renderAt('/superadmin/cooperatives', [ROLE_SUPER_ADMIN])
    expect(await screen.findByText('Portal Scheme')).toBeInTheDocument()
    expect(screen.getByTestId('superadmin-main-content')).toBeInTheDocument()

    view.unmount()
    view = renderAt('/superadmin/system', [ROLE_SUPER_ADMIN])
    expect(await screen.findByTestId('superadmin-main-content')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('superadmin-main-content')).toHaveTextContent(/UP|system|health/i))
  })

  it('navigates from a scheme row to the portal detail route', async () => {
    const user = userEvent.setup()
    renderAt('/superadmin/cooperatives', [ROLE_SUPER_ADMIN])
    await user.click(await screen.findByText('Portal Scheme'))
    expect(await screen.findByText('cooperative-detail:coop-1')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin/cooperatives/coop-1')
  })

  it('shows only portal navigation, never Saving Scheme operations', async () => {
    renderAt('/superadmin', [ROLE_SUPER_ADMIN])
    const nav = await screen.findByTestId('app-sidebar')
    for (const name of [/^Overview$/, /^Saving Schemes$/, /^Super Admins$/, /^System$/, /^Profile$/, /^Change Password$/]) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument()
    }
    for (const name of [
      /Members/,
      /Contributions/,
      /Loans/,
      /Fines/,
      /Social/,
      /Investments/,
      /Transactions/,
      /Ledger/,
      /Share-out|Payouts/,
      /Historical/,
      /Billing/,
      /Settings/,
      /Audit/,
    ]) {
      expect(within(nav).queryByRole('link', { name })).not.toBeInTheDocument()
    }
  })

  it('marks only the current portal item as active', async () => {
    renderAt('/superadmin/admins', [ROLE_SUPER_ADMIN])
    const nav = await screen.findByTestId('app-sidebar')
    expect(within(nav).getByRole('link', { name: /^Super Admins$/ })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: /^Overview$/ })).not.toHaveAttribute('aria-current')
  })

  it('does not render the cooperative selector or notification bell in the portal', async () => {
    renderAt('/superadmin', [ROLE_SUPER_ADMIN])
    await screen.findByTestId('superadmin-main-content')
    expect(screen.queryByLabelText(/select saving scheme|select cooperative/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /notifications/i })).not.toBeInTheDocument()
  })
})
