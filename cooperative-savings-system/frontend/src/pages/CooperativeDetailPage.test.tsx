import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { CooperativeDetailPage } from './CooperativeDetailPage'
import {
  assignCooperativePresident,
  classifyCooperativeOperatingModel,
  fetchCooperative,
  fetchCooperativeSubscription,
} from '@/shared/api/cooperatives'

vi.mock('@/shared/api/cooperatives', () => ({
  fetchCooperative: vi.fn(),
  fetchCooperativeSubscription: vi.fn(),
  assignCooperativePresident: vi.fn(),
  classifyCooperativeOperatingModel: vi.fn(),
  updateCooperative: vi.fn(),
  updateCooperativeStatus: vi.fn(),
  uploadCooperativeLogo: vi.fn(),
}))

const fetchCooperativeMock = vi.mocked(fetchCooperative)
const fetchSubscriptionMock = vi.mocked(fetchCooperativeSubscription)
const assignPresidentMock = vi.mocked(assignCooperativePresident)
const classifyMock = vi.mocked(classifyCooperativeOperatingModel)

const incomplete = {
  id: 'coop-1',
  name: 'Need President',
  currency: 'RWF',
  status: 'ACTIVE' as const,
  onboardingState: 'AWAITING_PRESIDENT' as const,
  financialYearStartMonth: 1,
  monthlyContributionAmount: '1000',
  contributionDueDay: 1,
  registrationNumber: 'RCA/2024/0123',
}

function renderPage(
  base = '/cooperatives',
  user: { roles: string[]; permissions: string[] } = { roles: [ROLE_SUPER_ADMIN], permissions: ['COOPERATIVE_WRITE'] },
) {
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
          roles: user.roles,
          permissions: user.permissions,
          cooperativeIds: [],
        },
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
          <SnackbarProvider>
            <MemoryRouter initialEntries={[`${base}/coop-1`]}>
              <Routes>
                <Route path={`${base}/:id`} element={<CooperativeDetailPage />} />
              </Routes>
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('CooperativeDetailPage onboarding', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchCooperativeMock.mockResolvedValue(incomplete)
    fetchSubscriptionMock.mockResolvedValue({
      id: 'sub-1',
      cooperativeId: 'coop-1',
      status: 'TRIAL',
      trialStartedAt: '2026-01-15T00:00:00Z',
      trialEndsAt: '2026-05-15T00:00:00Z',
    })
  })

  it('shows the incomplete warning, Assign President action, and trial status', async () => {
    renderPage()
    expect(await screen.findByText('Need President')).toBeInTheDocument()
    expect(screen.getAllByText('President required').length).toBeGreaterThan(0)
    expect(
      screen.getByText(
        'This Saving Scheme will remain in incomplete onboarding until a President is assigned.',
      ),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Assign President' }).length).toBeGreaterThan(0)
    expect(screen.getByText(/Free trial started/i)).toBeInTheDocument()
  })

  it('offers Open Support Workspace inside the system portal', async () => {
    renderPage('/superadmin/cooperatives')
    const link = await screen.findByTestId('open-support-workspace')
    expect(link).toHaveTextContent('Open Support Workspace')
    expect(link).toHaveAttribute('href', '/superadmin/cooperatives/coop-1/dashboard')
    // Management of the scheme stays on this page.
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument()
  })

  it('does not offer the support workspace outside the system portal', async () => {
    renderPage()
    await screen.findByText('Need President')
    expect(screen.queryByTestId('open-support-workspace')).not.toBeInTheDocument()
  })

  it('assigns a President through the administrators wrapper and clears the warning', async () => {
    const user = userEvent.setup()
    assignPresidentMock.mockResolvedValue({
      userId: 'user-1',
      firstName: 'Pat',
      lastName: 'President',
      username: 'pat.president',
      email: 'pat@test.local',
      membershipStatus: 'ACTIVE',
      accountStatus: 'ACTIVE',
      roleInCooperative: 'PRESIDENT',
    })
    fetchCooperativeMock.mockResolvedValue({ ...incomplete, onboardingState: 'COMPLETE' })
    fetchCooperativeMock.mockResolvedValueOnce(incomplete)

    renderPage()
    await user.click((await screen.findAllByRole('button', { name: 'Assign President' }))[0])
    const firstName = await screen.findByLabelText(/^First name/)
    fireEvent.change(firstName, { target: { value: 'Pat' } })
    fireEvent.change(screen.getByLabelText(/^Last name/), { target: { value: 'President' } })
    fireEvent.change(screen.getByLabelText(/^Username/), { target: { value: 'pat.president' } })
    fireEvent.change(screen.getByLabelText(/^Email/), { target: { value: 'pat@test.local' } })
    await user.click(screen.getAllByRole('button', { name: 'Assign President' }).at(-1)!)

    await waitFor(() => {
      expect(assignPresidentMock).toHaveBeenCalledWith('coop-1', {
        username: 'pat.president',
        email: 'pat@test.local',
        firstName: 'Pat',
        lastName: 'President',
        phone: undefined,
        temporaryPassword: undefined,
      })
    })
    await waitFor(() => {
      expect(
        screen.queryByText(
          'This Saving Scheme will remain in incomplete onboarding until a President is assigned.',
        ),
      ).not.toBeInTheDocument()
    })
  }, 15000)
})

describe('CooperativeDetailPage operating model', () => {
  const legacy = { ...incomplete, onboardingState: 'COMPLETE' as const, operatingModel: null }

  beforeEach(() => {
    vi.clearAllMocks()
    fetchSubscriptionMock.mockResolvedValue({
      id: 'sub-1',
      cooperativeId: 'coop-1',
      status: 'NONE',
    })
  })

  it('shows the saved operating model', async () => {
    fetchCooperativeMock.mockResolvedValue({ ...legacy, operatingModel: 'ROTATING' })
    renderPage()
    expect(await screen.findByText('Rotating Savings')).toBeInTheDocument()
    expect(screen.queryByTestId('operating-model-not-configured')).not.toBeInTheDocument()
  })

  it('shows the not-configured state for a legacy scheme and lets an authorized admin classify it', async () => {
    const user = userEvent.setup()
    fetchCooperativeMock.mockResolvedValue(legacy)
    classifyMock.mockResolvedValue({ ...legacy, operatingModel: 'HYBRID' })
    renderPage()

    const alert = await screen.findByTestId('operating-model-not-configured')
    expect(alert).toHaveTextContent('Operating model not configured')
    await user.click(screen.getByRole('button', { name: 'Choose operating model' }))

    // needs a model and an explicit confirmation before anything is sent
    await user.click(screen.getByRole('button', { name: 'Save operating model' }))
    expect(classifyMock).not.toHaveBeenCalled()
    await user.click(screen.getByRole('radio', { name: /Hybrid Savings/ }))
    await user.click(screen.getByRole('button', { name: 'Save operating model' }))
    expect(classifyMock).not.toHaveBeenCalled()
    expect(screen.getByText('Confirm that you understand before saving')).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: /cannot be changed here/ }))
    await user.click(screen.getByRole('button', { name: 'Save operating model' }))
    await waitFor(() =>
      expect(classifyMock).toHaveBeenCalledWith('coop-1', { operatingModel: 'HYBRID', confirmed: true }),
    )
  }, 20000)

  it('hides the classification control from users without COOPERATIVE_WRITE', async () => {
    fetchCooperativeMock.mockResolvedValue(legacy)
    renderPage('/cooperatives', { roles: ['MEMBER'], permissions: [] })
    expect(await screen.findByTestId('operating-model-not-configured')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Choose operating model' })).not.toBeInTheDocument()
  })
})
