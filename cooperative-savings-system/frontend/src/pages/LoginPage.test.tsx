import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { LOGIN_SUCCESS_STATE } from '@/features/branding/loginSuccessSplash'
import { login } from '@/shared/api/auth'
import { ROUTES } from '@/shared/constants/routes'
import { ROLE_MEMBER, ROLE_SUPER_ADMIN, type AuthUser, type LoginResponse } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { LoginPage } from './LoginPage'

vi.mock('@/shared/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/auth')>()
  return {
    ...actual,
    login: vi.fn(),
  }
})

const loginMock = vi.mocked(login)

const testUser: AuthUser = {
  id: 'u1',
  username: 'alice',
  email: 'alice@example.com',
  firstName: 'Alice',
  lastName: 'Member',
  fullName: 'Alice Member',
  roles: [ROLE_MEMBER],
  permissions: [],
  cooperativeIds: ['coop-1'],
}

const loginData: LoginResponse = {
  accessToken: 'token-abc',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: testUser,
}

/** Shows where the splash will send the user once it finishes. */
function LoginSuccessProbe() {
  const location = useLocation()
  const next = (location.state as { next?: string } | null)?.next
  return (
    <div>
      login-success-route
      <span data-testid="splash-next">{next}</span>
    </div>
  )
}

function renderLogin(initial: { user: AuthUser | null; accessToken: string | null; status: 'anonymous' | 'authenticated' } = { user: null, accessToken: null, status: 'anonymous' }) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: initial.user,
        accessToken: initial.accessToken,
        selectedCooperativeId: null,
        status: initial.status,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })

  const view = render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter initialEntries={[ROUTES.login]}>
            <Routes>
              <Route path={ROUTES.login} element={<LoginPage />} />
              <Route path={ROUTES.loginSuccess} element={<LoginSuccessProbe />} />
              <Route path="/superadmin" element={<div>superadmin-route</div>} />
              <Route path={ROUTES.dashboard} element={<div>dashboard-route</div>} />
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )

  return { ...view, store }
}

describe('LoginPage post-login splash', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('sends a successful username/password login to the splash before Dashboard', async () => {
    loginMock.mockResolvedValue(loginData)
    const { store } = renderLogin()

    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('login-success-route')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()
    expect(store.getState().auth.status).toBe('authenticated')
    expect(loginMock).toHaveBeenCalledWith(
      { username: 'alice', password: 'password1' },
      expect.anything(),
    )
  })

  it('keeps failed login on the login page', async () => {
    loginMock.mockRejectedValue(new Error('Invalid credentials'))
    renderLogin()

    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      expect(loginMock).toHaveBeenCalled()
    })
    expect(screen.queryByText('login-success-route')).not.toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
  })

  it('sends a MEMBER to the dashboard after the splash', async () => {
    loginMock.mockResolvedValue(loginData)
    renderLogin()
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByTestId('splash-next')).toHaveTextContent(ROUTES.dashboard)
  })

  it('sends a SUPER_ADMIN to the system portal after the splash', async () => {
    const admin: AuthUser = { ...testUser, id: 'a1', username: 'sysadmin', roles: [ROLE_SUPER_ADMIN], cooperativeIds: [] }
    loginMock.mockResolvedValue({ ...loginData, user: admin })
    renderLogin()
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'sysadmin' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByTestId('splash-next')).toHaveTextContent('/superadmin')
  })

  it('sends an already signed-in SUPER_ADMIN revisiting /login straight to the portal', async () => {
    const admin: AuthUser = { ...testUser, id: 'a1', roles: [ROLE_SUPER_ADMIN], cooperativeIds: [] }
    renderLogin({ user: admin, accessToken: 't', status: 'authenticated' })
    expect(await screen.findByText('superadmin-route')).toBeInTheDocument()
  })

  it('sends an already signed-in Saving Scheme user revisiting /login to the dashboard', async () => {
    renderLogin({ user: testUser, accessToken: 't', status: 'authenticated' })
    expect(await screen.findByText('dashboard-route')).toBeInTheDocument()
  })

  it('uses the login-success location state for a real login event', () => {
    expect(LOGIN_SUCCESS_STATE).toEqual({ fromLogin: true, next: ROUTES.dashboard })
  })
})
