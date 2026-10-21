import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { fetchSubscription } from '@/shared/api/subscription'
import { ROLE_PRESIDENT, ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import type { CooperativeSubscription } from '@/shared/types/cooperative'
import { ReportsExportPanel } from './ReportsExportPanel'

vi.mock('@/shared/api/reports', () => ({
  fetchReportTypes: vi.fn().mockResolvedValue([
    { type: 'CONTRIBUTIONS', label: 'Contributions', selfScoped: false },
    { type: 'INVESTMENTS', label: 'Investments', selfScoped: false },
    { type: 'FULL_FINANCIAL', label: 'Full Financial', selfScoped: false },
  ]),
  fetchWhatsAppStatus: vi.fn().mockResolvedValue({ configured: true }),
  exportReport: vi.fn(),
  shareReportViaWhatsApp: vi.fn(),
}))

vi.mock('@/shared/api/members', () => ({
  fetchMembers: vi.fn().mockResolvedValue({ content: [] }),
}))

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
}

function renderPanel(
  subscription: CooperativeSubscription,
  roles: string[] = [ROLE_PRESIDENT],
) {
  fetchMock.mockResolvedValue(subscription)
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
          roles,
          permissions: ['REPORT_READ'],
          cooperativeIds: ['coop-1'],
        },
        accessToken: 'token',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <SnackbarProvider>
          <ReportsExportPanel cooperativeId="coop-1" />
        </SnackbarProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('ReportsExportPanel subscription sharing', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps report download enabled and sharing available during TRIAL', async () => {
    renderPanel(trial)
    const downloads = (await screen.findAllByRole('button', { name: 'Download PDF' })).filter(
      (button) => button.getAttribute('type') !== 'submit',
    )
    expect(downloads.length).toBeGreaterThan(0)
    downloads.forEach((button) => expect(button).toBeEnabled())
    const shares = screen
      .getAllByRole('button', { name: 'Share via WhatsApp' })
      .filter((button) => button.className.includes('MuiButton-sizeSmall'))
    expect(shares.length).toBeGreaterThan(0)
    shares.forEach((button) => expect(button).toBeEnabled())
  })

  it('keeps report download enabled when expired but disables WhatsApp share', async () => {
    renderPanel({
      ...trial,
      status: 'EXPIRED',
      storedStatus: 'EXPIRED',
      effectiveStatus: 'EXPIRED',
      writeAllowed: false,
      usable: false,
    })
    const downloads = (await screen.findAllByRole('button', { name: 'Download PDF' })).filter(
      (button) => button.getAttribute('type') !== 'submit',
    )
    downloads.forEach((button) => expect(button).toBeEnabled())
    const shares = screen.getAllByRole('button', { name: 'Share via WhatsApp' })
    shares.forEach((button) => expect(button).toBeDisabled())
  })

  it('shows the subscription share tooltip when expired', async () => {
    const user = userEvent.setup()
    renderPanel({
      ...trial,
      effectiveStatus: 'EXPIRED',
      writeAllowed: false,
      usable: false,
    })
    const share = await screen.findAllByRole('button', { name: 'Share via WhatsApp' })
    await user.hover(share[0].parentElement ?? share[0])
    expect(
      await screen.findByText('Subscription renewal is required before sharing reports.'),
    ).toBeInTheDocument()
  })

  it('keeps SUPER_ADMIN sharing enabled on an expired cooperative', async () => {
    renderPanel(
      {
        ...trial,
        effectiveStatus: 'EXPIRED',
        writeAllowed: false,
        usable: false,
      },
      [ROLE_SUPER_ADMIN],
    )
    await waitFor(() => {
      const shares = screen
        .getAllByRole('button', { name: 'Share via WhatsApp' })
        .filter((button) => button.className.includes('MuiButton-sizeSmall'))
      expect(shares.length).toBeGreaterThan(0)
      shares.forEach((button) => expect(button).toBeEnabled())
    })
  })
})
