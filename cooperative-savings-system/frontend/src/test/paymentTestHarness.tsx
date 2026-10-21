import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { AxiosError } from 'axios'
import { SnackbarProvider } from 'notistack'
import type { ReactElement } from 'react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import type {
  MemberPayment,
  PaymentPosting,
  PaymentSummary,
} from '@/shared/types/memberPayment'
import { lightTheme } from '@/theme/theme'

export interface TestUser {
  id: string
  roles: string[]
  permissions: string[]
}

export function renderWithApp(
  ui: ReactElement,
  options: { user: TestUser; cooperativeId?: string | null; route?: string },
) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: options.user.id,
          username: options.user.id,
          email: `${options.user.id}@test.local`,
          firstName: 'Test',
          lastName: 'User',
          fullName: 'Test User',
          roles: options.user.roles,
          permissions: options.user.permissions,
          cooperativeIds: ['c1'],
        },
        accessToken: 'token',
        selectedCooperativeId: options.cooperativeId === undefined ? 'c1' : options.cooperativeId,
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <SnackbarProvider>
            <MemoryRouter initialEntries={[options.route ?? '/payments']}>{ui}</MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

/** An axios error shaped like the backend's {code, message} body. */
export function backendError(status: number, code: string | null, message = 'Backend message'): AxiosError {
  return new AxiosError('failed', String(status), undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: {} as never,
    data: { code, message },
  })
}

/** A request that got no answer at all (timeout, dropped connection). */
export function networkError(): AxiosError {
  return new AxiosError('Network Error', 'ERR_NETWORK')
}

export function pageOf<T>(content: T[]) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, first: true, last: true }
}

export function paymentOf(overrides: Partial<MemberPayment> = {}): MemberPayment {
  return {
    id: 'p1',
    cooperativeId: 'c1',
    memberUserId: 'payer',
    amount: '3500',
    allocatedAmount: '3500',
    currency: 'RWF',
    paymentDate: '2026-10-01',
    paymentMethod: 'MTN_MOMO',
    transactionReference: 'TX123',
    evidenceFileKey: null,
    status: 'SUBMITTED',
    submittedBy: 'payer',
    submittedAt: '2026-10-01T08:00:00Z',
    allocations: [
      { id: 'a1', type: 'CONTRIBUTION', amount: '2000', periodYear: 2026, periodMonth: 9 },
      { id: 'a2', type: 'LOAN_REPAYMENT', amount: '1500', loanId: 'loan-aaaa-1111' },
    ],
    ...overrides,
  }
}

export function summaryOf(overrides: Partial<PaymentSummary> = {}): PaymentSummary {
  return {
    id: 'p1',
    memberUserId: 'payer',
    memberName: 'Pat Payer',
    amount: '3500',
    currency: 'RWF',
    paymentDate: '2026-10-01',
    paymentMethod: 'MTN_MOMO',
    transactionReference: 'TX123',
    status: 'SUBMITTED',
    submittedAt: '2026-10-01T08:00:00Z',
    allocationTypes: ['CONTRIBUTION', 'LOAN_REPAYMENT'],
    ...overrides,
  }
}

export function postingOf(overrides: Partial<PaymentPosting> = {}): PaymentPosting {
  return {
    paymentId: 'p1',
    status: 'VERIFIED',
    amount: '3500',
    verificationMethod: 'PROVIDER_CONFIRMED',
    verificationEvidenceRecorded: true,
    verifiedBy: 'verifier',
    verifiedAt: '2026-10-02T08:00:00Z',
    postingAttempts: 0,
    retryable: false,
    postings: [],
    ...overrides,
  }
}
