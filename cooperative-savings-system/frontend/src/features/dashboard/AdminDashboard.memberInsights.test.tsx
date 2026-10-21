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
import { MemberDashboard } from '@/features/dashboard/MemberDashboard'
import type { DashboardInsights, DashboardMemberInsights, DashboardSummary } from '@/shared/types/dashboard'
import {
  ROLE_ACCOUNTANT,
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
  period: { start: '2026-01-01', end: '2026-09-18' },
  topContributors: [
    { memberId: 'm1', displayName: 'Jane Doe', amount: 450000, rank: 1 },
    { memberId: 'm2', displayName: 'Eric N.', amount: 390000, rank: 2 },
    { memberId: 'm3', displayName: 'Alice K.', amount: 320000, rank: 3 },
  ],
  fineFollowUp: [
    {
      memberId: 'm4',
      displayName: 'John Doe',
      issuedAmount: 50000,
      paidAmount: 20000,
      outstandingAmount: 30000,
      fineCount: 4,
      rank: 1,
    },
  ],
  overdueLoans: [
    {
      memberId: 'm5',
      displayName: 'Sam Loan',
      overdueLoanCount: 2,
      outstandingPrincipal: 120000,
      oldestDueDate: '2026-01-15',
      rank: 1,
    },
  ],
  currency: 'RWF',
}

vi.mock('@/shared/api/dashboard', () => ({
  fetchDashboardSummary: vi.fn(),
  fetchDashboardInsights: vi.fn(),
  fetchDashboardMemberInsights: vi.fn(),
  fetchDashboardAdvancedInsights: vi.fn().mockResolvedValue({
    period: { year: 2026, asOf: '2026-09-20' },
    largestActiveInvestments: [],
    frequentBorrowers: [],
    repaymentReliability: { period: 'LIFETIME', minimumSample: 3, members: [] },
    currency: 'RWF',
  }),
  fetchMonthlyContributionsChart: vi.fn().mockResolvedValue([{ month: 9, totalPaid: 1200000 }]),
  fetchLoansDisbursedByMonthChart: vi.fn().mockResolvedValue(
    Array.from({ length: 12 }, (_, i) => ({
      month: i + 1,
      loanCount: 0,
      principalAmount: 0,
    })),
  ),
  fetchInvestmentsByMonthChart: vi.fn().mockResolvedValue(
    Array.from({ length: 12 }, (_, i) => ({
      month: i + 1,
      capitalDeployed: 0,
      investmentCount: 0,
    })),
  ),
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
  fetchDashboardInsights,
  fetchDashboardMemberInsights,
  fetchDashboardSummary,
} from '@/shared/api/dashboard'

function renderAdmin(
  roles: string[],
  memberData: DashboardMemberInsights = memberInsights,
  permissions: string[] = ['MEMBERSHIP_MANAGE'],
) {
  vi.mocked(fetchDashboardSummary).mockResolvedValue(summary)
  vi.mocked(fetchDashboardInsights).mockResolvedValue(insights)
  vi.mocked(fetchDashboardMemberInsights).mockResolvedValue(memberData)

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
          permissions,
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

function renderMember() {
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
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter>
            <MemberDashboard cooperativeId="coop-1" />
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

describe('AdminDashboard member insights (Phase C2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('leadership sees Member Insights with ranked contributors and fines', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const section = await screen.findByTestId('member-insights', {}, { timeout: 15_000 })
    expect(within(section).getByText(/member insights/i)).toBeInTheDocument()
    const contributors = await screen.findByTestId('top-contributors-card', {}, { timeout: 15_000 })
    expect(within(contributors).getByText('Jane Doe')).toBeInTheDocument()
    expect(within(contributors).getByText(/450[,.]?000/)).toBeInTheDocument()
    const contributorTable = within(contributors).getByRole('table', { name: /top contributors/i })
    expect(within(contributorTable).getByRole('row', { name: /Jane Doe.*1.*450/ })).toBeInTheDocument()
    const fines = screen.getByTestId('fine-follow-up-card')
    expect(within(fines).getByText(/highest outstanding fines/i)).toBeInTheDocument()
    expect(within(fines).getByText('John Doe')).toBeInTheDocument()
    expect(section.textContent).not.toMatch(/worst|punished|bad payer/i)
  }, 20_000)

  it('shows empty contributor and fine states', async () => {
    renderAdmin([ROLE_ACCOUNTANT], {
      ...memberInsights,
      topContributors: [],
      fineFollowUp: [],
      overdueLoans: [],
    })
    expect(await screen.findByTestId('top-contributors-card-empty')).toHaveTextContent(
      /no contribution activity/i,
    )
    expect(screen.getByTestId('fine-follow-up-card-empty')).toHaveTextContent(
      /no fine balances require follow-up/i,
    )
  })

  it('loan officer sees overdue loans but not finance leaderboards', async () => {
    renderAdmin([ROLE_LOAN_OFFICER], memberInsights, [])
    expect(await screen.findByTestId('overdue-loans-card')).toBeInTheDocument()
    expect(screen.queryByTestId('top-contributors-card')).not.toBeInTheDocument()
    expect(screen.queryByTestId('fine-follow-up-card')).not.toBeInTheDocument()
  })

  it('secretary does not see Member Insights', async () => {
    renderAdmin([ROLE_SECRETARY])
    expect(await screen.findByText(/dashboard/i)).toBeInTheDocument()
    expect(screen.queryByTestId('member-insights')).not.toBeInTheDocument()
  })

  it('member dashboard does not expose Member Insights', async () => {
    renderMember()
    expect(await screen.findByText(/welcome/i)).toBeInTheDocument()
    expect(screen.queryByTestId('member-insights')).not.toBeInTheDocument()
    expect(fetchDashboardMemberInsights).not.toHaveBeenCalled()
  })

  it('keeps Phase C1 widgets and subscription card', async () => {
    renderAdmin([ROLE_PRESIDENT])
    expect(await screen.findByTestId('this-month-insights')).toBeInTheDocument()
    expect(await screen.findByTestId('dashboard-subscription-card')).toBeInTheDocument()
    expect(screen.getByTestId('loans-issued-vs-repaid-chart')).toBeInTheDocument()
  })

  it('links member names to detail when membership manage is allowed', async () => {
    renderAdmin([ROLE_PRESIDENT])
    await screen.findByTestId('member-insights', {}, { timeout: 10_000 })
    const link = await screen.findByRole('link', { name: 'Jane Doe' }, { timeout: 10_000 })
    expect(link).toHaveAttribute('href', '/members/m1')
  })
})
