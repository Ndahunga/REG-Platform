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
import type { DashboardInsights, DashboardSummary } from '@/shared/types/dashboard'
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
  period: { year: 2026, month: 9, previousYear: 2026, previousMonth: 8, label: '2026-09' },
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
  timezone: 'Africa/Kigali',
}

vi.mock('@/shared/api/dashboard', () => ({
  fetchDashboardSummary: vi.fn(),
  fetchDashboardInsights: vi.fn(),
  fetchDashboardMemberInsights: vi.fn().mockResolvedValue({
    period: { start: '2026-01-01', end: '2026-09-20' },
    topContributors: [],
    fineFollowUp: [],
    overdueLoans: [],
    currency: 'RWF',
  }),
  fetchDashboardAdvancedInsights: vi.fn().mockResolvedValue({
    period: { year: 2026, asOf: '2026-09-20' },
    largestActiveInvestments: [],
    frequentBorrowers: [],
    repaymentReliability: { period: 'LIFETIME', minimumSample: 3, members: [] },
    currency: 'RWF',
  }),
  fetchMonthlyContributionsChart: vi.fn().mockResolvedValue([
    { month: 1, totalPaid: 100 },
    { month: 9, totalPaid: 1200000 },
  ]),
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
    socialContributions: 0,
    shareCount: 0,
    currentShareValue: 0,
    totalShareValue: 0,
  }),
  fetchMyMemberFinancialSummary: vi.fn().mockResolvedValue({
    totalContributions: 0,
    outstandingLoanPrincipal: 0,
    outstandingLoanInterest: 0,
    outstandingFines: 0,
  }),
}))

import { fetchDashboardInsights, fetchDashboardSummary } from '@/shared/api/dashboard'

function renderAdmin(roles: string[], insightsData: DashboardInsights | null = insights) {
  vi.mocked(fetchDashboardSummary).mockResolvedValue(summary)
  if (insightsData) {
    vi.mocked(fetchDashboardInsights).mockResolvedValue(insightsData)
  } else {
    vi.mocked(fetchDashboardInsights).mockResolvedValue({
      ...insights,
      contributions: {
        currentMonth: 0,
        previousMonth: 0,
        changePercent: 0,
        changeState: 'FLAT',
      },
      loans: {
        ...insights.loans,
        issuedCountCurrentMonth: 0,
        issuedAmountCurrentMonth: 0,
        repaidCurrentMonth: 0,
        issuedAmountChangePercent: 0,
        issuedAmountChangeState: 'FLAT',
      },
      fines: {
        issuedCountCurrentMonth: 0,
        issuedAmountCurrentMonth: 0,
        collectedCurrentMonth: 0,
        collectedChangePercent: 0,
        collectedChangeState: 'FLAT',
      },
    })
  }

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

describe('AdminDashboard this-month insights (Phase C1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows contributions KPI with positive MoM for leadership', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('insight-contributions', {}, { timeout: 15_000 })
    expect(within(card).getByText(/contributions/i)).toBeInTheDocument()
    expect(await within(card).findByText(/1[\s,.]*200[\s,.]*000/, {}, { timeout: 15_000 })).toBeInTheDocument()
    expect(await within(card).findByText(/20\.0% vs last month/i, {}, { timeout: 15_000 })).toBeInTheDocument()
    expect(screen.getByTestId('this-month-insights')).toBeInTheDocument()
  }, 20_000)

  it('shows negative contribution delta', async () => {
    renderAdmin([ROLE_ACCOUNTANT], {
      ...insights,
      contributions: {
        currentMonth: 800,
        previousMonth: 1000,
        changePercent: -20,
        changeState: 'DOWN',
      },
    })
    const card = await screen.findByTestId('insight-contributions')
    expect(await within(card).findByText(/20\.0% vs last month/i)).toBeInTheDocument()
  })

  it('shows no-baseline state', async () => {
    renderAdmin([ROLE_PRESIDENT], {
      ...insights,
      contributions: {
        currentMonth: 500,
        previousMonth: 0,
        changePercent: null,
        changeState: 'NO_BASELINE',
      },
    })
    const card = await screen.findByTestId('insight-contributions')
    expect(await within(card).findByText(/no prior-month baseline/i)).toBeInTheDocument()
  })

  it('shows loans issued amount, count, MoM, repayments, and outstanding principal', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const issued = await screen.findByTestId('insight-loans-issued')
    expect(await within(issued).findByText(/8 loans/i)).toBeInTheDocument()
    expect(await within(issued).findByText(/40\.0% vs last month/i)).toBeInTheDocument()
    expect(await screen.findByTestId('insight-loan-repayments')).toBeInTheDocument()
    // Outstanding principal is shown once as a callout on the loan activity card (not repeated as a KPI here).
    const callout = await screen.findByTestId('loan-outstanding-callout')
    expect(callout).toHaveTextContent(/1[\s,.]*600[\s,.]*000/)
    expect(screen.queryByTestId('insight-outstanding-loans')).not.toBeInTheDocument()
  })

  it('shows fines collected and issued-vs-repaid chart', async () => {
    renderAdmin([ROLE_PRESIDENT])
    expect(await screen.findByTestId('insight-fines-collected')).toBeInTheDocument()
    expect(await screen.findByTestId('loans-issued-vs-repaid-chart')).toBeInTheDocument()
    expect(screen.getByTestId('fine-activity-card')).toBeInTheDocument()
  })

  it('still renders contribution trend and subscription card', async () => {
    renderAdmin([ROLE_PRESIDENT])
    expect(await screen.findByText(/monthly contributions/i)).toBeInTheDocument()
    expect(await screen.findByTestId('dashboard-subscription-card')).toBeInTheDocument()
  })

  it('renders empty chart state when no loan activity', async () => {
    renderAdmin([ROLE_PRESIDENT], {
      ...insights,
      loans: {
        ...insights.loans,
        issuedAmountCurrentMonth: 0,
        repaidCurrentMonth: 0,
        issuedCountCurrentMonth: 0,
      },
    })
    expect(await screen.findByTestId('loans-issued-vs-repaid-empty')).toBeInTheDocument()
  })

  it('loan officer sees loan insights but not contribution KPIs', async () => {
    renderAdmin([ROLE_LOAN_OFFICER])
    expect(await screen.findByTestId('insight-loans-issued')).toBeInTheDocument()
    expect(screen.queryByTestId('insight-contributions')).not.toBeInTheDocument()
    expect(screen.queryByTestId('insight-fines-collected')).not.toBeInTheDocument()
  })

  it('secretary does not see leadership insights', async () => {
    renderAdmin([ROLE_SECRETARY])
    expect(await screen.findByText(/dashboard/i)).toBeInTheDocument()
    expect(screen.queryByTestId('this-month-insights')).not.toBeInTheDocument()
  })

  it('member dashboard does not expose leadership analytics', async () => {
    renderMember()
    expect(await screen.findByText(/welcome/i)).toBeInTheDocument()
    expect(screen.queryByTestId('this-month-insights')).not.toBeInTheDocument()
    expect(screen.queryByTestId('insight-contributions')).not.toBeInTheDocument()
  })

  it('does not render NaN or Infinity in insight cards', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const section = await screen.findByTestId('this-month-insights')
    expect(section.textContent).not.toMatch(/NaN|Infinity/)
  })
})
