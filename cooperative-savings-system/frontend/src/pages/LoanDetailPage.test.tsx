import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import axios from 'axios'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer, { setSelectedCooperativeId } from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_MEMBER, ROLE_PRESIDENT, ROLE_SUPER_ADMIN, type AuthUser } from '@/shared/types/auth'
import type { Loan } from '@/shared/types/loan'
import { lightTheme } from '@/theme/theme'
import { LoanDetailPage } from './LoanDetailPage'
import { RouteErrorBoundary } from '@/shared/components/RouteErrorBoundary'
import { fetchSubscription } from '@/shared/api/subscription'

vi.mock('@/shared/api/loans', () => ({
  fetchLoan: vi.fn(),
  fetchLoanRepayments: vi.fn(),
  exportLoanSchedule: vi.fn(),
  fetchLoanScheduleWhatsAppStatus: vi.fn(),
  shareLoanScheduleViaWhatsApp: vi.fn(),
  approveLoan: vi.fn(),
  rejectLoan: vi.fn(),
  disburseLoan: vi.fn(),
  writeOffLoan: vi.fn(),
  createLoanRepayment: vi.fn(),
}))

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn(),
}))

import {
  approveLoan,
  exportLoanSchedule,
  fetchLoan,
  fetchLoanRepayments,
  fetchLoanScheduleWhatsAppStatus,
  shareLoanScheduleViaWhatsApp,
} from '@/shared/api/loans'

const fetchLoanMock = vi.mocked(fetchLoan)
const fetchLoanRepaymentsMock = vi.mocked(fetchLoanRepayments)
const exportLoanScheduleMock = vi.mocked(exportLoanSchedule)
const fetchWhatsAppStatusMock = vi.mocked(fetchLoanScheduleWhatsAppStatus)
const shareWhatsAppMock = vi.mocked(shareLoanScheduleViaWhatsApp)
const approveLoanMock = vi.mocked(approveLoan)
const fetchSubscriptionMock = vi.mocked(fetchSubscription)

const officer: AuthUser = {
  id: 'u1',
  username: 'jane',
  email: 'jane@example.com',
  firstName: 'Jane',
  lastName: 'Officer',
  fullName: 'Jane Officer',
  roles: [ROLE_PRESIDENT],
  permissions: ['LOAN_READ'],
  cooperativeIds: ['coop-1'],
}

const member: AuthUser = {
  id: 'member-1',
  username: 'member.jane',
  email: 'member@example.com',
  firstName: 'Jane',
  lastName: 'Member',
  fullName: 'Jane Member',
  roles: [ROLE_MEMBER],
  permissions: ['LOAN_READ'],
  cooperativeIds: ['coop-1'],
}

const loan: Loan = {
  id: 'loan-uuid-should-not-appear',
  cooperativeId: 'coop-1',
  memberUserId: 'member-1',
  memberName: 'Jane Doe',
  fullName: 'Jane Doe',
  requestedAmount: '80000',
  approvedAmount: '80000',
  principalAmount: '80000',
  interestRatePercent: 2,
  interestType: 'FLAT',
  termMonths: 2,
  interestAmount: 3200,
  scheduleFinalized: true,
  repaymentDateModel: 'SAME_DAY_OF_MONTH',
  status: 'ACTIVE',
  repaymentSchedule: [
    {
      installmentNumber: 1,
      dueDate: '2026-02-15',
      openingPrincipalBalance: 80000,
      paymentAmount: 41600,
      principalComponent: 40000,
      interestComponent: 1600,
      status: 'PENDING',
    },
  ],
}

function httpError(status: number, message: string) {
  return new axios.AxiosError(
    message,
    String(status),
    undefined,
    undefined,
    {
      status,
      statusText: message,
      data: { message },
      headers: {},
      config: { headers: {} } as never,
    },
  )
}

function renderPage(
  configured = true,
  options: {
    user?: AuthUser
    cooperativeId?: string | null
    loanResult?: Loan | null
    loanError?: unknown
    whatsappError?: unknown
    writeAllowed?: boolean
    effectiveStatus?: 'TRIAL' | 'ACTIVE' | 'EXPIRED' | 'NONE'
  } = {},
) {
  const user = options.user ?? officer
  const writeAllowed = options.writeAllowed ?? true
  const effectiveStatus = options.effectiveStatus ?? 'TRIAL'
  fetchSubscriptionMock.mockResolvedValue({
    id: 'sub-1',
    cooperativeId: 'coop-1',
    status: effectiveStatus,
    storedStatus: effectiveStatus,
    effectiveStatus,
    writeAllowed,
    usable: writeAllowed,
  })
  if (options.loanError) {
    fetchLoanMock.mockRejectedValue(options.loanError)
  } else if (options.loanResult === null) {
    fetchLoanMock.mockResolvedValue(undefined as never)
  } else {
    fetchLoanMock.mockResolvedValue(options.loanResult ?? loan)
  }
  fetchLoanRepaymentsMock.mockResolvedValue([])
  if (options.whatsappError) {
    fetchWhatsAppStatusMock.mockRejectedValue(options.whatsappError)
  } else {
    fetchWhatsAppStatusMock.mockResolvedValue({ configured })
  }
  exportLoanScheduleMock.mockResolvedValue({ filename: 'repayment-schedule-jane-doe.pdf' })
  shareWhatsAppMock.mockResolvedValue({
    sent: true,
    recipient: '250788123456',
    filename: 'repayment-schedule-jane-doe.pdf',
  })

  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user,
        accessToken: 'token',
        selectedCooperativeId: options.cooperativeId === undefined ? 'coop-1' : options.cooperativeId,
        status: 'authenticated' as const,
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
          <SnackbarProvider>
            <MemoryRouter initialEntries={['/loans/loan-uuid-should-not-appear']}>
              <Routes>
                <Route path="/loans/:loanId" element={<LoanDetailPage />} />
              </Routes>
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
  return { ...view, store }
}

describe('LoanDetailPage repayment schedule export', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps Download PDF, removes Excel, and shows Share via WhatsApp', async () => {
    renderPage()
    expect(await screen.findByRole('button', { name: 'Download PDF' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download Excel' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Share via WhatsApp' })).toBeInTheDocument()
    expect(screen.queryByText(/wa\.me/i)).not.toBeInTheDocument()
  })

  it('downloads the PDF independently of WhatsApp', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Download PDF' }))
    await waitFor(() => {
      expect(exportLoanScheduleMock).toHaveBeenCalledWith(
        'coop-1',
        'loan-uuid-should-not-appear',
        'pdf',
      )
    })
    expect(shareWhatsAppMock).not.toHaveBeenCalled()
    expect(await screen.findByText('Schedule downloaded')).toBeInTheDocument()
  })

  it('opens the recipient dialog and reuses Reports phone validation', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Share via WhatsApp' }))
    expect(await screen.findByText('Share schedule via WhatsApp')).toBeInTheDocument()
    const phone = screen.getByLabelText('Recipient phone number')
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()

    fireEvent.change(phone, { target: { value: 'not-a-phone' } })
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
    expect(shareWhatsAppMock).not.toHaveBeenCalled()

    fireEvent.change(phone, { target: { value: '0788123456' } })
    await user.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => {
      expect(shareWhatsAppMock).toHaveBeenCalledWith(
        'coop-1',
        'loan-uuid-should-not-appear',
        '0788123456',
      )
    })
    expect(
      await screen.findByText('Schedule sent via WhatsApp (repayment-schedule-jane-doe.pdf)'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/loan-uuid-should-not-appear/)).not.toBeInTheDocument()
  }, 15000)

  it('shows a friendly error when WhatsApp send fails', async () => {
    const user = userEvent.setup()
    renderPage()
    shareWhatsAppMock.mockRejectedValueOnce(new Error('WhatsApp could not send the schedule'))
    await user.click(await screen.findByRole('button', { name: 'Share via WhatsApp' }))
    fireEvent.change(await screen.findByLabelText('Recipient phone number'), {
      target: { value: '0788123456' },
    })
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('WhatsApp could not send the schedule')).toBeInTheDocument()
  }, 15000)

  it('disables share when WhatsApp is not configured and still allows PDF download', async () => {
    const user = userEvent.setup()
    renderPage(false)
    const share = await screen.findByRole('button', { name: 'Share via WhatsApp' })
    expect(share).toBeDisabled()
    await user.click(await screen.findByRole('button', { name: 'Download PDF' }))
    await waitFor(() => {
      expect(exportLoanScheduleMock).toHaveBeenCalled()
    })
    expect(shareWhatsAppMock).not.toHaveBeenCalled()
  })

  it('disables schedule WhatsApp share when the subscription is expired', async () => {
    const user = userEvent.setup()
    renderPage(true, { writeAllowed: false, effectiveStatus: 'EXPIRED' })
    const share = await screen.findByRole('button', { name: 'Share via WhatsApp' })
    expect(share).toBeDisabled()
    await user.hover(share.parentElement ?? share)
    expect(
      await screen.findByText('Subscription renewal is required before sharing reports.'),
    ).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Download PDF' }))
    await waitFor(() => expect(exportLoanScheduleMock).toHaveBeenCalled())
    expect(shareWhatsAppMock).not.toHaveBeenCalled()
  })

  it('keeps SUPER_ADMIN schedule sharing enabled when expired', async () => {
    renderPage(true, {
      writeAllowed: false,
      effectiveStatus: 'EXPIRED',
      user: { ...officer, roles: [ROLE_SUPER_ADMIN] },
    })
    expect(await screen.findByRole('button', { name: 'Share via WhatsApp' })).toBeEnabled()
  })
})

describe('LoanDetailPage blank-screen stability', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders a valid officer loan detail', async () => {
    renderPage()
    expect(await screen.findByText('Loan — Jane Doe')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('Repayment schedule')).toBeInTheDocument()
  })

  it('renders a member own loan detail', async () => {
    renderPage(true, { user: member })
    expect(await screen.findByText('Loan — Jane Doe')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeInTheDocument()
  })

  it('shows Access Denied for a member 403 without crashing', async () => {
    renderPage(true, { user: member, loanError: httpError(403, 'Access denied') })
    expect(await screen.findByText('Access denied')).toBeInTheDocument()
    expect(screen.getByText('You can only view your own loans.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to loans' })).toBeInTheDocument()
    expect(screen.queryByText('Repayment schedule')).not.toBeInTheDocument()
  })

  it('shows a visible not-found state for 404', async () => {
    renderPage(true, { loanError: httpError(404, 'Loan not found') })
    expect(await screen.findByText('Loan not found')).toBeInTheDocument()
    expect(
      screen.getByText('This loan does not exist or is not in the selected Saving Scheme.'),
    ).toBeInTheDocument()
  })

  it('shows a visible generic error state for 500', async () => {
    renderPage(true, { loanError: httpError(500, 'Internal server error') })
    expect(await screen.findByText('Unable to load')).toBeInTheDocument()
    expect(screen.getByText('Internal server error')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('shows Select a Saving Scheme when cooperativeId is missing', () => {
    renderPage(true, { cooperativeId: null })
    expect(screen.getByText('Select a Saving Scheme')).toBeInTheDocument()
    expect(
      screen.getByText('Choose a Saving Scheme in the top bar to manage loans.'),
    ).toBeInTheDocument()
    expect(fetchLoanMock).not.toHaveBeenCalled()
  })

  it('does not throw when selectedCooperativeId goes from set to null', async () => {
    const { store } = renderPage()
    expect(await screen.findByText('Loan — Jane Doe')).toBeInTheDocument()
    expect(() => {
      act(() => {
        store.dispatch(setSelectedCooperativeId(null))
      })
    }).not.toThrow()
    expect(screen.getByText('Select a Saving Scheme')).toBeInTheDocument()
  })

  it('renders null schedule amounts as em dashes without crashing', async () => {
    renderPage(true, {
      loanResult: {
        ...loan,
        regularMonthlyInterest: null,
        repaymentSchedule: [
          {
            installmentNumber: 1,
            dueDate: null,
            principalComponent: null,
            interestComponent: null,
            paymentAmount: null,
            status: 'PENDING',
          },
        ],
      },
    })
    expect(await screen.findByText('Repayment schedule')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('renders a null guarantor amount as an em dash', async () => {
    renderPage(true, {
      loanResult: {
        ...loan,
        guarantor: {
          id: 'g-1',
          loanId: 'loan-uuid-should-not-appear',
          guarantorUserId: 'g1',
          guarantorName: 'Paul Guarantor',
          guaranteedAmount: null,
          status: 'PENDING',
        },
      },
    })
    expect(await screen.findByText('Paul Guarantor')).toBeInTheDocument()
    expect(screen.getByText('Guaranteed amount').parentElement).toHaveTextContent('—')
  })

  it('shows a visible unavailable state when the request succeeds without loan data', async () => {
    renderPage(true, { loanResult: null })
    expect(await screen.findByText('Loan unavailable')).toBeInTheDocument()
    expect(
      screen.getByText('This loan could not be displayed. Try again or return to the loans list.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Repayment schedule')).not.toBeInTheDocument()
  })

  it('keeps loan detail visible when WhatsApp status fails', async () => {
    renderPage(true, { whatsappError: httpError(404, 'Not found') })
    expect(await screen.findByText('Loan — Jane Doe')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Share via WhatsApp' })).toBeDisabled()
  })

  it('catches an unexpected render error in the loan detail route boundary', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Boom(): never {
      throw new Error('forced loan detail crash')
    }
    render(
      <MemoryRouter>
        <ThemeProvider theme={lightTheme}>
          <RouteErrorBoundary>
            <Boom />
          </RouteErrorBoundary>
        </ThemeProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to loans' })).toBeInTheDocument()
    expect(screen.queryByText('forced loan detail crash')).not.toBeInTheDocument()
    vi.restoreAllMocks()
  })
})

describe('LoanDetailPage approval due date cleanup', () => {
  const approver: AuthUser = {
    ...officer,
    id: 'approver-1',
    permissions: ['LOAN_READ', 'LOAN_APPROVE', 'FUND_AUTHORIZE'],
  }

  const pendingLoan: Loan = {
    ...loan,
    status: 'PENDING',
    scheduleFinalized: false,
    dueDate: null,
    disbursementDate: null,
    repaymentSchedule: [],
  }

  beforeEach(() => {
    vi.clearAllMocks()
    approveLoanMock.mockResolvedValue({
      ...pendingLoan,
      status: 'AWAITING_SECOND_APPROVAL',
    })
  })

  it('does not show Due Date on first approval and submits without dueDate', async () => {
    const user = userEvent.setup()
    renderPage(true, { user: approver, loanResult: pendingLoan })
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('Approve loan')).toBeInTheDocument()
    expect(screen.getByLabelText('Approved amount')).toBeInTheDocument()
    expect(screen.getByLabelText('Repayment period (months)')).toBeInTheDocument()
    expect(screen.queryByLabelText('Due date')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Itariki yo kurangiza')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Maturity date')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('Approve this loan?')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => {
      expect(approveLoanMock).toHaveBeenCalledWith('coop-1', 'loan-uuid-should-not-appear', {
        approvedAmount: '80000',
        termMonths: 2,
      })
    })
    const payload = approveLoanMock.mock.calls[0]?.[2]
    expect(payload).not.toHaveProperty('dueDate')
  })

  it('does not show Due Date on second approval', async () => {
    const user = userEvent.setup()
    renderPage(true, {
      user: approver,
      loanResult: {
        ...pendingLoan,
        status: 'AWAITING_SECOND_APPROVAL',
        firstApprovedBy: 'other-officer',
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('Approve loan')).toBeInTheDocument()
    expect(screen.queryByLabelText('Due date')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Itariki yo kurangiza')).not.toBeInTheDocument()
  })

  it('keeps amount and term validation', async () => {
    const user = userEvent.setup()
    renderPage(true, { user: approver, loanResult: pendingLoan })
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    const amount = await screen.findByLabelText('Approved amount')
    await user.clear(amount)
    await user.type(amount, 'not-money')
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('Enter a valid amount')).toBeInTheDocument()
    expect(approveLoanMock).not.toHaveBeenCalled()
    expect(screen.queryByText('Approve this loan?')).not.toBeInTheDocument()
  })

  it('still displays maturity date after disbursement', async () => {
    renderPage(true, {
      loanResult: {
        ...loan,
        status: 'ACTIVE',
        dueDate: '2026-06-15',
        disbursementDate: '2026-01-15',
      },
    })
    expect(await screen.findByText('Maturity date')).toBeInTheDocument()
    expect(screen.getByText('2026-06-15')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })
})
