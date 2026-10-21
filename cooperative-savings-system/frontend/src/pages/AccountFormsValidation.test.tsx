import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { changePassword, login } from '@/shared/api/auth'
import { ROLE_MEMBER, type AuthUser } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { ChangePasswordPage } from './ChangePasswordPage'
import { LoginPage } from './LoginPage'

vi.mock('@/shared/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/auth')>()
  return { ...actual, login: vi.fn(), changePassword: vi.fn() }
})

const user: AuthUser = {
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

function renderPage(page: React.ReactNode, options: { anonymous?: boolean } = {}) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: options.anonymous
        ? { user: null, accessToken: null, selectedCooperativeId: null, status: 'anonymous' as const }
        : { user, accessToken: 't', selectedCooperativeId: 'coop-1', status: 'authenticated' as const },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter>{page}</MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('LoginPage password rule', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(login).mockResolvedValue({ accessToken: 't', tokenType: 'Bearer', expiresIn: 900, user } as never)
  })

  it('requires a password but does not impose a minimum length (the backend only checks non-blank)', async () => {
    const u = userEvent.setup()
    renderPage(<LoginPage />, { anonymous: true })
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'legacy' } })
    await u.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Password is required')).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText(/^password/i), { target: { value: 'abc' } })
    await u.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(login).toHaveBeenCalledTimes(1))
    expect(vi.mocked(login).mock.calls[0][0]).toMatchObject({ username: 'legacy', password: 'abc' })
  })
})

describe('ChangePasswordPage validation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(changePassword).mockResolvedValue({ accessToken: 't2', user } as never)
  })

  const fill = (current: string, next: string, confirm: string) => {
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: current } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: next } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: confirm } })
  }
  const save = () => screen.getByRole('button', { name: 'Save password' })

  it('rejects a new password equal to the current one without calling the API', async () => {
    const u = userEvent.setup()
    renderPage(<ChangePasswordPage />)
    fill('SamePass123', 'SamePass123', 'SamePass123')
    await u.click(save())
    expect(await screen.findByText('New password must be different from current password')).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('rejects a short, over-long or mismatched new password', async () => {
    const u = userEvent.setup()
    renderPage(<ChangePasswordPage />)
    fill('OldPass123', 'short', 'short')
    await u.click(save())
    expect(await screen.findByText('At least 8 characters')).toBeInTheDocument()

    fill('OldPass123', 'x'.repeat(129), 'x'.repeat(129))
    await u.click(save())
    expect(await screen.findByText('At most 128 characters')).toBeInTheDocument()

    fill('OldPass123', 'NewPass1234', 'Different1234')
    await u.click(save())
    expect(await screen.findByText('Passwords must match')).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('submits exactly once for a valid change', async () => {
    const u = userEvent.setup()
    renderPage(<ChangePasswordPage />)
    fill('OldPass123', 'NewPass1234', 'NewPass1234')
    await u.click(save())
    await waitFor(() => expect(changePassword).toHaveBeenCalledTimes(1))
    expect(vi.mocked(changePassword).mock.calls[0][0]).toEqual({
      currentPassword: 'OldPass123',
      newPassword: 'NewPass1234',
    })
  })
})
