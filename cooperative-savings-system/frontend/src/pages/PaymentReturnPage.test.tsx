import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { BILLING_RETURN_STORAGE_KEY, fetchSubscriptionPayment } from '@/shared/api/billing'
import { ROLE_PRESIDENT, type AuthUser } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { PaymentReturnPage } from './PaymentReturnPage'

vi.mock('@/shared/api/billing', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/billing')>()
  return {
    ...actual,
    fetchSubscriptionPayment: vi.fn(),
  }
})

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
}))

const fetchPaymentMock = vi.mocked(fetchSubscriptionPayment)

function renderReturn(path: string) {
  const user: AuthUser = {
    id: 'u1',
    username: 'pat',
    email: 'pat@test.local',
    firstName: 'Pat',
    lastName: 'Leader',
    fullName: 'Pat Leader',
    roles: [ROLE_PRESIDENT],
    permissions: [],
    cooperativeIds: ['coop-1'],
  }
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user,
        accessToken: 'token',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path="/billing/payment-return" element={<PaymentReturnPage />} />
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('PaymentReturnPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('shows verifying state while payment remains PENDING', async () => {
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-1',
      billingCycle: 'ANNUAL',
      paymentChannel: 'CARD',
      status: 'PENDING',
      currency: 'RWF',
      amount: '18000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
    })
    renderReturn('/billing/payment-return?paymentId=pay-1&cooperativeId=coop-1&status=successful')
    expect(await screen.findByTestId('payment-return-pending')).toHaveTextContent('Verifying your payment')
    expect(screen.getByTestId('payment-return-pending')).toHaveTextContent('still being processed')
    expect(screen.queryByTestId('payment-return-success')).not.toBeInTheDocument()
    expect(fetchPaymentMock).toHaveBeenCalledWith('coop-1', 'pay-1')
  })

  it('shows SUCCESS only after backend verification', async () => {
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-1',
      billingCycle: 'ANNUAL',
      paymentChannel: 'CARD',
      status: 'SUCCESS',
      currency: 'RWF',
      amount: '18000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
      paidAt: '2026-09-01T10:01:00Z',
    })
    renderReturn('/billing/payment-return?paymentId=pay-1&cooperativeId=coop-1&status=successful')
    expect(await screen.findByTestId('payment-return-success')).toHaveTextContent('Payment successful')
  })

  it('shows FAILED after backend verification', async () => {
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-1',
      billingCycle: 'MONTHLY',
      paymentChannel: 'CARD',
      status: 'FAILED',
      currency: 'RWF',
      amount: '2000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
      failedAt: '2026-09-01T10:01:00Z',
    })
    renderReturn('/billing/payment-return?paymentId=pay-1&cooperativeId=coop-1')
    expect(await screen.findByTestId('payment-return-failed')).toHaveTextContent('Payment was not completed')
  })

  it('shows temporary verification failure without claiming payment failed', async () => {
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-1',
      billingCycle: 'MONTHLY',
      paymentChannel: 'CARD',
      status: 'PENDING',
      currency: 'RWF',
      amount: '2000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
      verificationUnavailable: true,
    })
    renderReturn('/billing/payment-return?paymentId=pay-1&cooperativeId=coop-1&status=successful')
    expect(await screen.findByTestId('payment-return-verify-unavailable')).toHaveTextContent(
      "couldn't verify your payment right now",
    )
    expect(screen.queryByTestId('payment-return-success')).not.toBeInTheDocument()
    expect(screen.queryByTestId('payment-return-failed')).not.toBeInTheDocument()
  })

  it('prefers selected cooperative over a crafted cooperativeId query', async () => {
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-1',
      billingCycle: 'MONTHLY',
      paymentChannel: 'CARD',
      status: 'PENDING',
      currency: 'RWF',
      amount: '2000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
    })
    renderReturn('/billing/payment-return?paymentId=pay-1&cooperativeId=coop-other&status=successful')
    expect(await screen.findByTestId('payment-return-pending')).toBeInTheDocument()
    expect(fetchPaymentMock).toHaveBeenCalledWith('coop-1', 'pay-1')
  })

  it('resolves paymentId from stored context and tx_ref hint', async () => {
    sessionStorage.setItem(
      BILLING_RETURN_STORAGE_KEY,
      JSON.stringify({ cooperativeId: 'coop-1', paymentId: 'pay-stored' }),
    )
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-stored',
      billingCycle: 'MONTHLY',
      paymentChannel: 'CARD',
      status: 'PENDING',
      currency: 'RWF',
      amount: '2000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
    })
    renderReturn(
      '/billing/payment-return?status=successful&tx_ref=ouwealth-sub-11111111-1111-4111-8111-111111111111',
    )
    expect(await screen.findByTestId('payment-return-pending')).toBeInTheDocument()
    expect(fetchPaymentMock).toHaveBeenCalledWith('coop-1', 'pay-stored')
  })

  it('shows missing state when payment cannot be identified', async () => {
    renderReturn('/billing/payment-return?status=successful')
    expect(await screen.findByTestId('payment-return-missing')).toHaveTextContent(
      'could not identify this payment',
    )
    expect(fetchPaymentMock).not.toHaveBeenCalled()
  })

  it('links back to billing', async () => {
    fetchPaymentMock.mockResolvedValue({
      id: 'pay-1',
      billingCycle: 'MONTHLY',
      paymentChannel: 'CARD',
      status: 'SUCCESS',
      currency: 'RWF',
      amount: '2000.0000',
      initiatedAt: '2026-09-01T10:00:00Z',
    })
    renderReturn('/billing/payment-return?paymentId=pay-1&cooperativeId=coop-1')
    expect(await screen.findByRole('link', { name: 'Back to billing' })).toHaveAttribute('href', '/billing')
  })
})
