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
import { fetchCooperative } from '@/shared/api/cooperatives'
import { ROLE_MEMBER, ROLE_PRESIDENT, ROLE_SUPER_ADMIN, type AuthUser } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { AppRouter } from './AppRouter'

const SCHEMES: Record<string, { id: string; name: string }> = {
  'coop-1': { id: 'coop-1', name: 'Alpha Scheme' },
  'coop-2': { id: 'coop-2', name: 'Beta Scheme' },
}

vi.mock('@/shared/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/auth')>()
  return { ...actual, refresh: vi.fn().mockRejectedValue(new Error('no session')), logout: vi.fn() }
})
vi.mock('@/shared/api/notifications', () => ({ fetchUnreadCount: vi.fn().mockResolvedValue(0) }))
vi.mock('@/shared/api/cooperatives', () => ({
  fetchCooperative: vi.fn(),
  fetchCooperatives: vi.fn().mockResolvedValue({
    content: [
      { id: 'coop-1', name: 'Alpha Scheme', currency: 'RWF', status: 'ACTIVE', onboardingState: 'COMPLETE' },
      { id: 'coop-2', name: 'Beta Scheme', currency: 'RWF', status: 'ACTIVE', onboardingState: 'COMPLETE' },
    ],
    totalElements: 2,
    totalPages: 1,
    page: 0,
    size: 50,
  }),
  fetchMyCooperatives: vi.fn().mockResolvedValue([
    { id: 'coop-1', name: 'Alpha Scheme', status: 'ACTIVE' },
    { id: 'coop-2', name: 'Beta Scheme', status: 'ACTIVE' },
  ]),
  createCooperative: vi.fn(),
}))
vi.mock('@/shared/api/dashboard', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/dashboard')>()
  return {
    ...actual,
    fetchPlatformOverviewIfAvailable: vi.fn().mockResolvedValue({
      totalCooperatives: 2,
      activeCooperatives: 2,
      inactiveCooperatives: 0,
      suspendedCooperatives: 0,
      archivedCooperatives: 0,
      totalMembers: 40,
      activeMembers: 38,
      totalUsers: 45,
      pendingContributionReviews: 1,
      pendingSpecialContributions: 0,
      pendingLoans: 2,
      overdueLoans: 0,
      pendingFinePayments: 0,
      pendingSocialContributions: 0,
      pendingPayouts: 0,
    }),
  }
})
vi.mock('@/shared/api/superAdmins', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/superAdmins')>()
  return { ...actual, fetchSuperAdmins: vi.fn().mockResolvedValue([{ id: 'a' }, { id: 'b' }]) }
})
vi.mock('@/features/dashboard/SuperAdminOverviewCharts', () => ({
  SuperAdminOverviewCharts: () => <div data-testid="platform-charts">platform charts</div>,
}))

// Operational pages are existing components; here they are markers that report the scheme they are scoped to,
// so the test proves the same page is reused and receives the right scheme from the store.
vi.mock('@/pages/DashboardPage', async () => {
  const { useAppSelector } = await import('@/app/store/hooks')
  return {
    DashboardPage: () => {
      const id = useAppSelector((s) => s.auth.selectedCooperativeId)
      return <div data-testid="scheme-dashboard">scheme-dashboard:{id}</div>
    },
  }
})
vi.mock('@/pages/LoansPage', async () => {
  const { useAppSelector } = await import('@/app/store/hooks')
  return {
    LoansPage: () => {
      const id = useAppSelector((s) => s.auth.selectedCooperativeId)
      return <div data-testid="scheme-loans">loans-page:{id}</div>
    },
  }
})
vi.mock('@/pages/LoanDetailPage', async () => {
  const { useParams } = await import('react-router-dom')
  return { LoanDetailRoute: () => <div data-testid="scheme-loan-detail">loan:{useParams().loanId}</div> }
})
vi.mock('@/pages/MembersPage', () => ({ MembersPage: () => <div data-testid="scheme-members">members-page</div> }))
vi.mock('@/pages/LoginPage', () => ({ LoginPage: () => <div>login-page</div> }))
vi.mock('@/pages/CooperativeDetailPage', () => ({ CooperativeDetailPage: () => <div>platform-detail-page</div> }))
vi.mock('@/layouts/AppLayout', async () => {
  const { Outlet } = await import('react-router-dom')
  return { AppLayout: () => <div data-testid="client-layout"><Outlet /></div> }
})

const fetchCooperativeMock = vi.mocked(fetchCooperative)

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
    cooperativeIds: roles.includes(ROLE_SUPER_ADMIN) ? [] : ['coop-1'],
  }
}

function renderAt(path: string, roles: string[], selectedCooperativeId: string | null = null) {
  window.history.pushState({}, '', path)
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: { user: userWith(roles), accessToken: 'token', selectedCooperativeId, status: 'authenticated' as const },
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
  return { ...view, store }
}

const OPERATIONAL_LINKS = [
  /^Dashboard$/,
  /^Members$/,
  /^Contributions$/,
  /^Regular Contribution Approvals$/,
  /^Share Purchase Approvals$/,
  /^Loans$/,
  /^Fines$/,
  /^Fine Payment Queue$/i,
  /^Social Dashboard$/,
  /^Social Contribution Approvals$/,
  /^Investments$/,
  /^Transactions$/,
  /^Ledger$/,
  /^Share Out$/,
  /^Reports$/,
  /^Billing$/,
  /^Historical Data Import$/i,
  /^Settings$/,
  /^Audit logs$/i,
]

describe('Saving Scheme support workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchCooperativeMock.mockImplementation(async (id: string) => {
      const scheme = SCHEMES[id]
      if (!scheme) throw Object.assign(new Error('Cooperative not found'), { isAxiosError: true, response: { status: 404, data: { message: 'Cooperative not found' } } })
      return scheme as never
    })
  })

  it('shows the platform overview, analytics and Saving Schemes at /superadmin', async () => {
    renderAt('/superadmin', [ROLE_SUPER_ADMIN])
    expect(await screen.findByRole('heading', { name: 'OuWealth Administration' })).toBeInTheDocument()
    expect(await screen.findByTestId('platform-charts')).toBeInTheDocument()
    expect(await screen.findByText('Alpha Scheme')).toBeInTheDocument()
    expect(screen.getByText('Beta Scheme')).toBeInTheDocument()
    expect(screen.getByText('Total System Super Admins')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Manage Super Admins/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Manage Saving Schemes/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /System Health/ })).toBeInTheDocument()
    expect(screen.queryByTestId('support-banner')).not.toBeInTheDocument()
  })

  it('keeps the platform sidebar compact when no scheme is open', async () => {
    renderAt('/superadmin', [ROLE_SUPER_ADMIN])
    const nav = await screen.findByTestId('app-sidebar')
    for (const name of [/^Overview$/, /^Saving Schemes$/, /^Super Admins$/, /^System$/, /^Profile$/, /^Change Password$/]) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument()
    }
    expect(within(nav).queryByRole('link', { name: /^Members$/ })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Loans$/ })).not.toBeInTheDocument()
    expect(screen.queryByTestId('support-sidebar-header')).not.toBeInTheDocument()
  })

  it('opens a scheme from the overview into its support workspace', async () => {
    const user = userEvent.setup()
    const { store } = renderAt('/superadmin', [ROLE_SUPER_ADMIN])
    await user.click(await screen.findByText('Alpha Scheme'))

    expect(await screen.findByTestId('scheme-dashboard')).toHaveTextContent('scheme-dashboard:coop-1')
    expect(window.location.pathname).toBe('/superadmin/cooperatives/coop-1/dashboard')
    expect(store.getState().auth.selectedCooperativeId).toBe('coop-1')
    expect(screen.queryByTestId('client-layout')).not.toBeInTheDocument()
  })

  it('makes the support context obvious: banner, scheme header and exit', async () => {
    renderAt('/superadmin/cooperatives/coop-1/dashboard', [ROLE_SUPER_ADMIN])
    const banner = await screen.findByTestId('support-banner')
    await waitFor(() => expect(banner).toHaveTextContent('System Admin Support — Viewing Alpha Scheme'))
    expect(within(banner).getByRole('link', { name: 'Exit Saving Scheme' })).toHaveAttribute('href', '/superadmin')
    const header = screen.getByTestId('support-sidebar-header')
    await waitFor(() => expect(header).toHaveTextContent('Alpha Scheme'))
    expect(header).toHaveTextContent('Alpha Scheme')
    expect(header).toHaveTextContent('System Admin Support')
    expect(within(header).getByRole('link', { name: 'Back to Platform' })).toHaveAttribute('href', '/superadmin')
  })

  it('shows the operational modules, rooted under /superadmin, in the support sidebar', async () => {
    renderAt('/superadmin/cooperatives/coop-1/dashboard', [ROLE_SUPER_ADMIN])
    const nav = await screen.findByTestId('app-sidebar')
    for (const name of OPERATIONAL_LINKS) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument()
    }
    expect(within(nav).getByRole('link', { name: /^Loans$/ })).toHaveAttribute(
      'href',
      '/superadmin/cooperatives/coop-1/loans',
    )
    expect(within(nav).getByRole('link', { name: /^Profile$/ })).toHaveAttribute('href', '/superadmin/profile')
  }, 20000)

  it('reuses the existing pages for each module', async () => {
    const user = userEvent.setup()
    renderAt('/superadmin/cooperatives/coop-1/dashboard', [ROLE_SUPER_ADMIN])
    const nav = await screen.findByTestId('app-sidebar')
    await user.click(within(nav).getByRole('link', { name: /^Loans$/ }))
    expect(await screen.findByTestId('scheme-loans')).toHaveTextContent('loans-page:coop-1')
    await user.click(within(nav).getByRole('link', { name: /^Members$/ }))
    expect(await screen.findByTestId('scheme-members')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin/cooperatives/coop-1/members')
  }, 20000)

  it('exits the scheme and clears the context with Back to Platform', async () => {
    const user = userEvent.setup()
    const { store } = renderAt('/superadmin/cooperatives/coop-1/loans', [ROLE_SUPER_ADMIN])
    await screen.findByTestId('scheme-loans')
    await user.click(screen.getByTestId('support-back-to-platform'))

    expect(await screen.findByRole('heading', { name: 'OuWealth Administration' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin')
    await waitFor(() => expect(store.getState().auth.selectedCooperativeId).toBeNull())
    expect(screen.queryByTestId('support-banner')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('app-sidebar')).queryByRole('link', { name: /^Loans$/ })).not.toBeInTheDocument()
  })

  it('clears the context when leaving a scheme through a platform page', async () => {
    const user = userEvent.setup()
    const { store } = renderAt('/superadmin/cooperatives/coop-1/loans', [ROLE_SUPER_ADMIN])
    await screen.findByTestId('scheme-loans')
    await user.click(within(screen.getByTestId('app-sidebar')).getByRole('link', { name: /^Profile$/ }))
    await waitFor(() => expect(store.getState().auth.selectedCooperativeId).toBeNull())
    expect(screen.queryByTestId('support-banner')).not.toBeInTheDocument()
  })

  it('switches between two schemes and updates the context', async () => {
    const user = userEvent.setup()
    const { store } = renderAt('/superadmin/cooperatives/coop-1/loans', [ROLE_SUPER_ADMIN])
    expect(await screen.findByTestId('scheme-loans')).toHaveTextContent('loans-page:coop-1')

    await user.click(within(screen.getByTestId('app-top-bar')).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: 'Beta Scheme' }))

    await waitFor(() => expect(screen.getByTestId('scheme-loans')).toHaveTextContent('loans-page:coop-2'))
    expect(window.location.pathname).toBe('/superadmin/cooperatives/coop-2/loans')
    expect(screen.getByTestId('support-banner')).toHaveTextContent('Beta Scheme')
    expect(store.getState().auth.selectedCooperativeId).toBe('coop-2')
  })

  it('offers All Saving Schemes in the selector, which returns to the platform overview', async () => {
    const user = userEvent.setup()
    renderAt('/superadmin/cooperatives/coop-1/dashboard', [ROLE_SUPER_ADMIN])
    await screen.findByTestId('scheme-dashboard')
    await user.click(within(screen.getByTestId('app-top-bar')).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: 'All Saving Schemes' }))
    expect(await screen.findByRole('heading', { name: 'OuWealth Administration' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin')
  })

  it('restores the scheme from the URL on a fresh load (refresh)', async () => {
    const { store } = renderAt('/superadmin/cooperatives/coop-2/loans', [ROLE_SUPER_ADMIN], null)
    expect(await screen.findByTestId('scheme-loans')).toHaveTextContent('loans-page:coop-2')
    expect(store.getState().auth.selectedCooperativeId).toBe('coop-2')
    expect(screen.getByTestId('support-banner')).toHaveTextContent('Beta Scheme')
  })

  it('renders nothing from a scheme until it is verified, and fails cleanly for an unknown id', async () => {
    const { store } = renderAt('/superadmin/cooperatives/does-not-exist/loans', [ROLE_SUPER_ADMIN])
    expect(await screen.findByTestId('support-workspace-error')).toHaveTextContent('could not be opened')
    expect(screen.queryByTestId('scheme-loans')).not.toBeInTheDocument()
    expect(screen.queryByTestId('support-banner')).not.toBeInTheDocument()
    expect(store.getState().auth.selectedCooperativeId).toBeNull()
    expect(screen.getByRole('link', { name: 'Back to Saving Schemes' })).toHaveAttribute(
      'href',
      '/superadmin/cooperatives',
    )
  })

  it('keeps unknown sections inside the workspace as not-found', async () => {
    renderAt('/superadmin/cooperatives/coop-1/nonsense', [ROLE_SUPER_ADMIN])
    expect(await screen.findByText('404')).toBeInTheDocument()
  })

  it('sends client URLs of a SUPER_ADMIN with an open scheme to the same page in the workspace', async () => {
    renderAt('/loans/abc?tab=approvals', [ROLE_SUPER_ADMIN], 'coop-1')
    expect(await screen.findByTestId('scheme-loan-detail')).toHaveTextContent('loan:abc')
    expect(window.location.pathname).toBe('/superadmin/cooperatives/coop-1/loans/abc')
    expect(window.location.search).toBe('?tab=approvals')
  })

  it('sends /dashboard to the portal home when no scheme is open', async () => {
    renderAt('/dashboard', [ROLE_SUPER_ADMIN], null)
    expect(await screen.findByRole('heading', { name: 'OuWealth Administration' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/superadmin')
  })

  it('opens a scheme from the Saving Schemes list while keeping row click for management', async () => {
    const user = userEvent.setup()
    renderAt('/superadmin/cooperatives', [ROLE_SUPER_ADMIN])
    const row = (await screen.findByText('Alpha Scheme')).closest('tr') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Open' }))
    expect(await screen.findByTestId('scheme-dashboard')).toHaveTextContent('scheme-dashboard:coop-1')

    window.history.pushState({}, '', '/superadmin/cooperatives')
  })

  it.each([ROLE_PRESIDENT, ROLE_MEMBER])('does not let %s into any workspace URL', async (role) => {
    renderAt('/superadmin/cooperatives/coop-1/loans', [role])
    expect(await screen.findByText('404')).toBeInTheDocument()
    expect(screen.queryByTestId('scheme-loans')).not.toBeInTheDocument()
    expect(screen.queryByTestId('support-banner')).not.toBeInTheDocument()
    expect(fetchCooperativeMock).not.toHaveBeenCalled()
  })

  it('leaves Saving Scheme users on their normal client routes', async () => {
    renderAt('/loans', [ROLE_PRESIDENT], 'coop-1')
    expect(await screen.findByTestId('scheme-loans')).toBeInTheDocument()
    expect(screen.getByTestId('client-layout')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/loans')
  })
})
