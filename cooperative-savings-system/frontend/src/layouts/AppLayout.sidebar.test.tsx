import { ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer, { readStoredSidebarPinned, SIDEBAR_PINNED_STORAGE_KEY } from '@/app/store/uiSlice'
import { BRAND_LOGO_ALT } from '@/shared/components/BrandLogo'
import {
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
  ROLE_VICE_PRESIDENT,
} from '@/shared/types/auth'
import { darkTheme, lightTheme } from '@/theme/theme'
import { AppLayout } from './AppLayout'

vi.mock('@/shared/api/notifications', () => ({
  fetchUnreadCount: vi.fn().mockResolvedValue(0),
  fetchNotifications: vi.fn().mockResolvedValue([]),
  fetchPendingApprovals: vi.fn().mockResolvedValue([]),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/shared/api/cooperatives', () => ({
  fetchMyCooperatives: vi.fn().mockResolvedValue([
    { id: 'coop-1', name: 'Alpha Scheme', code: 'A1' },
    { id: 'coop-2', name: 'Beta Scheme', code: 'B2' },
  ]),
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
    // Within the 7-day window so the trial-expiry notice is rendered.
    daysRemaining: 5,
    trialEndsAt: '2099-12-31T00:00:00Z',
  }),
}))

vi.mock('@/shared/api/auth', () => ({
  logout: vi.fn().mockResolvedValue(undefined),
}))

/** Sidebar sections start collapsed; role/permission tests open them all to inspect the links. */
function expandAll<T extends HTMLElement>(root: T): T {
  root
    .querySelectorAll('[data-testid^="sidebar-section-toggle-"][aria-expanded="false"]')
    .forEach((toggle) => fireEvent.click(toggle))
  return root
}

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

function renderLayout({
  roles,
  mdUp = true,
  path = '/dashboard',
  mode = 'light' as 'light' | 'dark',
  selectedCooperativeId = 'coop-1',
  isSuperAdmin = false,
  sidebarPinned = true,
}: {
  roles: string[]
  mdUp?: boolean
  path?: string
  mode?: 'light' | 'dark'
  selectedCooperativeId?: string | null
  isSuperAdmin?: boolean
  sidebarPinned?: boolean
}) {
  stubMatchMedia(mdUp)
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'tester',
          email: 't@example.com',
          firstName: 'Test',
          lastName: 'User',
          fullName: 'Test User',
          roles,
          permissions: [],
          cooperativeIds: ['coop-1', 'coop-2'],
        },
        accessToken: 'test-token',
        selectedCooperativeId,
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: mode, sidebarPinned },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  const view = render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={mode === 'dark' ? darkTheme : lightTheme}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route element={<AppLayout />}>
                <Route path="/dashboard" element={<div data-testid="page-dashboard">Dashboard</div>} />
                <Route path="/loans" element={<div data-testid="page-loans">Loans</div>} />
                <Route path="/loans/:id" element={<div data-testid="page-loan-detail">Loan detail</div>} />
                <Route path="/members" element={<div data-testid="page-members">Members</div>} />
                <Route path="/members/:id" element={<div data-testid="page-member-detail">Member detail</div>} />
                <Route path="/cooperatives" element={<div data-testid="page-coops">Coops</div>} />
                <Route path="/cooperatives/:id" element={<div data-testid="page-coop-detail">Coop detail</div>} />
                <Route path="/billing" element={<div data-testid="page-billing">Billing</div>} />
                <Route path="/settings" element={<div data-testid="page-settings">Settings</div>} />
                <Route path="/contributions" element={<div data-testid="page-contrib">Contributions</div>} />
                <Route path="/social-fund" element={<div data-testid="page-social">Social</div>} />
                <Route path="/ledger" element={<div data-testid="page-ledger">Ledger</div>} />
                <Route path="/profile" element={<div data-testid="page-profile">Profile</div>} />
                <Route path="*" element={<div data-testid="page-other">Other</div>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )

  return { ...view, store, isSuperAdmin }
}

function desktopNav() {
  return expandAll(
    within(screen.getByTestId('app-sidebar-desktop')).getByRole('navigation', {
      name: /main navigation/i,
    }),
  )
}

describe('AppLayout sidebar (Phase B)', { timeout: 20_000 }, () => {
  beforeEach(() => {
    stubMatchMedia(true)
    localStorage.removeItem(SIDEBAR_PINNED_STORAGE_KEY)
  })

  it('renders a permanent desktop sidebar and slim top bar', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], mdUp: true })
    expect(screen.getByTestId('app-sidebar-desktop')).toBeInTheDocument()
    expect(screen.getByTestId('app-top-bar')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Menu$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /My account/i })).not.toBeInTheDocument()
  })

  it('marks Dashboard as the active nav item on /dashboard', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/dashboard' })
    const link = within(desktopNav()).getByRole('link', { name: /^Dashboard$/i })
    expect(link).toHaveAttribute('aria-current', 'page')
  })

  it('keeps Loans parent active on loan detail routes', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/loans/loan-99' })
    const loans = within(desktopNav()).getByRole('link', { name: /^Loans$/i })
    expect(loans).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('page-loan-detail')).toBeInTheDocument()
  })

  it('shows MEMBER-only navigation modules', () => {
    renderLayout({ roles: [ROLE_MEMBER] })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: /^Dashboard$/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /My contributions/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /My loans/i })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Members$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Billing$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Saving Schemes$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Ledger$/i })).not.toBeInTheDocument()
  })

  it('shows PRESIDENT leadership and operations modules', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /^Settings$/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /Historical Data Import/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /^Billing$/i })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Saving Schemes$/i })).not.toBeInTheDocument()
  })

  it('shows ACCOUNTANT finance modules without membership admin', () => {
    renderLayout({ roles: [ROLE_ACCOUNTANT] })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: /^Contributions$/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /^Ledger$/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /^Billing$/i })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Members$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Settings$/i })).not.toBeInTheDocument()
  })

  it('shows LOAN_OFFICER loan-related modules', () => {
    renderLayout({ roles: [ROLE_LOAN_OFFICER] })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: /^Loans$/i })).toBeInTheDocument()
    // Loan approvals live inside the Loans page now; the sidebar shortcut was replaced.
    expect(within(nav).queryByRole('link', { name: /Loan Approvals/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /Share Purchase Approvals/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Billing$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Members$/i })).not.toBeInTheDocument()
  })

  it('keeps SECRETARY records access unchanged', () => {
    renderLayout({ roles: [ROLE_SECRETARY] })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /Audit logs/i })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Contributions$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^Billing$/i })).not.toBeInTheDocument()
  })

  it('no longer shows platform navigation in the Saving Scheme sidebar (Super Admin portal owns it)', () => {
    renderLayout({ roles: [ROLE_SUPER_ADMIN] })
    const nav = desktopNav()
    expect(within(nav).queryByRole('link', { name: /^Saving Schemes$/i })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /^System$/i })).not.toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
  })

  it('restricts Billing to billing-manager roles', () => {
    const member = renderLayout({ roles: [ROLE_MEMBER] })
    expect(within(desktopNav()).queryByRole('link', { name: /^Billing$/i })).not.toBeInTheDocument()
    member.unmount()

    renderLayout({ roles: [ROLE_VICE_PRESIDENT] })
    expect(within(desktopNav()).getByRole('link', { name: /^Billing$/i })).toBeInTheDocument()
  })

  it('shows Settings only for leadership roles', () => {
    const officer = renderLayout({ roles: [ROLE_LOAN_OFFICER] })
    expect(within(desktopNav()).queryByRole('link', { name: /^Settings$/i })).not.toBeInTheDocument()
    officer.unmount()

    renderLayout({ roles: [ROLE_PRESIDENT] })
    expect(within(desktopNav()).getByRole('link', { name: /^Settings$/i })).toBeInTheDocument()
  })

  it('opens the mobile drawer from the hamburger with the same nav source', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT], mdUp: false })
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /open menu/i }))
    const paper = expandAll(document.querySelector('.MuiDrawer-paper') as HTMLElement)
    expect(paper).toBeTruthy()
    expect(within(paper).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
    expect(within(paper).getByRole('link', { name: /^Dashboard$/i })).toBeInTheDocument()
  })

  it('closes the mobile drawer after selecting a route', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT], mdUp: false, path: '/dashboard' })
    await user.click(screen.getByRole('button', { name: /open menu/i }))
    const paper = expandAll(document.querySelector('.MuiDrawer-paper') as HTMLElement)
    await user.click(within(paper).getByRole('link', { name: /^Members$/i }))
    expect(screen.getByTestId('page-members')).toBeInTheDocument()
    await waitFor(
      () => {
        expect(document.querySelector('.MuiModal-root')).toHaveAttribute('aria-hidden', 'true')
      },
      { timeout: 15_000 },
    )
  }, 20_000)

  it('keeps CooperativeSelector, notifications, language and theme controls (user menu moved to the sidebar)', async () => {
    renderLayout({ roles: [ROLE_PRESIDENT], mdUp: true })
    const top = screen.getByTestId('app-top-bar')
    expect(await within(top).findByLabelText(/Select Saving Scheme/i)).toBeInTheDocument()
    expect(within(top).getByRole('button', { name: /notifications/i })).toBeInTheDocument()
    expect(within(top).queryByRole('button', { name: /profile/i })).not.toBeInTheDocument()
    expect(within(top).getByLabelText(/language/i)).toBeInTheDocument()
    expect(within(top).getByLabelText(/theme/i)).toBeInTheDocument()
  })

  it('renders OfflineBanner and SubscriptionBanner above main content', async () => {
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, get: () => false })
    renderLayout({ roles: [ROLE_PRESIDENT] })
    expect(screen.getByText(/you're offline/i)).toBeInTheDocument()
    expect(await screen.findByTestId('subscription-banner')).toBeInTheDocument()
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, get: () => true })
  })

  it('continues to render deep-linked detail pages inside AppLayout', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/members/m-1' })
    expect(screen.getByTestId('page-member-detail')).toBeInTheDocument()
    expect(within(desktopNav()).getByRole('link', { name: /^Members$/i })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('places the brand lockup in the desktop sidebar navigation', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], mdUp: true })
    const sidebar = screen.getByTestId('app-sidebar-desktop')
    expect(within(sidebar).getByRole('img', { name: BRAND_LOGO_ALT })).toBeInTheDocument()
    expect(within(sidebar).getByText('Wealth')).toBeInTheDocument()
    expect(within(screen.getByRole('banner')).queryByText('Wealth')).not.toBeInTheDocument()
  })
})

describe('AppLayout desktop sidebar pin', { timeout: 20_000 }, () => {
  beforeEach(() => {
    stubMatchMedia(true)
    localStorage.removeItem(SIDEBAR_PINNED_STORAGE_KEY)
  })

  const drawerPaper = () => {
    const paper = document.querySelector('.MuiDrawer-paper') as HTMLElement | null
    return paper ? expandAll(paper) : null
  }
  const drawerClosed = () => waitFor(() => expect(drawerPaper()).toBeNull(), { timeout: 10_000 })

  it('is pinned and visible by default, with an Unpin control and no navigation toggle', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const sidebar = screen.getByTestId('app-sidebar-desktop')
    expect(within(sidebar).getByRole('button', { name: 'Unpin sidebar' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pin sidebar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open navigation' })).not.toBeInTheDocument()
    // the logo stays in the sidebar, not duplicated in the top bar
    expect(within(sidebar).getByRole('img', { name: BRAND_LOGO_ALT })).toBeInTheDocument()
    expect(within(screen.getByRole('banner')).queryByText('Wealth')).not.toBeInTheDocument()
  })

  it('Unpin hides the permanent sidebar and lets the content use the full width', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT] })
    expect(screen.getByTestId('app-main-content')).toHaveStyle({ maxWidth: '1440px' })

    await user.click(screen.getByRole('button', { name: 'Unpin sidebar' }))

    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(screen.getByTestId('page-dashboard')).toBeInTheDocument() // main layout still rendered
    expect(screen.getByTestId('app-main-content')).toHaveStyle({ maxWidth: 'none' })
    expect(localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY)).toBe('false')
  })

  it('shows an Open navigation button and the logo in the top bar while the sidebar is hidden', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT] })
    await user.click(screen.getByRole('button', { name: 'Unpin sidebar' }))

    const top = screen.getByTestId('app-top-bar')
    expect(within(top).getByRole('button', { name: 'Open navigation' })).toBeInTheDocument()
    // the brand must not disappear with the sidebar
    expect(within(top).getByRole('link', { name: /wealth|ouwealth/i })).toHaveAttribute('href', '/dashboard')
    expect(within(top).getByText('Wealth')).toBeInTheDocument()
  })

  it('opens a temporary sidebar from the top bar without re-pinning it', async () => {
    const user = userEvent.setup()
    const { store } = renderLayout({ roles: [ROLE_PRESIDENT], sidebarPinned: false })
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(drawerPaper()).toBeNull()

    await user.click(within(screen.getByTestId('app-top-bar')).getByRole('button', { name: 'Open navigation' }))

    const paper = drawerPaper() as HTMLElement
    expect(paper).toBeTruthy()
    expect(within(paper).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
    expect(within(paper).getByRole('button', { name: 'Pin sidebar' })).toBeInTheDocument()
    // still unpinned: no permanent sidebar and the preference was not changed
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(store.getState().ui.sidebarPinned).toBe(false)
    expect(localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY)).not.toBe('true')
  })

  it('navigates from the temporary sidebar, closes it, and stays unpinned', async () => {
    const user = userEvent.setup()
    const { store } = renderLayout({ roles: [ROLE_PRESIDENT], sidebarPinned: false })
    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    await user.click(within(drawerPaper() as HTMLElement).getByRole('link', { name: /^Members$/i }))

    expect(screen.getByTestId('page-members')).toBeInTheDocument()
    await drawerClosed()
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(store.getState().ui.sidebarPinned).toBe(false)
    expect(screen.getByRole('button', { name: 'Open navigation' })).toBeInTheDocument()
  }, 20_000)

  it('Pin in the temporary sidebar closes it and restores the permanent sidebar', async () => {
    const user = userEvent.setup()
    const { store } = renderLayout({ roles: [ROLE_PRESIDENT], sidebarPinned: false })
    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    await user.click(within(drawerPaper() as HTMLElement).getByRole('button', { name: 'Pin sidebar' }))

    expect(await screen.findByTestId('app-sidebar-desktop')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open navigation' })).not.toBeInTheDocument()
    expect(within(screen.getByTestId('app-sidebar-desktop')).getByRole('button', { name: 'Unpin sidebar' })).toBeInTheDocument()
    expect(store.getState().ui.sidebarPinned).toBe(true)
    expect(localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY)).toBe('true')
    await drawerClosed()
    expect(screen.getByTestId('app-main-content')).toHaveStyle({ maxWidth: '1440px' })
  }, 20_000)

  it('persists the preference through localStorage across a re-render', async () => {
    const user = userEvent.setup()
    const first = renderLayout({ roles: [ROLE_PRESIDENT] })
    await user.click(screen.getByRole('button', { name: 'Unpin sidebar' }))
    expect(localStorage.getItem(SIDEBAR_PINNED_STORAGE_KEY)).toBe('false')
    first.unmount()

    // a fresh app start reads the stored value: hidden on desktop
    renderLayout({ roles: [ROLE_PRESIDENT], sidebarPinned: readStoredSidebarPinned() })
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open navigation' })).toBeInTheDocument()
    document.body.innerHTML = ''

    localStorage.setItem(SIDEBAR_PINNED_STORAGE_KEY, 'true')
    renderLayout({ roles: [ROLE_PRESIDENT], sidebarPinned: readStoredSidebarPinned() })
    expect(screen.getByTestId('app-sidebar-desktop')).toBeInTheDocument()
  })

  it('does not change mobile: hamburger opens a temporary drawer with no pin controls', async () => {
    const user = userEvent.setup()
    for (const sidebarPinned of [true, false]) {
      const view = renderLayout({ roles: [ROLE_PRESIDENT], mdUp: false, sidebarPinned })
      expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Open navigation' })).not.toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /open menu/i }))
      const paper = drawerPaper() as HTMLElement
      expect(within(paper).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /pin sidebar/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /unpin sidebar/i })).not.toBeInTheDocument()
      view.unmount()
    }
  })
})

describe('AppLayout sidebar: Loans and Share Purchase Approvals', { timeout: 20_000 }, () => {
  beforeEach(() => {
    stubMatchMedia(true)
    localStorage.removeItem(SIDEBAR_PINNED_STORAGE_KEY)
  })

  it('shows Loans but no Loan Approvals shortcut, and puts Share Purchase Approvals right after Loans', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const nav = desktopNav()
    const names = within(nav)
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(names).toContain('Loans')
    expect(names).not.toContain('Loan Approvals')
    expect(names.indexOf('Share Purchase Approvals')).toBe(names.indexOf('Loans') + 1)
  })

  it('links Share Purchase Approvals to its dedicated view', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    expect(within(desktopNav()).getByRole('link', { name: 'Share Purchase Approvals' })).toHaveAttribute(
      'href',
      '/contributions?tab=share-approvals',
    )
  })

  it('shows Share Purchase Approvals only to authorized roles', () => {
    for (const role of [ROLE_PRESIDENT, ROLE_VICE_PRESIDENT, ROLE_ACCOUNTANT, ROLE_SUPER_ADMIN]) {
      const view = renderLayout({ roles: [role] })
      expect(within(desktopNav()).getByRole('link', { name: 'Share Purchase Approvals' })).toBeInTheDocument()
      view.unmount()
    }
    for (const role of [ROLE_SECRETARY, ROLE_LOAN_OFFICER, ROLE_MEMBER]) {
      const view = renderLayout({ roles: [role] })
      expect(within(desktopNav()).queryByRole('link', { name: 'Share Purchase Approvals' })).not.toBeInTheDocument()
      view.unmount()
    }
  }, 30_000)

  it('marks only Share Purchase Approvals active on its view', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/contributions?tab=share-approvals' })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: 'Share Purchase Approvals' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Regular Contribution Approvals' })).not.toHaveAttribute('aria-current')
    expect(within(nav).getByRole('link', { name: /^Contributions$/ })).not.toHaveAttribute('aria-current')
  })

  it('marks only Regular Contribution Approvals active on its view', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/contributions?tab=approvals' })
    const nav = desktopNav()
    expect(within(nav).getByRole('link', { name: 'Regular Contribution Approvals' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Share Purchase Approvals' })).not.toHaveAttribute('aria-current')
    expect(within(nav).getByRole('link', { name: /^Contributions$/ })).not.toHaveAttribute('aria-current')
  })
})


describe('AppLayout sidebar: expandable sections and bottom account area', { timeout: 20_000 }, () => {
  beforeEach(() => {
    stubMatchMedia(true)
    localStorage.removeItem(SIDEBAR_PINNED_STORAGE_KEY)
  })

  const rawNav = () =>
    within(screen.getByTestId('app-sidebar-desktop')).getByRole('navigation', { name: /main navigation/i })
  const toggle = (id: string) => screen.getByTestId(`sidebar-section-toggle-${id}`)

  it('shows exactly the four section headers for a President, in order', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const toggles = rawNav().querySelectorAll('[data-testid^="sidebar-section-toggle-"]')
    expect(Array.from(toggles).map((el) => el.textContent)).toEqual([
      'Operations',
      'Finance',
      'Administration',
      'Profile',
    ])
    // Dashboard remains a plain top-level link, not a section.
    expect(within(rawNav()).getByRole('link', { name: /^Dashboard$/i })).toBeInTheDocument()
  })

  it('starts collapsed apart from the section holding the active page, and hides collapsed links', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/dashboard' })
    for (const id of ['operations', 'finance', 'administration', 'profile']) {
      expect(toggle(id)).toHaveAttribute('aria-expanded', 'false')
    }
    // Collapsed links are not exposed to assistive tech or the keyboard.
    expect(within(rawNav()).queryByRole('link', { name: /^Members$/i })).not.toBeInTheDocument()
  })

  it('expands and collapses a section when its header is clicked', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/dashboard' })
    await user.click(toggle('operations'))
    expect(toggle('operations')).toHaveAttribute('aria-expanded', 'true')
    expect(within(rawNav()).getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
    await user.click(toggle('operations'))
    expect(toggle('operations')).toHaveAttribute('aria-expanded', 'false')
    await waitFor(() =>
      expect(within(rawNav()).queryByRole('link', { name: /^Members$/i })).not.toBeInTheDocument(),
    )
  })

  it('auto-expands the section that contains the active route and highlights the item', () => {
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/loans/loan-9' })
    expect(toggle('finance')).toHaveAttribute('aria-expanded', 'true')
    expect(toggle('operations')).toHaveAttribute('aria-expanded', 'false')
    expect(within(rawNav()).getByRole('link', { name: /^Loans$/i })).toHaveAttribute('aria-current', 'page')
  })

  it('auto-expands a collapsed section when navigating into it', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT], path: '/dashboard' })
    await user.click(toggle('administration'))
    await user.click(within(rawNav()).getByRole('link', { name: /^Settings$/i }))
    expect(toggle('administration')).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('page-settings')).toBeInTheDocument()
  })

  it('maps pages to sections: Loans, Social Dashboard and share approvals are Finance; Members is Operations', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const section = (id: string) => within(screen.getByTestId(`sidebar-section-${id}`))
    expandAll(rawNav())
    expect(section('operations').getByRole('link', { name: /^Members$/i })).toBeInTheDocument()
    expect(section('operations').getByRole('link', { name: /^Fines$/i })).toBeInTheDocument()
    expect(section('finance').getByRole('link', { name: /^Loans$/i })).toBeInTheDocument()
    expect(section('finance').getByRole('link', { name: /Share Purchase Approvals/i })).toBeInTheDocument()
    expect(section('finance').getByRole('link', { name: /Social Dashboard/i })).toBeInTheDocument()
    expect(section('finance').getByRole('link', { name: /^Reports$/i })).toBeInTheDocument()
    expect(section('administration').getByRole('link', { name: /^Settings$/i })).toBeInTheDocument()
    expect(section('administration').getByRole('link', { name: /^Billing$/i })).toBeInTheDocument()
    expect(section('administration').getByRole('link', { name: /Audit logs/i })).toBeInTheDocument()
    expect(section('profile').getByRole('link', { name: /^Profile$/i })).toBeInTheDocument()
  })

  it('omits sections a role has no pages in (no unauthorized links to expand)', () => {
    renderLayout({ roles: [ROLE_MEMBER] })
    const ids = Array.from(rawNav().querySelectorAll('[data-testid^="sidebar-section-toggle-"]')).map((el) =>
      el.getAttribute('data-testid'),
    )
    expect(ids).not.toContain('sidebar-section-toggle-administration')
    expect(ids).toContain('sidebar-section-toggle-operations')
  })

  it('anchors the profile and sign-out controls at the bottom of the sidebar, outside the scroll area', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const sidebar = within(screen.getByTestId('app-sidebar-desktop'))
    const account = sidebar.getByTestId('sidebar-account')
    const scroll = sidebar.getByTestId('sidebar-scroll')
    expect(scroll).not.toContainElement(account)
    expect(sidebar.getByTestId('app-sidebar').lastElementChild).toBe(account)
    expect(within(account).getByText('Test User')).toBeInTheDocument()
    expect(within(account).getByText('PRESIDENT')).toBeInTheDocument()
    expect(within(account).getByRole('button', { name: /sign out/i })).toBeInTheDocument()
    // The navigation area scrolls on its own so the account area stays visible.
    expect(getComputedStyle(scroll).overflowY).toBe('auto')
  })

  it('no longer renders the profile control in the top navbar', () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const top = screen.getByTestId('app-top-bar')
    expect(within(top).queryByRole('button', { name: /profile/i })).not.toBeInTheDocument()
    expect(within(top).queryByText('Test User')).not.toBeInTheDocument()
  })

  it('opens the account menu from the profile area', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT] })
    await user.click(screen.getByTestId('sidebar-account-button'))
    expect(await screen.findByRole('menuitem', { name: /^Profile$/i })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /sign out/i })).toBeInTheDocument()
  })

  it('signs out through the existing logout flow: revokes the session and clears auth', async () => {
    const user = userEvent.setup()
    const { logout } = await import('@/shared/api/auth')
    const { store } = renderLayout({ roles: [ROLE_PRESIDENT] })
    await user.click(within(screen.getByTestId('sidebar-account')).getByRole('button', { name: /sign out/i }))
    await waitFor(() => expect(logout).toHaveBeenCalled())
    await waitFor(() => expect(store.getState().auth.user).toBeNull())
    expect(store.getState().auth.accessToken).toBeNull()
  })

  it('stays usable on narrow screens: account area and sections live in the drawer', async () => {
    const user = userEvent.setup()
    renderLayout({ roles: [ROLE_PRESIDENT], mdUp: false })
    const top = screen.getByTestId('app-top-bar')
    expect(within(top).queryByRole('button', { name: /profile/i })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /open menu/i }))
    const paper = document.querySelector('.MuiDrawer-paper') as HTMLElement
    expect(within(paper).getByTestId('sidebar-account')).toBeInTheDocument()
    expect(within(paper).getByRole('button', { name: /sign out/i })).toBeInTheDocument()
    expect(within(paper).getByTestId('sidebar-section-toggle-operations')).toBeInTheDocument()
    // Language/theme controls remain reachable inside the scrollable navigation area.
    expect(within(within(paper).getByTestId('sidebar-scroll')).getByLabelText(/language/i)).toBeInTheDocument()
  })

  it('still renders the Task 1 trial-expiry notice above the content', async () => {
    renderLayout({ roles: [ROLE_PRESIDENT] })
    const banner = await screen.findByTestId('subscription-banner')
    expect(within(banner).getByText('Your free trial is ending soon')).toBeInTheDocument()
  })
})
