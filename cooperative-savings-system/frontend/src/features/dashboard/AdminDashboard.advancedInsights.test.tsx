import { ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, within } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { AdminDashboard } from '@/features/dashboard/AdminDashboard'
import type {
  DashboardAdvancedInsights,
  DashboardInsights,
  DashboardMemberInsights,
  DashboardSummary,
} from '@/shared/types/dashboard'
import {
  ROLE_LOAN_OFFICER,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
} from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'

const summary: DashboardSummary = {
  totalMembers: 10,
  activeMembers: 9,
  regularContributionsTotal: 1000,
  specialContributionsTotal: 200,
  actualContributionsTotal: 1200,
  availableGroupFunds: 5000,
  outstandingLoanPrincipal: 1600000,
  overdueLoansCount: 0,
  currency: 'RWF',
}

const insights: DashboardInsights = {
  period: { year: 2026, month: 9, previousYear: 2026, previousMonth: 8 },
  contributions: {
    currentMonth: 1200000,
    previousMonth: 1000000,
    changePercent: 20,
    changeState: 'UP',
  },
  loans: {
    issuedCountCurrentMonth: 8,
    issuedAmountCurrentMonth: 700000,
    issuedCountPreviousMonth: 6,
    issuedAmountPreviousMonth: 500000,
    issuedAmountChangePercent: 40,
    issuedAmountChangeState: 'UP',
    repaidCurrentMonth: 420000,
    repaidPreviousMonth: 380000,
    repaidChangePercent: 10.5,
    repaidChangeState: 'UP',
    outstandingPrincipal: 1600000,
  },
  fines: {
    issuedCountCurrentMonth: 5,
    issuedAmountCurrentMonth: 30000,
    collectedCurrentMonth: 22000,
    collectedPreviousMonth: 25000,
    collectedChangePercent: -12,
    collectedChangeState: 'DOWN',
  },
  currency: 'RWF',
}

const memberInsights: DashboardMemberInsights = {
  period: { start: '2026-01-01', end: '2026-09-20' },
  topContributors: [{ memberId: 'm1', displayName: 'Jane Doe', amount: 450000, rank: 1 }],
  fineFollowUp: [],
  overdueLoans: [],
  currency: 'RWF',
}

const advancedInsights: DashboardAdvancedInsights = {
  period: { year: 2026, asOf: '2026-09-20' },
  frequentBorrowers: [
    {
      memberId: 'm1',
      displayName: 'Jane Doe',
      numberOfLoansDisbursed: 5,
      totalPrincipalBorrowed: 900000,
      rank: 1,
    },
    {
      memberId: 'm2',
      displayName: 'Eric N.',
      numberOfLoansDisbursed: 4,
      totalPrincipalBorrowed: 700000,
      rank: 2,
    },
  ],
  largestActiveInvestments: [
    {
      investmentId: 'i1',
      name: 'Tea plantation',
      originalCapital: 5000000,
      remainingCapital: 4000000,
      profitReturned: 200000,
      status: 'ACTIVE',
      rank: 1,
    },
  ],
  repaymentReliability: {
    period: 'LIFETIME',
    minimumSample: 3,
    dataQualityExcludedTotal: 0,
    members: [
      {
        memberId: 'm1',
        displayName: 'Jane Doe',
        installmentsDue: 12,
        installmentsPaidOnTime: 11,
        installmentsPaidLate: 0,
        installmentsUnpaidPastDue: 1,
        onTimeRate: 91.7,
        rank: 1,
      },
    ],
  },
  currency: 'RWF',
}

const loansChart = Array.from({ length: 12 }, (_, i) => ({
  month: i + 1,
  loanCount: i === 2 ? 4 : 0,
  principalAmount: i === 2 ? 500000 : 0,
}))

const investmentsChart = Array.from({ length: 12 }, (_, i) => ({
  month: i + 1,
  capitalDeployed: i === 5 ? 2500000 : 0,
  investmentCount: i === 5 ? 2 : 0,
}))

vi.mock('@/shared/api/dashboard', () => ({
  fetchDashboardSummary: vi.fn(),
  fetchDashboardInsights: vi.fn(),
  fetchDashboardMemberInsights: vi.fn(),
  fetchDashboardAdvancedInsights: vi.fn(),
  fetchMonthlyContributionsChart: vi.fn().mockResolvedValue([{ month: 9, totalPaid: 1200000 }]),
  fetchLoansDisbursedByMonthChart: vi.fn(),
  fetchInvestmentsByMonthChart: vi.fn(),
}))

vi.mock('@/shared/api/cooperatives', () => ({
  fetchMyCooperatives: vi.fn().mockResolvedValue([{ id: 'coop-1', name: 'Alpha', code: 'A1' }]),
}))

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn().mockResolvedValue({
    id: 'sub-1',
    cooperativeId: 'coop-1',
    status: 'TRIAL',
    effectiveStatus: 'TRIAL',
    writeAllowed: true,
    usable: true,
    daysRemaining: 20,
    trialEndsAt: '2099-12-31T00:00:00Z',
  }),
}))

vi.mock('@/shared/api/members', () => ({
  fetchMemberFinancialSummaries: vi.fn().mockResolvedValue([]),
  fetchMemberFinancialSummary: vi.fn().mockResolvedValue({
    totalContributions: 0,
    outstandingLoanPrincipal: 0,
    outstandingLoanInterest: 0,
    outstandingFines: 0,
  }),
}))

import {
  fetchDashboardAdvancedInsights,
  fetchDashboardInsights,
  fetchDashboardMemberInsights,
  fetchDashboardSummary,
  fetchInvestmentsByMonthChart,
  fetchLoansDisbursedByMonthChart,
} from '@/shared/api/dashboard'

function renderAdmin(roles: string[]) {
  vi.mocked(fetchDashboardSummary).mockResolvedValue(summary)
  vi.mocked(fetchDashboardInsights).mockResolvedValue(insights)
  vi.mocked(fetchDashboardMemberInsights).mockResolvedValue(memberInsights)
  vi.mocked(fetchDashboardAdvancedInsights).mockResolvedValue(advancedInsights)
  vi.mocked(fetchLoansDisbursedByMonthChart).mockResolvedValue(loansChart)
  vi.mocked(fetchInvestmentsByMonthChart).mockResolvedValue(investmentsChart)

  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'officer',
          email: 'o@example.com',
          firstName: 'Off',
          lastName: 'Icer',
          fullName: 'Off Icer',
          roles,
          permissions: ['MEMBERSHIP_MANAGE'],
          cooperativeIds: ['coop-1'],
        },
        accessToken: 'tok',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter>
            <AdminDashboard cooperativeId="coop-1" />
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('AdminDashboard advanced insights (Phase F1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('leadership sees Advanced Insights charts and tables', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const section = await screen.findByTestId('advanced-insights', {}, { timeout: 15_000 })
    expect(within(section).getByText(/advanced insights/i)).toBeInTheDocument()
    expect(await screen.findByTestId('loans-disbursed-by-month-chart')).toBeInTheDocument()
    expect(await screen.findByTestId('investments-by-month-chart')).toBeInTheDocument()

    const borrowers = await screen.findByTestId('frequent-borrowers-card')
    expect(within(borrowers).getByText('Jane Doe')).toBeInTheDocument()
    expect(within(borrowers).getByText('5')).toBeInTheDocument()
    expect(within(borrowers).getByText(/900[,.]?000/)).toBeInTheDocument()
    expect(borrowers.textContent).not.toMatch(/most indebted|biggest debtor|worst borrower|roi/i)

    const investments = screen.getByTestId('largest-active-investments-card')
    expect(within(investments).getByText('Tea plantation')).toBeInTheDocument()
    expect(within(investments).getByText(/4[,.]?000[,.]?000/)).toBeInTheDocument()
    expect(investments.textContent).not.toMatch(/roi|return %|annualized/i)

    const reliability = screen.getByTestId('repayment-reliability-card')
    expect(within(reliability).getByText(/repayment reliability/i)).toBeInTheDocument()
    expect(within(reliability).getByText(/minimum 3 evaluated installments/i)).toBeInTheDocument()
    expect(within(reliability).getByText('91.7%')).toBeInTheDocument()
    expect(within(reliability).getByText('11')).toBeInTheDocument()
    expect(reliability.textContent).not.toMatch(/best borrower|worst borrower|credit score|risk score/i)

    expect(screen.getByTestId('this-month-insights')).toBeInTheDocument()
    expect(screen.getByTestId('member-insights')).toBeInTheDocument()
  }, 20_000)

  it('loan officer sees loan analytics but not investment analytics', async () => {
    renderAdmin([ROLE_LOAN_OFFICER])
    expect(await screen.findByTestId('advanced-insights')).toBeInTheDocument()
    expect(await screen.findByTestId('loans-disbursed-by-month-chart')).toBeInTheDocument()
    expect(screen.queryByTestId('investments-by-month-chart')).not.toBeInTheDocument()
    expect(screen.getByTestId('frequent-borrowers-card')).toBeInTheDocument()
    expect(screen.getByTestId('repayment-reliability-card')).toBeInTheDocument()
    expect(screen.queryByTestId('largest-active-investments-card')).not.toBeInTheDocument()
    expect(fetchInvestmentsByMonthChart).not.toHaveBeenCalled()
  }, 15_000)

  it('secretary and member do not see Advanced Insights', async () => {
    renderAdmin([ROLE_SECRETARY])
    expect(await screen.findByText(/dashboard/i)).toBeInTheDocument()
    expect(screen.queryByTestId('advanced-insights')).not.toBeInTheDocument()

    const store = configureStore({
      reducer: { auth: authReducer, ui: uiReducer },
      preloadedState: {
        auth: {
          user: {
            id: 'u2',
            username: 'member',
            email: 'm@example.com',
            firstName: 'Mem',
            lastName: 'Ber',
            fullName: 'Mem Ber',
            roles: [ROLE_MEMBER],
            permissions: [],
            cooperativeIds: ['coop-1'],
          },
          accessToken: 'tok',
          selectedCooperativeId: 'coop-1',
          status: 'authenticated' as const,
        },
        ui: { sidebarOpen: false, themePreference: 'light' as const },
      },
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <Provider store={store}>
        <QueryClientProvider client={client}>
          <ThemeProvider theme={lightTheme}>
            <MemoryRouter>
              <AdminDashboard cooperativeId="coop-1" />
            </MemoryRouter>
          </ThemeProvider>
        </QueryClientProvider>
      </Provider>,
    )
    expect(screen.queryByTestId('advanced-insights')).not.toBeInTheDocument()
    expect(fetchDashboardAdvancedInsights).not.toHaveBeenCalled()
  })

  it('shows empty loan and investment chart states', async () => {
    vi.mocked(fetchLoansDisbursedByMonthChart).mockResolvedValue(
      Array.from({ length: 12 }, (_, i) => ({
        month: i + 1,
        loanCount: 0,
        principalAmount: 0,
      })),
    )
    vi.mocked(fetchInvestmentsByMonthChart).mockResolvedValue(
      Array.from({ length: 12 }, (_, i) => ({
        month: i + 1,
        capitalDeployed: 0,
        investmentCount: 0,
      })),
    )
    vi.mocked(fetchDashboardAdvancedInsights).mockResolvedValue({
      ...advancedInsights,
      frequentBorrowers: [],
      largestActiveInvestments: [],
      repaymentReliability: {
        period: 'LIFETIME',
        minimumSample: 3,
        members: [],
      },
    })
    vi.mocked(fetchDashboardSummary).mockResolvedValue(summary)
    vi.mocked(fetchDashboardInsights).mockResolvedValue(insights)
    vi.mocked(fetchDashboardMemberInsights).mockResolvedValue(memberInsights)

    const store = configureStore({
      reducer: { auth: authReducer, ui: uiReducer },
      preloadedState: {
        auth: {
          user: {
            id: 'u1',
            username: 'officer',
            email: 'o@example.com',
            firstName: 'Off',
            lastName: 'Icer',
            fullName: 'Off Icer',
            roles: [ROLE_PRESIDENT],
            permissions: [],
            cooperativeIds: ['coop-1'],
          },
          accessToken: 'tok',
          selectedCooperativeId: 'coop-1',
          status: 'authenticated' as const,
        },
        ui: { sidebarOpen: false, themePreference: 'light' as const },
      },
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <Provider store={store}>
        <QueryClientProvider client={client}>
          <ThemeProvider theme={lightTheme}>
            <MemoryRouter>
              <AdminDashboard cooperativeId="coop-1" />
            </MemoryRouter>
          </ThemeProvider>
        </QueryClientProvider>
      </Provider>,
    )

    expect(await screen.findByTestId('loans-disbursed-by-month-empty')).toBeInTheDocument()
    expect(screen.getByTestId('investments-by-month-empty')).toBeInTheDocument()
    expect(screen.getByTestId('frequent-borrowers-empty')).toBeInTheDocument()
    expect(screen.getByTestId('largest-active-investments-empty')).toBeInTheDocument()
    expect(screen.getByTestId('repayment-reliability-empty')).toBeInTheDocument()
  }, 15_000)

  it('requests seasonal chart data for the selected year', async () => {
    renderAdmin([ROLE_PRESIDENT])
    await screen.findByTestId('advanced-insights')
    expect(fetchLoansDisbursedByMonthChart).toHaveBeenCalledWith(
      'coop-1',
      new Date().getFullYear(),
    )
    expect(fetchInvestmentsByMonthChart).toHaveBeenCalledWith('coop-1', new Date().getFullYear())
    expect(screen.getByTestId('advanced-insights-year')).toBeInTheDocument()
  }, 15_000)
})
