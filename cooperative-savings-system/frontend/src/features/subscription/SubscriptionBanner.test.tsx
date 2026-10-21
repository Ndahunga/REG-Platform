import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { fetchSubscription } from '@/shared/api/subscription'
import { MemoryRouter } from 'react-router-dom'
import {
  ROLE_ACCOUNTANT,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
} from '@/shared/types/auth'
import type { CooperativeSubscription } from '@/shared/types/cooperative'
import { SubscriptionBanner } from './SubscriptionBanner'
import { DashboardSubscriptionCard } from './DashboardSubscriptionCard'

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn(),
}))

const fetchMock = vi.mocked(fetchSubscription)

const trial: CooperativeSubscription = {
  id: 's1',
  cooperativeId: 'coop-1',
  status: 'TRIAL',
  storedStatus: 'TRIAL',
  effectiveStatus: 'TRIAL',
  writeAllowed: true,
  usable: true,
  trialEndsAt: '2027-01-16T10:00:00Z',
  daysRemaining: 90,
}

function renderWithAuth(
  ui: React.ReactNode,
  options: {
    roles: string[]
    selectedCooperativeId?: string | null
    subscription?: CooperativeSubscription | null
  },
) {
  fetchMock.mockResolvedValue(options.subscription === undefined ? trial : options.subscription)
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'pat',
          email: 'pat@test.local',
          firstName: 'Pat',
          lastName: 'Leader',
          fullName: 'Pat Leader',
          roles: options.roles,
          permissions: [],
          cooperativeIds: ['coop-1', 'coop-2'],
        },
        accessToken: 'token',
        selectedCooperativeId: options.selectedCooperativeId === undefined ? 'coop-1' : options.selectedCooperativeId,
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return {
    store,
    ...render(
      <Provider store={store}>
        <QueryClientProvider client={client}>
          <MemoryRouter>{ui}</MemoryRouter>
        </QueryClientProvider>
      </Provider>,
    ),
  }
}

describe('SubscriptionBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
  })

  const trialWith = (daysRemaining: number): CooperativeSubscription => ({ ...trial, daysRemaining })

  it('stays quiet while the trial has more than 7 days left', async () => {
    renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(8) })
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(screen.queryByTestId('subscription-banner')).not.toBeInTheDocument()
  })

  it('shows the expiry notice with the actual days remaining at exactly 7 days', async () => {
    renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(7) })
    expect(await screen.findByText('Your free trial is ending soon')).toBeInTheDocument()
    expect(screen.getByText(/7 days left in your free trial/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View plans' })).toHaveAttribute('href', '/billing')
  })

  it('shows fewer than 7 days, singular for the last day, and 0 on the final day', async () => {
    const view = renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(3) })
    expect(await screen.findByText(/3 days left in your free trial/i)).toBeInTheDocument()
    view.unmount()
    renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(1) })
    expect(await screen.findByText(/1 day left in your free trial/i)).toBeInTheDocument()
  })

  it('does not show the trial notice once the trial has expired (expired banner applies instead)', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_PRESIDENT],
      subscription: { ...trialWith(0), effectiveStatus: 'EXPIRED', writeAllowed: false, usable: false },
    })
    expect(await screen.findByText('Your OuWealth subscription is inactive')).toBeInTheDocument()
    expect(screen.queryByText('Your free trial is ending soon')).not.toBeInTheDocument()
  })

  it('shows nothing for an active paid subscription, even with few days left', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_PRESIDENT],
      subscription: {
        ...trial,
        status: 'ACTIVE',
        storedStatus: 'ACTIVE',
        effectiveStatus: 'ACTIVE',
        currentPeriodEndsAt: '2027-01-16T10:00:00Z',
        daysRemaining: 3,
      },
    })
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(screen.queryByTestId('subscription-banner')).not.toBeInTheDocument()
  })

  it.each([[ROLE_PRESIDENT], [ROLE_ACCOUNTANT]])('offers the plans link to billing manager %s', async (role) => {
    renderWithAuth(<SubscriptionBanner />, { roles: [role], subscription: trialWith(5) })
    expect(await screen.findByTestId('trial-notice-billing-cta')).toBeInTheDocument()
  })

  it.each([[ROLE_MEMBER], [ROLE_SECRETARY]])(
    'gives %s the notice but no billing action, and points to leadership',
    async (role) => {
      renderWithAuth(<SubscriptionBanner />, { roles: [role], subscription: trialWith(5) })
      expect(await screen.findByText('Your free trial is ending soon')).toBeInTheDocument()
      expect(screen.queryByTestId('trial-notice-billing-cta')).not.toBeInTheDocument()
      expect(screen.getByText(/Contact your President/i)).toBeInTheDocument()
    },
  )

  it('can be dismissed and stays dismissed across remounts (page navigation) on the same day', async () => {
    const view = renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(5) })
    expect(await screen.findByTestId('subscription-banner')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss for today' }))
    expect(screen.queryByTestId('subscription-banner')).not.toBeInTheDocument()
    view.unmount()

    renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(5) })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(screen.queryByTestId('subscription-banner')).not.toBeInTheDocument()
  })

  it('reappears the next day with the updated count after a dismissal', async () => {
    const key = `ouwealth.trialExpiryNotice.coop-1.${trial.trialEndsAt}`
    window.localStorage.setItem(key, '2000-01-01')
    renderWithAuth(<SubscriptionBanner />, { roles: [ROLE_PRESIDENT], subscription: trialWith(4) })
    expect(await screen.findByText(/4 days left in your free trial/i)).toBeInTheDocument()
  })

  it('shows a past-due warning for leadership', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_PRESIDENT],
      subscription: {
        ...trial,
        status: 'PAST_DUE',
        storedStatus: 'PAST_DUE',
        effectiveStatus: 'PAST_DUE',
        pastDueUntil: '2026-10-01T00:00:00Z',
      },
    })
    expect(await screen.findByText('Subscription payment is overdue')).toBeInTheDocument()
  })

  it('shows expired leadership messaging', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_PRESIDENT],
      subscription: { ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false, usable: false },
    })
    expect(await screen.findByText('Your OuWealth subscription is inactive')).toBeInTheDocument()
    expect(
      screen.getByText(/new transactions and operational changes are temporarily unavailable/i),
    ).toBeInTheDocument()
  })

  it('shows contact-leadership messaging for members', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_MEMBER],
      subscription: { ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false, usable: false },
    })
    expect(await screen.findByText(/contact your President/i)).toBeInTheDocument()
  })

  it('shows NONE read-only warning', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_PRESIDENT],
      subscription: {
        ...trial,
        status: 'NONE',
        storedStatus: 'NONE',
        effectiveStatus: 'NONE',
        writeAllowed: false,
        usable: false,
      },
    })
    expect(await screen.findByText('Subscription required')).toBeInTheDocument()
  })

  it('does not put SUPER_ADMIN into a read-only warning', async () => {
    renderWithAuth(<SubscriptionBanner />, {
      roles: [ROLE_SUPER_ADMIN],
      subscription: { ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false, usable: false },
    })
    expect(await screen.findByText(/You can still administer this Saving Scheme/i)).toBeInTheDocument()
    expect(screen.queryByText(/temporarily unavailable/i)).not.toBeInTheDocument()
  })
})

describe('DashboardSubscriptionCard', () => {
  it('renders ACTIVE leadership status and billing CTA', () => {
    renderWithAuth(
      <DashboardSubscriptionCard
        subscription={{
          ...trial,
          status: 'ACTIVE',
          effectiveStatus: 'ACTIVE',
          billingCycle: 'MONTHLY',
          currentPeriodEndsAt: '2026-10-16T00:00:00Z',
        }}
        variant="leadership"
      />,
      { roles: [ROLE_PRESIDENT] },
    )
    expect(screen.getByTestId('dashboard-subscription-card')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Manage Subscription' })).toHaveAttribute('href', '/billing')
  })

  it('sends trial leadership to view plans', () => {
    renderWithAuth(
      <DashboardSubscriptionCard subscription={trial} variant="leadership" />,
      { roles: [ROLE_PRESIDENT] },
    )
    expect(screen.getByRole('link', { name: 'View Plans' })).toHaveAttribute('href', '/billing')
  })

  it('sends expired leadership to renew billing', () => {
    renderWithAuth(
      <DashboardSubscriptionCard
        subscription={{ ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false, usable: false }}
        variant="leadership"
      />,
      { roles: [ROLE_PRESIDENT] },
    )
    expect(screen.getByRole('link', { name: 'Renew Subscription' })).toHaveAttribute('href', '/billing')
  })

  it('shows member contact-leadership copy when expired and hides payment CTA', () => {
    renderWithAuth(
      <DashboardSubscriptionCard
        subscription={{ ...trial, effectiveStatus: 'EXPIRED', writeAllowed: false, usable: false }}
        variant="member"
      />,
      { roles: [ROLE_MEMBER] },
    )
    expect(screen.getAllByText(/contact your President/i).length).toBeGreaterThan(0)
    expect(screen.queryByTestId('dashboard-billing-cta')).not.toBeInTheDocument()
  })

  it('hides the member card while the subscription is healthy', () => {
    const { container } = renderWithAuth(<DashboardSubscriptionCard subscription={trial} variant="member" />, {
      roles: [ROLE_MEMBER],
    })
    expect(container.querySelector('[data-testid="dashboard-subscription-card"]')).not.toBeInTheDocument()
  })
})
