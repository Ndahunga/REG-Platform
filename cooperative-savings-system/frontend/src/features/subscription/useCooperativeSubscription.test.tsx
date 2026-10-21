import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { renderHook, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer, { setSelectedCooperativeId } from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { AxiosError, AxiosHeaders } from 'axios'
import { cooperativeSubscriptionQueryKey, fetchSubscription } from '@/shared/api/subscription'
import { ROLE_PRESIDENT } from '@/shared/types/auth'
import { isSubscriptionInactiveError } from '@/shared/api/client'
import { useCooperativeSubscription } from './useCooperativeSubscription'

vi.mock('@/shared/api/subscription', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/subscription')>()
  return {
    ...actual,
    fetchSubscription: vi.fn(),
  }
})

const fetchMock = vi.mocked(fetchSubscription)

function wrapper(selectedCooperativeId: string) {
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
          roles: [ROLE_PRESIDENT],
          permissions: [],
          cooperativeIds: ['coop-a', 'coop-b'],
        },
        accessToken: 'token',
        selectedCooperativeId,
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return {
    store,
    Wrapper: ({ children }: { children: React.ReactNode }) => (
      <Provider store={store}>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </Provider>
    ),
  }
}

describe('useCooperativeSubscription', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keys the query by selectedCooperativeId and refetches on switch', async () => {
    fetchMock.mockImplementation(async (id: string) => ({
      id: `sub-${id}`,
      cooperativeId: id,
      status: id === 'coop-a' ? 'TRIAL' : 'EXPIRED',
      effectiveStatus: id === 'coop-a' ? 'TRIAL' : 'EXPIRED',
      writeAllowed: id === 'coop-a',
      usable: id === 'coop-a',
    }))

    const { store, Wrapper } = wrapper('coop-a')
    const { result, rerender } = renderHook(() => useCooperativeSubscription(), { wrapper: Wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.subscription?.cooperativeId).toBe('coop-a')
    expect(result.current.effectiveStatus).toBe('TRIAL')
    expect(result.current.canWrite).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('coop-a')

    store.dispatch(setSelectedCooperativeId('coop-b'))
    rerender()

    await waitFor(() => expect(result.current.subscription?.cooperativeId).toBe('coop-b'))
    expect(result.current.effectiveStatus).toBe('EXPIRED')
    expect(result.current.readOnly).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('coop-b')
  })

  it('does not run without a selected cooperative', () => {
    fetchMock.mockResolvedValue(null)
    const emptyStore = configureStore({
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
            roles: [ROLE_PRESIDENT],
            permissions: [],
            cooperativeIds: [],
          },
          accessToken: 'token',
          selectedCooperativeId: null,
          status: 'authenticated' as const,
        },
        ui: { sidebarOpen: false, themePreference: 'light' as const },
      },
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useCooperativeSubscription(), {
      wrapper: ({ children }) => (
        <Provider store={emptyStore}>
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </Provider>
      ),
    })
    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('subscription query key and 402 handling', () => {
  it('builds a cooperative-scoped query key', () => {
    expect(cooperativeSubscriptionQueryKey('coop-1')).toEqual(['cooperatives', 'coop-1', 'subscription'])
  })

  it('detects subscription errors without treating them as 401', () => {
    const error = new AxiosError('inactive')
    error.response = {
      status: 402,
      data: { success: false, code: 'SUBSCRIPTION_INACTIVE', message: 'inactive' },
      statusText: 'Payment Required',
      headers: {},
      config: { headers: new AxiosHeaders() },
    }
    expect(isSubscriptionInactiveError(error)).toBe(true)
    expect(error.response.status).not.toBe(401)
  })
})
