import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { LOGIN_SUCCESS_STATE } from '@/features/branding/loginSuccessSplash'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import { onboardCooperative } from '@/shared/api/onboarding'
import { ROUTES } from '@/shared/constants/routes'
import { ROLE_MEMBER, ROLE_PRESIDENT, type AuthUser, type LoginResponse } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { SignupPage } from './SignupPage'

vi.mock('@/shared/api/onboarding', () => ({
  onboardCooperative: vi.fn(),
}))

const onboardMock = vi.mocked(onboardCooperative)

const testUser: AuthUser = {
  id: 'u1',
  username: 'pat.president',
  email: 'pat@example.com',
  firstName: 'Pat',
  lastName: 'President',
  fullName: 'Pat President',
  roles: [ROLE_MEMBER, ROLE_PRESIDENT],
  permissions: [],
  cooperativeIds: ['coop-new'],
}

const loginData: LoginResponse = {
  accessToken: 'token-onboard',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: testUser,
}

function renderSignup() {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: null,
        accessToken: null,
        selectedCooperativeId: null,
        status: 'anonymous' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  const view = render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter initialEntries={[ROUTES.signup]}>
            <Routes>
              <Route path={ROUTES.signup} element={<SignupPage />} />
              <Route path={ROUTES.loginSuccess} element={<div>login-success-route</div>} />
              <Route path={ROUTES.dashboard} element={<div>dashboard-route</div>} />
              <Route path={ROUTES.login} element={<div>login-route</div>} />
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )

  return { ...view, store }
}

function schemeNameField() {
  return screen.getByRole('textbox', { name: /^Name/ })
}

function fillStep1() {
  fillStep1Fields()
  fireEvent.click(screen.getByRole('radio', { name: /Hybrid Savings/ }))
}

function fillStep1Fields() {
  fireEvent.change(schemeNameField(), { target: { value: 'Public Scheme' } })
  fireEvent.change(screen.getByLabelText(/Contact email/i), {
    target: { value: 'scheme@example.com' },
  })
  fireEvent.change(screen.getByLabelText(/Contact phone/i), { target: { value: '0781234567' } })
  fireEvent.change(screen.getByLabelText(/Address/i), { target: { value: 'Kigali' } })
  fireEvent.change(screen.getByLabelText(/Registration date/i), {
    target: { value: '2024-01-15' },
  })
  fireEvent.change(screen.getByLabelText(/Monthly contribution/i), { target: { value: '5000' } })
}

function fillStep2() {
  fireEvent.change(screen.getByLabelText(/^First name/i), { target: { value: 'Pat' } })
  fireEvent.change(screen.getByLabelText(/^Last name/i), { target: { value: 'President' } })
  fireEvent.change(screen.getByLabelText(/^Username/i), { target: { value: 'pat.president' } })
  fireEvent.change(screen.getByLabelText(/^Email/i), { target: { value: 'pat@example.com' } })
  fireEvent.change(screen.getByLabelText(/^Phone/i), { target: { value: '0781112233' } })
  fireEvent.change(screen.getByLabelText(/^Password/i), { target: { value: 'SignupPass1!' } })
  fireEvent.change(screen.getByLabelText(/Confirm password/i), { target: { value: 'SignupPass1!' } })
}

async function clickButton(name: string) {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name }))
  })
}

async function goNext() {
  await clickButton('Next')
}

describe('SignupPage onboarding wizard', { timeout: 15_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('validates saving scheme details before leaving step 1', async () => {
    renderSignup()
    await goNext()
    expect(await screen.findByText('Name is required')).toBeInTheDocument()
    expect(schemeNameField()).toBeInTheDocument()
    expect(screen.queryByLabelText(/^First name/i)).not.toBeInTheDocument()
  })

  it('offers the three operating models and selects none by default', () => {
    renderSignup()
    expect(screen.getByText('Saving Scheme Operating Model')).toBeInTheDocument()
    for (const name of [/Accumulating Savings/, /Rotating Savings/, /Hybrid Savings/]) {
      expect(screen.getByRole('radio', { name })).not.toBeChecked()
    }
  })

  it('requires an operating model before leaving step 1', async () => {
    renderSignup()
    fillStep1Fields()
    await goNext()
    expect((await screen.findAllByText('Select a Saving Scheme operating model')).length).toBeGreaterThan(0)
    expect(screen.queryByLabelText(/^First name/i)).not.toBeInTheDocument()
  })

  it('validates creator details on step 2', async () => {
    renderSignup()
    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    await goNext()
    expect(await screen.findByText('First name is required')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Password/i)).toBeInTheDocument()
  })

  it('navigates back without losing scheme details', async () => {
    renderSignup()
    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    await clickButton('Back')
    expect(await screen.findByRole('textbox', { name: /^Name/ })).toHaveValue('Public Scheme')
    expect(screen.queryByLabelText(/Registration number/i)).not.toBeInTheDocument()
  })

  it('shows review details, pricing, and hides the password', async () => {
    renderSignup()
    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    fillStep2()
    await goNext()

    expect(await screen.findByRole('button', { name: 'Create Saving Scheme' })).toBeInTheDocument()
    expect(screen.getByText('Public Scheme')).toBeInTheDocument()
    expect(screen.getByText('pat@example.com')).toBeInTheDocument()
    // the review step never mentions a registration number
    expect(screen.queryByText(/registration number/i)).not.toBeInTheDocument()
    expect(screen.getByText('4 months free')).toBeInTheDocument()
    expect(screen.getByText('RWF 2,000/month')).toBeInTheDocument()
    expect(screen.getByText('RWF 18,000/year (save 25%)')).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Password/i)).not.toBeInTheDocument()
    expect(screen.queryByText('SignupPass1!')).not.toBeInTheDocument()
  })

  it('stores credentials, selects the new cooperative, and uses the login-success splash', async () => {
    onboardMock.mockResolvedValue(loginData)
    const { store } = renderSignup()

    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    fillStep2()
    await goNext()
    await clickButton('Create Saving Scheme')

    expect(screen.getByText('login-success-route')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()
    expect(onboardMock).toHaveBeenCalledTimes(1)
    const payload = onboardMock.mock.calls[0][0]
    expect(payload.cooperative.name).toBe('Public Scheme')
    expect(payload.cooperative.currency).toBe('RWF')
    expect(payload.cooperative.operatingModel).toBe('HYBRID')
    // no registration number is sent, and none is invented from the scheme name
    expect(payload.cooperative).not.toHaveProperty('registrationNumber')
    expect(JSON.stringify(payload)).not.toMatch(/registrationNumber/i)
    expect(payload.creator).toEqual({
      firstName: 'Pat',
      lastName: 'President',
      username: 'pat.president',
      email: 'pat@example.com',
      phone: '0781112233',
      password: 'SignupPass1!',
    })
    expect(payload.creator).not.toHaveProperty('role')
    expect(payload.cooperative).not.toHaveProperty('subscriptionInitialization')
    expect(store.getState().auth.status).toBe('authenticated')
    expect(store.getState().auth.accessToken).toBe('token-onboard')
    expect(store.getState().auth.selectedCooperativeId).toBe('coop-new')
    expect(LOGIN_SUCCESS_STATE).toEqual({ fromLogin: true, next: ROUTES.dashboard })
  })

  it('renders API validation errors without leaving the wizard', async () => {
    onboardMock.mockRejectedValue(new Error('Username already exists'))
    renderSignup()

    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    fillStep2()
    await goNext()
    await clickButton('Create Saving Scheme')

    expect(screen.getByText('Username already exists')).toBeInTheDocument()
    expect(screen.queryByText('login-success-route')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create Saving Scheme' })).toBeInTheDocument()
  })

  it('has no Registration Number field, hint or placeholder on step 1', () => {
    renderSignup()
    expect(screen.queryByLabelText(/registration number/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/registration number/i)).not.toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/RCA\//i)).not.toBeInTheDocument()
    // the registration DATE is a different, still-required field
    expect(screen.getByLabelText(/Registration date/i)).toBeInTheDocument()
  })

  it('lets step 1 proceed without any registration number', async () => {
    renderSignup()
    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    expect(screen.queryByText(/registration number/i)).not.toBeInTheDocument()
  })

  it('does not mention a registration number in the signup failure copy (EN and RW)', () => {
    expect(en.errors.signupFailed).not.toMatch(/registration/i)
    expect(rw.errors.signupFailed).not.toMatch(/registration|iyandikish/i)
  })

  it('renders compact medium Back, Next and Create buttons grouped at the end, not full-width stretchers', async () => {
    renderSignup()
    expect(screen.getByTestId('signup-actions')).toBeInTheDocument()
    const next = screen.getByRole('button', { name: 'Next' })
    expect(next).toHaveClass('MuiButton-sizeMedium')
    expect(next).not.toHaveClass('MuiButton-sizeLarge')

    fillStep1()
    await goNext()
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Back' })).toHaveClass('MuiButton-sizeMedium')
    expect(screen.getByRole('button', { name: 'Next' })).toHaveClass('MuiButton-sizeMedium')

    fillStep2()
    await goNext()
    const create = await screen.findByRole('button', { name: 'Create Saving Scheme' })
    expect(create).toHaveClass('MuiButton-sizeMedium')
    expect(create).not.toBeDisabled()
  })

  it('keeps Next, Back and Create reachable and activatable from the keyboard', async () => {
    onboardMock.mockResolvedValue(loginData)
    const user = userEvent.setup({ delay: null })
    renderSignup()
    fillStep1()

    screen.getByRole('button', { name: 'Next' }).focus()
    expect(screen.getByRole('button', { name: 'Next' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()

    screen.getByRole('button', { name: 'Back' }).focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('textbox', { name: /^Name/ })).toHaveValue('Public Scheme')

    await goNext()
    fillStep2()
    await goNext()
    screen.getByRole('button', { name: 'Create Saving Scheme' }).focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByText('login-success-route')).toBeInTheDocument()
    expect(onboardMock).toHaveBeenCalledTimes(1)
  })

  it('keeps a mobile-friendly stepped structure with all step labels visible', () => {
    renderSignup()
    expect(screen.getByText('Saving Scheme Details')).toBeInTheDocument()
    expect(screen.getByText('Your Details')).toBeInTheDocument()
    expect(screen.getByText('Review & Create')).toBeInTheDocument()
    // the tagline replaces the prominent trial intro on step 1; the trial is explained again at review
    expect(screen.queryByTestId('trial-pricing-note')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument()
  })
})
