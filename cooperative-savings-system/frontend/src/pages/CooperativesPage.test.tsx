import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen } from '@testing-library/react'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { CooperativesPage } from './CooperativesPage'
import { fetchCooperatives } from '@/shared/api/cooperatives'

vi.mock('@/shared/api/cooperatives', () => ({
  fetchCooperatives: vi.fn(),
  createCooperative: vi.fn(),
}))

const fetchCooperativesMock = vi.mocked(fetchCooperatives)

function renderPage() {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'admin-1',
          username: 'superadmin',
          email: 'admin@test.local',
          firstName: 'Super',
          lastName: 'Admin',
          fullName: 'Super Admin',
          roles: [ROLE_SUPER_ADMIN],
          permissions: ['COOPERATIVE_WRITE'],
          cooperativeIds: [],
        },
        accessToken: 'token',
        selectedCooperativeId: null,
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
          <SnackbarProvider>
            <MemoryRouter>
              <CooperativesPage />
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('CooperativesPage onboarding indicator', () => {
  beforeEach(() => {
    fetchCooperativesMock.mockReset()
  })

  it('shows President required for AWAITING_PRESIDENT cooperatives', async () => {
    fetchCooperativesMock.mockResolvedValue({
      content: [
        {
          id: 'coop-1',
          name: 'Incomplete Scheme',
          currency: 'RWF',
          status: 'ACTIVE',
          onboardingState: 'AWAITING_PRESIDENT',
          financialYearStartMonth: 1,
          monthlyContributionAmount: '1000',
          contributionDueDay: 1,
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
    renderPage()
    expect(await screen.findByText('Incomplete Scheme')).toBeInTheDocument()
    expect(screen.getByText('President required')).toBeInTheDocument()
  })

  it('does not show the incomplete chip when onboarding is complete', async () => {
    fetchCooperativesMock.mockResolvedValue({
      content: [
        {
          id: 'coop-2',
          name: 'Complete Scheme',
          currency: 'RWF',
          status: 'ACTIVE',
          onboardingState: 'COMPLETE',
          financialYearStartMonth: 1,
          monthlyContributionAmount: '1000',
          contributionDueDay: 1,
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
    renderPage()
    expect(await screen.findByText('Complete Scheme')).toBeInTheDocument()
    expect(screen.queryByText('President required')).not.toBeInTheDocument()
  })
})
