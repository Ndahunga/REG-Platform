import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { fetchAuditLog, fetchAuditLogs } from '@/shared/api/auditLogs'
import { ROLE_PRESIDENT, type AuthUser } from '@/shared/types/auth'
import type { AuditLog } from '@/shared/types/auditLog'
import { lightTheme } from '@/theme/theme'
import { AuditLogsPage } from './AuditLogsPage'

vi.mock('@/shared/api/auditLogs', () => ({
  fetchAuditLogs: vi.fn(),
  fetchAuditLog: vi.fn(),
}))

const fetchAuditLogsMock = vi.mocked(fetchAuditLogs)
const fetchAuditLogMock = vi.mocked(fetchAuditLog)

const user: AuthUser = {
  id: 'u1',
  username: 'jane',
  email: 'jane@example.com',
  firstName: 'Jane',
  lastName: 'Doe',
  fullName: 'Jane Doe',
  roles: [ROLE_PRESIDENT],
  permissions: [],
  cooperativeIds: ['coop-1'],
}

const loanApproval: AuditLog = {
  id: 'audit-1',
  userId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  userName: 'Jane Doe',
  username: 'jane',
  cooperativeId: 'coop-1',
  action: 'LOAN_REQUEST',
  entityType: 'Loan',
  entityId: '889a4772-aaaa-bbbb-cccc-dddddddddddd',
  entityLabel: 'John Member',
  previousValues: null,
  newValues:
    '{"status":"PENDING","approvedAmount":"300000.0000","loanId":"889a4772-aaaa-bbbb-cccc-dddddddddddd","userId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}',
  ipAddress: '10.0.0.8',
  userAgent: 'Mozilla/5.0 secret-agent',
  createdAt: '2026-09-07T10:42:00Z',
}

const emptyChanges: AuditLog = {
  ...loanApproval,
  id: 'audit-empty',
  action: 'PASSWORD_CHANGE',
  entityType: 'User',
  entityLabel: 'Jane Doe',
  previousValues: null,
  newValues: null,
  ipAddress: null,
  userAgent: null,
}

const invalidJson: AuditLog = {
  ...loanApproval,
  id: 'audit-invalid',
  action: 'UPDATE',
  previousValues: 'not-json {',
  newValues: 'also-bad',
}

function pageResponse(content: AuditLog[]) {
  return {
    content,
    page: 0,
    size: 10,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  }
}

function renderPage() {
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
          <MemoryRouter>
            <AuditLogsPage />
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('AuditLogsPage presentation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchAuditLogsMock.mockResolvedValue(pageResponse([loanApproval]))
    fetchAuditLogMock.mockResolvedValue(loanApproval)
  })

  it('shows human-readable action labels in the list instead of raw enums', async () => {
    renderPage()
    expect(await screen.findByText('Loan requested')).toBeInTheDocument()
    expect(screen.queryByText('LOAN_REQUEST')).not.toBeInTheDocument()
  })

  it('renders structured change rows in the drawer without a raw JSON code block', async () => {
    const userUi = userEvent.setup()
    const { container } = renderPage()
    await userUi.click(await screen.findByText('Loan requested'))

    expect(await screen.findByText('Summary')).toBeInTheDocument()
    expect(screen.getByText('Jane Doe — Loan requested for John Member.')).toBeInTheDocument()
    expect(screen.getByText('Approved amount')).toBeInTheDocument()
    expect(screen.getByText(/300,000/)).toBeInTheDocument()
    expect(screen.getByText('Pending first approval')).toBeInTheDocument()
    expect(screen.queryByText(/889a4772-aaaa-bbbb-cccc-dddddddddddd/)).not.toBeInTheDocument()
    expect(screen.queryByText('loanId')).not.toBeInTheDocument()
    expect(container.querySelector('pre')).toBeNull()
    expect(screen.queryByText(/Mozilla\/5\.0/)).not.toBeInTheDocument()
    expect(screen.queryByText('secret-agent')).not.toBeInTheDocument()
    expect(screen.getByText('Technical details')).toBeInTheDocument()
    const ip = screen.queryByText('10.0.0.8')
    if (ip) expect(ip).not.toBeVisible()
  })

  it('shows No changes recorded when both payloads are empty', async () => {
    fetchAuditLogsMock.mockResolvedValue(pageResponse([emptyChanges]))
    fetchAuditLogMock.mockResolvedValue(emptyChanges)
    const userUi = userEvent.setup()
    renderPage()
    await userUi.click(await screen.findByText('Password changed'))
    expect(await screen.findByText('No changes recorded.')).toBeInTheDocument()
  })

  it('does not crash when previous/new JSON is invalid', async () => {
    fetchAuditLogsMock.mockResolvedValue(pageResponse([invalidJson]))
    fetchAuditLogMock.mockResolvedValue(invalidJson)
    const userUi = userEvent.setup()
    const { container } = renderPage()
    await userUi.click(await screen.findByText('Updated'))
    expect(await screen.findByText('Details')).toBeInTheDocument()
    expect(screen.getByText('not-json {')).toBeInTheDocument()
    expect(container.querySelector('pre')).toBeNull()
  })

  it('sends raw action values to the filter API while showing labels', async () => {
    const userUi = userEvent.setup()
    renderPage()
    await screen.findByText('Loan requested', {}, { timeout: 15_000 })
    await userUi.click(screen.getByLabelText('Action'))
    await userUi.click(await screen.findByRole('option', { name: 'Loan requested' }, { timeout: 15_000 }))
    await waitFor(
      () => {
        expect(fetchAuditLogsMock).toHaveBeenCalledWith(
          'coop-1',
          expect.objectContaining({ action: 'LOAN_REQUEST' }),
        )
      },
      { timeout: 15_000 },
    )
  }, 20_000)
})
