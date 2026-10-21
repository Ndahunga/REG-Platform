import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { fetchSubscription } from '@/shared/api/subscription'
import { ROLE_PRESIDENT, ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import { FinancialActionButton } from './FinancialActionButton'

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn(),
}))

const fetchMock = vi.mocked(fetchSubscription)

function renderButton(roles: string[]) {
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
          permissions: [],
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
        <FinancialActionButton>Save</FinancialActionButton>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('FinancialActionButton', () => {
  it('disables writes when the selected cooperative is expired', async () => {
    fetchMock.mockResolvedValue({
      id: 's1',
      cooperativeId: 'coop-1',
      status: 'EXPIRED',
      effectiveStatus: 'EXPIRED',
      writeAllowed: false,
      usable: false,
    })
    renderButton([ROLE_PRESIDENT])
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled())
  })

  it('keeps SUPER_ADMIN writes enabled on an expired cooperative', async () => {
    fetchMock.mockResolvedValue({
      id: 's1',
      cooperativeId: 'coop-1',
      status: 'EXPIRED',
      effectiveStatus: 'EXPIRED',
      writeAllowed: false,
      usable: false,
    })
    renderButton([ROLE_SUPER_ADMIN])
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
  })
})
