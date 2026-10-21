import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import {
  createSuperAdmin,
  fetchSuperAdmins,
  updateSuperAdminStatus,
  type SuperAdmin,
} from '@/shared/api/superAdmins'
import { ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { SuperAdminsPage } from './SuperAdminsPage'

vi.mock('@/shared/api/superAdmins', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/superAdmins')>()
  return {
    ...actual,
    fetchSuperAdmins: vi.fn(),
    createSuperAdmin: vi.fn(),
    updateSuperAdminStatus: vi.fn(),
  }
})

const fetchMock = vi.mocked(fetchSuperAdmins)
const createMock = vi.mocked(createSuperAdmin)
const statusMock = vi.mocked(updateSuperAdminStatus)

const me: SuperAdmin = {
  id: 'me',
  firstName: 'Owner',
  lastName: 'Admin',
  fullName: 'Owner Admin',
  username: 'owner',
  email: 'owner@example.com',
  accountStatus: 'ACTIVE',
  createdAt: '2026-01-01T10:00:00Z',
  lastLoginAt: '2026-10-01T08:00:00Z',
  currentUser: true,
}
const other: SuperAdmin = {
  id: 'other',
  firstName: 'Second',
  lastName: 'Admin',
  fullName: 'Second Admin',
  username: 'second',
  email: 'second@example.com',
  accountStatus: 'ACTIVE',
  createdAt: '2026-02-01T10:00:00Z',
  lastLoginAt: null,
  currentUser: false,
}
const dormant: SuperAdmin = {
  ...other,
  id: 'dormant',
  fullName: 'Dormant Admin',
  username: 'dormant',
  email: 'dormant@example.com',
  accountStatus: 'INACTIVE',
}

function renderPage() {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'me',
          username: 'owner',
          email: 'owner@example.com',
          firstName: 'Owner',
          lastName: 'Admin',
          fullName: 'Owner Admin',
          roles: [ROLE_SUPER_ADMIN],
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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <SnackbarProvider>
            <MemoryRouter>
              <SuperAdminsPage />
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
  return { invalidate }
}

async function fillCreateForm(user: ReturnType<typeof userEvent.setup>, overrides: Record<string, string> = {}) {
  const values = {
    'First name': 'New',
    'Last name': 'Admin',
    Username: 'newadmin',
    Email: 'New.Admin@Example.com',
    Password: 'StrongPass1!',
    'Confirm password': 'StrongPass1!',
    ...overrides,
  }
  const dialog = await screen.findByRole('dialog')
  for (const [label, value] of Object.entries(values)) {
    const field = within(dialog).getByLabelText(new RegExp(`^${label}`))
    await user.clear(field)
    if (value) await user.type(field, value)
  }
  return dialog
}

describe('SuperAdminsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchMock.mockResolvedValue([me, other])
  })

  it('shows the title, subtitle and add button', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Super Admins' })).toBeInTheDocument()
    expect(
      screen.getByText('Manage administrators with platform-wide access to OuWealth Community.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Add Super Admin/ })).toBeInTheDocument()
  })

  it('shows a loading state while the list loads', () => {
    fetchMock.mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(screen.queryByText('Owner Admin')).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows an error state with retry', async () => {
    fetchMock.mockRejectedValueOnce(new Error('boom'))
    const user = userEvent.setup()
    renderPage()
    expect(await screen.findByText('boom')).toBeInTheDocument()
    fetchMock.mockResolvedValueOnce([me])
    await user.click(screen.getByRole('button', { name: /retry|try again/i }))
    expect(await screen.findByText('Owner Admin')).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    fetchMock.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No super admins found')).toBeInTheDocument()
  })

  it('lists admins, marking the signed-in one with You', async () => {
    renderPage()
    expect(await screen.findByText('Owner Admin')).toBeInTheDocument()
    expect(screen.getByText('Second Admin')).toBeInTheDocument()
    expect(screen.getAllByText('You')).toHaveLength(1)
    const ownerRow = screen.getByText('Owner Admin').closest('tr')
    expect(ownerRow).not.toBeNull()
    expect(within(ownerRow as HTMLElement).getByText('You')).toBeInTheDocument()
    for (const header of ['Name', 'Username', 'Email', 'Status', 'Last login', 'Created', 'Actions']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
  })

  it('does not offer deactivation for the current user', async () => {
    renderPage()
    const ownerRow = (await screen.findByText('Owner Admin')).closest('tr') as HTMLElement
    expect(within(ownerRow).getByRole('button', { name: 'Deactivate' })).toBeDisabled()
    const otherRow = screen.getByText('Second Admin').closest('tr') as HTMLElement
    expect(within(otherRow).getByRole('button', { name: 'Deactivate' })).toBeEnabled()
  })

  it('validates the create form and never submits invalid data', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: /Add Super Admin/ }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Create Super Admin' }))
    expect(await within(dialog).findByText('First name is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Last name is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Username is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Email is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Password is required')).toBeInTheDocument()
    expect(createMock).not.toHaveBeenCalled()

    await fillCreateForm(user, { Email: 'nope', 'Confirm password': 'different' })
    await user.click(within(dialog).getByRole('button', { name: 'Create Super Admin' }))
    expect(await within(dialog).findByText('Enter a valid email address')).toBeInTheDocument()
    expect(within(dialog).getByText('Passwords must match')).toBeInTheDocument()
    expect(createMock).not.toHaveBeenCalled()
  }, 20000)

  it('masks the password fields', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: /Add Super Admin/ }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText(/^Password/)).toHaveAttribute('type', 'password')
    expect(within(dialog).getByLabelText(/^Confirm password/)).toHaveAttribute('type', 'password')
  })

  it('creates an admin, closes the dialog, refetches and confirms', async () => {
    const user = userEvent.setup()
    createMock.mockResolvedValue({ ...other, id: 'new', fullName: 'New Admin', username: 'newadmin' })
    const { invalidate } = renderPage()
    await user.click(await screen.findByRole('button', { name: /Add Super Admin/ }))
    const dialog = await fillCreateForm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Create Super Admin' }))

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1))
    expect(createMock.mock.calls[0][0]).toEqual({
      firstName: 'New',
      lastName: 'Admin',
      username: 'newadmin',
      email: 'new.admin@example.com',
      phone: undefined,
      password: 'StrongPass1!',
    })
    expect(await screen.findByText('Super admin account created successfully.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['platform', 'super-admins'] })
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2))
    expect(screen.queryByText('StrongPass1!')).not.toBeInTheDocument()
  })

  it('keeps the dialog open and shows the backend conflict message', async () => {
    const user = userEvent.setup()
    createMock.mockRejectedValue(
      Object.assign(new Error('Username already exists'), {
        isAxiosError: true,
        response: { status: 409, data: { message: 'Username already exists' } },
      }),
    )
    renderPage()
    await user.click(await screen.findByRole('button', { name: /Add Super Admin/ }))
    const dialog = await fillCreateForm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Create Super Admin' }))

    expect(await screen.findByTestId('super-admin-form-error')).toHaveTextContent('Username already exists')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    // Safe data is preserved; the password is not.
    expect(within(dialog).getByLabelText(/^Username/)).toHaveValue('newadmin')
    expect(within(dialog).getByLabelText(/^Password/)).toHaveValue('')
  })

  it('disables submit and shows progress while creating', async () => {
    const user = userEvent.setup()
    createMock.mockReturnValue(new Promise(() => {}))
    renderPage()
    await user.click(await screen.findByRole('button', { name: /Add Super Admin/ }))
    const dialog = await fillCreateForm(user)
    await user.click(within(dialog).getByRole('button', { name: 'Create Super Admin' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Create Super Admin' })).toBeDisabled())
    expect(within(dialog).getByRole('progressbar')).toBeInTheDocument()
  })

  it('asks for confirmation before deactivating and refetches afterwards', async () => {
    const user = userEvent.setup()
    statusMock.mockResolvedValue({ ...other, accountStatus: 'INACTIVE' })
    const { invalidate } = renderPage()
    const row = (await screen.findByText('Second Admin')).closest('tr') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Deactivate' }))

    const dialog = await screen.findByRole('dialog')
    expect(statusMock).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(statusMock).toHaveBeenCalledWith('other', 'INACTIVE'))
    expect(await screen.findByText('Super admin account deactivated')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['platform', 'super-admins'] })
  })

  it('can cancel a deactivation without calling the API', async () => {
    const user = userEvent.setup()
    renderPage()
    const row = (await screen.findByText('Second Admin')).closest('tr') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Deactivate' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(statusMock).not.toHaveBeenCalled()
  })

  it('reactivates an inactive admin directly', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValue([me, dormant])
    statusMock.mockResolvedValue({ ...dormant, accountStatus: 'ACTIVE' })
    renderPage()
    const row = (await screen.findByText('Dormant Admin')).closest('tr') as HTMLElement
    expect(within(row).getByText('Inactive')).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Reactivate' }))
    await waitFor(() => expect(statusMock).toHaveBeenCalledWith('dormant', 'ACTIVE'))
    expect(await screen.findByText('Super admin account reactivated')).toBeInTheDocument()
  })

  it('surfaces a backend refusal such as the last-active-admin rule', async () => {
    const user = userEvent.setup()
    statusMock.mockRejectedValue(
      Object.assign(new Error('The last active super admin cannot be deactivated'), {
        isAxiosError: true,
        response: { status: 409, data: { message: 'The last active super admin cannot be deactivated' } },
      }),
    )
    renderPage()
    const row = (await screen.findByText('Second Admin')).closest('tr') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Deactivate' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Deactivate' }))
    expect(await screen.findByText('The last active super admin cannot be deactivated')).toBeInTheDocument()
  })
})
