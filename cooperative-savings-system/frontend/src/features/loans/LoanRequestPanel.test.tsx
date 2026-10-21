import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Component, type ReactNode } from 'react'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer, { setCredentials } from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_MEMBER, ROLE_PRESIDENT, type AuthUser } from '@/shared/types/auth'
import type { LoanApplicationForm, LoanSettings } from '@/shared/types/loan'
import type { Member } from '@/shared/types/member'
import { lightTheme } from '@/theme/theme'
import { LoanRequestPanel } from './LoanRequestPanel'

vi.mock('@/shared/api/loanSettings', () => ({
  fetchLoanSettings: vi.fn(),
}))

vi.mock('@/shared/api/members', () => ({
  fetchMembers: vi.fn(),
}))

vi.mock('@/shared/api/loans', () => ({
  fetchLoanApplicationPreview: vi.fn(),
  fetchLoanEligibility: vi.fn(),
  previewLoanRepayment: vi.fn(),
  createLoan: vi.fn(),
}))

import { fetchLoanSettings } from '@/shared/api/loanSettings'
import {
  createLoan,
  fetchLoanApplicationPreview,
  fetchLoanEligibility,
  previewLoanRepayment,
} from '@/shared/api/loans'
import { fetchMembers } from '@/shared/api/members'

const fetchLoanSettingsMock = vi.mocked(fetchLoanSettings)
const fetchMembersMock = vi.mocked(fetchMembers)
const fetchPreviewMock = vi.mocked(fetchLoanApplicationPreview)
const fetchEligibilityMock = vi.mocked(fetchLoanEligibility)
const previewRepaymentMock = vi.mocked(previewLoanRepayment)
const createLoanMock = vi.mocked(createLoan)

const COOP = 'coop-1'

const memberUser: AuthUser = {
  id: 'member-1',
  username: 'member.jane',
  email: 'member@example.com',
  firstName: 'Jane',
  lastName: 'Member',
  fullName: 'Jane Member',
  roles: [ROLE_MEMBER],
  permissions: ['LOAN_READ'],
  cooperativeIds: [COOP],
}

const officerUser: AuthUser = {
  id: 'officer-1',
  username: 'pres',
  email: 'pres@example.com',
  firstName: 'Pat',
  lastName: 'President',
  fullName: 'Pat President',
  roles: [ROLE_PRESIDENT],
  permissions: ['LOAN_READ', 'LOAN_WRITE'],
  cooperativeIds: [COOP],
}

const settings: LoanSettings = {
  interestRatePercent: 2,
  interestType: 'FLAT',
  maxLoanAmount: 1000000,
  maxTermMonths: 24,
  allowMemberRequests: true,
}

const applicationForm: LoanApplicationForm = {
  cooperativeId: COOP,
  cooperativeName: 'Test Ikimina',
  memberUserId: 'member-1',
  memberFullName: 'Jane Member',
}

const members = {
  content: [
    {
      userId: 'member-2',
      username: 'bob',
      firstName: 'Bob',
      lastName: 'Borrower',
      fullName: 'Bob Borrower',
      email: 'bob@example.com',
    } as unknown as Member,
  ],
  page: 0,
  size: 200,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** Mirrors production: a render exception unmounts the page (white screen) unless something catches it. */
class CrashCatcher extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  render() {
    if (this.state.error) {
      return <div data-testid="render-crashed">{this.state.error.message}</div>
    }
    return this.props.children
  }
}

function renderPanel(user: AuthUser, isAdmin: boolean) {
  const store = configureStore({ reducer: { auth: authReducer, ui: uiReducer } })
  store.dispatch(setCredentials({ user, accessToken: 'token' }))
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={lightTheme}>
          <SnackbarProvider>
            <MemoryRouter>
              <CrashCatcher>
                <LoanRequestPanel cooperativeId={COOP} isAdmin={isAdmin} />
              </CrashCatcher>
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

const HOOK_ERRORS = /Rendered (more|fewer) hooks|order of Hooks|Invalid hook call/i

describe('LoanRequestPanel hook lifecycle', () => {
  let consoleError: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.clearAllMocks()
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    fetchMembersMock.mockResolvedValue(members)
    fetchPreviewMock.mockResolvedValue(applicationForm)
    fetchEligibilityMock.mockResolvedValue({ eligible: true, reason: 'Eligible' })
    previewRepaymentMock.mockResolvedValue({
      prorataEnabled: false,
      scheduleFinalized: true,
      installments: [],
    } as never)
    createLoanMock.mockResolvedValue({ id: 'loan-1' } as never)
  })

  afterEach(() => {
    consoleError.mockRestore()
  })

  function expectNoHookErrors() {
    expect(screen.queryByTestId('render-crashed')).not.toBeInTheDocument()
    const logged = consoleError.mock.calls.map((call: unknown[]) => call.map(String).join(' ')).join('\n')
    expect(logged).not.toMatch(HOOK_ERRORS)
  }

  it('member Apply Loan: loading -> settings resolve -> form renders without a hook-order crash', async () => {
    const settingsRequest = deferred<LoanSettings>()
    fetchLoanSettingsMock.mockReturnValue(settingsRequest.promise)

    renderPanel(memberUser, false)

    // 1-2. settings still loading: loading state, no form
    expect(screen.queryByRole('heading', { name: 'Apply for Loan' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply for Loan' })).not.toBeInTheDocument()
    expectNoHookErrors()

    // 3-4. settings resolve: the loan form appears
    await act(async () => {
      settingsRequest.resolve(settings)
    })
    expect(await screen.findByRole('heading', { name: 'Apply for Loan' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply for Loan' })).toBeInTheDocument()

    // 5. no white screen / hook error
    expectNoHookErrors()
  })

  it('officer Issue Loan: loading -> settings resolve -> form renders without a hook-order crash', async () => {
    const settingsRequest = deferred<LoanSettings>()
    fetchLoanSettingsMock.mockReturnValue(settingsRequest.promise)

    renderPanel(officerUser, true)

    expect(screen.queryByRole('heading', { name: 'Issue a loan' })).not.toBeInTheDocument()
    expectNoHookErrors()

    await act(async () => {
      settingsRequest.resolve(settings)
    })
    expect(await screen.findByRole('heading', { name: 'Issue a loan' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create loan' })).toBeInTheDocument()
    expectNoHookErrors()
  })

  it('member: application preview still loading after settings resolved does not break hook order', async () => {
    fetchLoanSettingsMock.mockResolvedValue(settings)
    const previewRequest = deferred<LoanApplicationForm>()
    fetchPreviewMock.mockReturnValue(previewRequest.promise)

    renderPanel(memberUser, false)

    // settings are in, preview is not: still the loading state
    await waitFor(() => expect(fetchLoanSettingsMock).toHaveBeenCalled())
    await act(async () => {
      await Promise.resolve()
    })
    expect(screen.queryByRole('heading', { name: 'Apply for Loan' })).not.toBeInTheDocument()
    expectNoHookErrors()

    await act(async () => {
      previewRequest.resolve(applicationForm)
    })
    expect(await screen.findByRole('heading', { name: 'Apply for Loan' })).toBeInTheDocument()
    expect(screen.getByText('Test Ikimina')).toBeInTheDocument()
    expectNoHookErrors()
  })

  it('member: preview resolving before settings also renders the form', async () => {
    const settingsRequest = deferred<LoanSettings>()
    fetchLoanSettingsMock.mockReturnValue(settingsRequest.promise)

    renderPanel(memberUser, false)
    await waitFor(() => expect(fetchPreviewMock).toHaveBeenCalled())
    expectNoHookErrors()

    await act(async () => {
      settingsRequest.resolve(settings)
    })
    expect(await screen.findByRole('heading', { name: 'Apply for Loan' })).toBeInTheDocument()
    expectNoHookErrors()
  })

  it('settings error renders the error state and later retry succeeds without a hook-order crash', async () => {
    fetchLoanSettingsMock.mockRejectedValueOnce(new Error('boom'))
    renderPanel(officerUser, true)

    expect(await screen.findByText('boom')).toBeInTheDocument()
    expectNoHookErrors()

    fetchLoanSettingsMock.mockResolvedValue(settings)
    await userEvent.click(screen.getByRole('button', { name: /retry|try again/i }))
    expect(await screen.findByRole('heading', { name: 'Issue a loan' })).toBeInTheDocument()
    expectNoHookErrors()
  })

  it('member requests disabled: shows the warning after settings load, without a hook-order crash', async () => {
    fetchLoanSettingsMock.mockResolvedValue({ ...settings, allowMemberRequests: false })

    renderPanel(memberUser, false)

    expect(
      await screen.findByText('Member loan requests are disabled for this Saving Scheme.'),
    ).toBeInTheDocument()
    expectNoHookErrors()
  })

  describe('dependent queries stay disabled until their inputs exist', () => {
    it('schedule preview waits for settings and an amount > 0', async () => {
      const settingsRequest = deferred<LoanSettings>()
      fetchLoanSettingsMock.mockReturnValue(settingsRequest.promise)

      renderPanel(memberUser, false)
      expect(previewRepaymentMock).not.toHaveBeenCalled()

      await act(async () => {
        settingsRequest.resolve(settings)
      })
      await screen.findByRole('heading', { name: 'Apply for Loan' })
      expect(previewRepaymentMock).not.toHaveBeenCalled() // no amount yet

      await userEvent.type(screen.getByLabelText(/amount/i), '0')
      await act(async () => {
        await Promise.resolve()
      })
      expect(previewRepaymentMock).not.toHaveBeenCalled() // amount must be > 0

      await userEvent.clear(screen.getByLabelText(/amount/i))
      await userEvent.type(screen.getByLabelText(/amount/i), '50000')
      await waitFor(() => expect(previewRepaymentMock).toHaveBeenCalled())
      expect(previewRepaymentMock.mock.calls.at(-1)?.[1]).toMatchObject({ amount: '50000' })
      expectNoHookErrors()
    })

    it('member eligibility waits until the form is ready, then uses the signed-in member id', async () => {
      const settingsRequest = deferred<LoanSettings>()
      fetchLoanSettingsMock.mockReturnValue(settingsRequest.promise)

      renderPanel(memberUser, false)
      await waitFor(() => expect(fetchPreviewMock).toHaveBeenCalled())
      expect(fetchEligibilityMock).not.toHaveBeenCalled()

      await act(async () => {
        settingsRequest.resolve(settings)
      })
      await screen.findByRole('heading', { name: 'Apply for Loan' })
      await waitFor(() => expect(fetchEligibilityMock).toHaveBeenCalled())
      expect(fetchEligibilityMock.mock.calls[0]?.[0]).toBe(COOP)
      expect(fetchEligibilityMock.mock.calls[0]?.[1]).toBe('member-1')
    })

    it('officer eligibility waits for a selected member id', async () => {
      const settingsRequest = deferred<LoanSettings>()
      fetchLoanSettingsMock.mockReturnValue(settingsRequest.promise)

      renderPanel(officerUser, true)
      expect(fetchEligibilityMock).not.toHaveBeenCalled()

      await act(async () => {
        settingsRequest.resolve(settings)
      })
      await screen.findByRole('heading', { name: 'Issue a loan' })
      await act(async () => {
        await Promise.resolve()
      })
      expect(fetchEligibilityMock).not.toHaveBeenCalled() // no member selected yet

      await userEvent.click(screen.getAllByRole('combobox')[0])
      await userEvent.click(within(await screen.findByRole('listbox')).getByText(/Bob Borrower/))

      await waitFor(() => expect(fetchEligibilityMock).toHaveBeenCalled())
      expect(fetchEligibilityMock.mock.calls.at(-1)?.[1]).toBe('member-2')
      expectNoHookErrors()
    })
  })
})

describe('LoanRequestPanel input validation', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchLoanSettingsMock.mockResolvedValue(settings)
    fetchMembersMock.mockResolvedValue(members)
    fetchPreviewMock.mockResolvedValue(applicationForm)
    fetchEligibilityMock.mockResolvedValue({
      eligible: true,
      reason: 'Eligible',
      existingLoanAmount: '0',
      amountAlreadyRepaid: '0',
      outstandingBalance: '0',
    } as never)
    previewRepaymentMock.mockResolvedValue({
      prorataEnabled: false,
      scheduleFinalized: true,
      installments: [],
    } as never)
    createLoanMock.mockResolvedValue({ id: 'loan-1' } as never)
  })

  async function openForm() {
    renderPanel(memberUser, false)
    await screen.findByRole('heading', { name: 'Apply for Loan' })
    await waitFor(() => expect(fetchEligibilityMock).toHaveBeenCalled())
  }

  const amountField = () => screen.getByLabelText(/^amount/i)
  const submitButton = () => screen.getByRole('button', { name: 'Apply for Loan' })

  it.each(['abc', '1e3', '-5'])(
    'typing "%s" as the amount does not crash the page or reach the preview/eligibility requests',
    async (typed) => {
      await openForm()
      fetchEligibilityMock.mockClear()
      await userEvent.type(amountField(), typed)
      await act(async () => {
        await Promise.resolve()
      })
      expect(screen.queryByTestId('render-crashed')).not.toBeInTheDocument()
      // Intermediate keystrokes such as "1" are valid amounts; the invalid text itself must never be sent.
      const isValid = (value: unknown) => value === '' || /^\d+(\.\d+)?$/.test(String(value))
      for (const call of previewRepaymentMock.mock.calls) expect(isValid(call[1].amount)).toBe(true)
      for (const call of fetchEligibilityMock.mock.calls) expect(isValid(call[2])).toBe(true)
    },
  )

  it('does not submit an invalid amount and keeps what was typed', async () => {
    await openForm()
    await userEvent.type(amountField(), 'abc')
    await userEvent.type(screen.getByLabelText(/purpose/i), 'Farm inputs')
    await userEvent.click(submitButton())
    expect(await screen.findByText('Enter a valid amount')).toBeInTheDocument()
    expect(createLoanMock).not.toHaveBeenCalled()
    expect(amountField()).toHaveValue('abc')
  })

  it('does not submit a term above 600 months', async () => {
    await openForm()
    await userEvent.type(amountField(), '50000')
    await userEvent.type(screen.getByLabelText(/purpose/i), 'Farm inputs')
    await userEvent.type(screen.getByLabelText(/repayment period/i), '601')
    await userEvent.click(submitButton())
    expect(await screen.findByText('Enter a valid term in months (1 to 600)')).toBeInTheDocument()
    expect(createLoanMock).not.toHaveBeenCalled()
  })

  it('submits a valid request exactly once', async () => {
    await openForm()
    await userEvent.type(amountField(), '50000')
    await userEvent.type(screen.getByLabelText(/purpose/i), 'Farm inputs')
    await userEvent.type(screen.getByLabelText(/repayment period/i), '12')
    await userEvent.click(submitButton())
    await waitFor(() => expect(createLoanMock).toHaveBeenCalledTimes(1))
    expect(createLoanMock.mock.calls[0][1]).toMatchObject({ amount: '50000', termMonths: 12 })
  })
})
