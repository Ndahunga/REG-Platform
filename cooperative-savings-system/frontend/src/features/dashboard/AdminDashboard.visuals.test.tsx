import { ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { setSelectedCooperativeId } from '@/app/store/authSlice'
import { CooperativeSelector } from '@/shared/components/CooperativeSelector'
import { DashboardPage } from '@/pages/DashboardPage'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import { fetchCooperative, fetchMyCooperatives } from '@/shared/api/cooperatives'
import { AdminDashboard } from '@/features/dashboard/AdminDashboard'
import { MemberDashboard } from '@/features/dashboard/MemberDashboard'
import type {
  DashboardAdvancedInsights,
  DashboardInsights,
  DashboardMemberInsights,
  DashboardSummary,
} from '@/shared/types/dashboard'
import {
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
} from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'

const summary: DashboardSummary = {
  totalMembers: 30,
  activeMembers: 24,
  regularContributionsTotal: 1000000,
  specialContributionsTotal: 250000,
  actualContributionsTotal: 1250000,
  availableGroupFunds: 5000000,
  outstandingLoanPrincipal: 1600000,
  overdueLoansCount: 3,
  currency: 'RWF',
}

const insights: DashboardInsights = {
  period: { year: 2026, month: 9, previousYear: 2026, previousMonth: 8 },
  contributions: { currentMonth: 1200000, previousMonth: 1000000, changePercent: 20, changeState: 'UP' },
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
    { memberId: 'm1', displayName: 'Diana Uwase', amount: 450000, rank: 1 },
    { memberId: 'm2', displayName: 'Caleb Nkusi', amount: 390000, rank: 2 },
    { memberId: 'm3', displayName: 'Davie Mugisha', amount: 320000, rank: 3 },
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

const advancedInsights: DashboardAdvancedInsights = {
  period: { year: 2026, asOf: '2026-09-20' },
  frequentBorrowers: [
    { memberId: 'm1', displayName: 'Jane Doe', numberOfLoansDisbursed: 5, totalPrincipalBorrowed: 900000, rank: 1 },
    { memberId: 'm2', displayName: 'Eric N.', numberOfLoansDisbursed: 4, totalPrincipalBorrowed: 700000, rank: 2 },
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
        installmentsPaidOnTime: 9,
        installmentsPaidLate: 2,
        installmentsUnpaidPastDue: 1,
        onTimeRate: 75,
        rank: 1,
      },
    ],
  },
  currency: 'RWF',
}

const emptyMonthly = Array.from({ length: 12 }, (_, i) => ({
  month: i + 1,
  loanCount: 0,
  principalAmount: 0,
}))
const emptyInvestments = Array.from({ length: 12 }, (_, i) => ({
  month: i + 1,
  capitalDeployed: 0,
  investmentCount: 0,
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
  fetchCooperative: vi.fn().mockResolvedValue({ id: 'coop-1', name: 'Support Scheme' }),
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

interface Overrides {
  summary?: DashboardSummary
  insights?: DashboardInsights
  memberInsights?: DashboardMemberInsights
  advanced?: DashboardAdvancedInsights
}

function makeStore(
  roles: string[],
  permissions: string[] = ['MEMBERSHIP_MANAGE'],
  cooperativeIds: string[] = ['coop-1'],
) {
  return configureStore({
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
          cooperativeIds,
        },
        accessToken: 'tok',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
}

function wrap(children: React.ReactNode, roles: string[], permissions?: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <Provider store={makeStore(roles, permissions)}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter>{children}</MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

function renderAdmin(roles: string[], overrides: Overrides = {}, permissions?: string[]) {
  vi.mocked(fetchDashboardSummary).mockResolvedValue(overrides.summary ?? summary)
  vi.mocked(fetchDashboardInsights).mockResolvedValue(overrides.insights ?? insights)
  vi.mocked(fetchDashboardMemberInsights).mockResolvedValue(overrides.memberInsights ?? memberInsights)
  vi.mocked(fetchDashboardAdvancedInsights).mockResolvedValue(overrides.advanced ?? advancedInsights)
  vi.mocked(fetchLoansDisbursedByMonthChart).mockResolvedValue(emptyMonthly)
  vi.mocked(fetchInvestmentsByMonthChart).mockResolvedValue(emptyInvestments)
  return wrap(<AdminDashboard cooperativeId="coop-1" />, roles, permissions)
}

function precedes(first: HTMLElement, second: HTMLElement) {
  return Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING)
}

const amount = (value: number) => new RegExp(value.toLocaleString('en-US').replace(/,/g, '[,.\\s]?'))

/** Whole-text match, so 250,000 does not also match 1,250,000. */
const exactAmount = (value: number) =>
  new RegExp('^\\D*' + value.toLocaleString('en-US').replace(/,/g, '[,.\\s\\u00a0]?') + '$')

describe('dashboard section order and quick actions', () => {
  beforeEach(() => vi.clearAllMocks())

  it('officer dashboard: at a glance, then Quick Actions, then My Member Status', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const glance = await screen.findByTestId('scheme-at-a-glance')
    const actions = await screen.findByTestId('member-quick-actions')
    const status = await screen.findByTestId('my-member-status')

    expect(precedes(glance, actions)).toBe(true)
    expect(precedes(actions, status)).toBe(true)
  })

  it('officer dashboard: member quick actions are rendered once and not inside My Member Status', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const status = await screen.findByTestId('my-member-status')

    expect(screen.getAllByTestId('member-quick-actions')).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: new RegExp(en.shares.buy.action) })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: en.dashboard.member.links.applyLoan })).toHaveLength(1)
    expect(within(status).queryByText(en.dashboard.member.actionsTitle)).not.toBeInTheDocument()
    expect(within(status).queryByRole('link')).not.toBeInTheDocument()
  })

  it('member dashboard: Quick Actions come before My Member Status and appear once', async () => {
    wrap(<MemberDashboard cooperativeId="coop-1" />, [ROLE_MEMBER], [])
    const actions = await screen.findByTestId('member-quick-actions')
    const status = await screen.findByTestId('my-member-status')

    expect(precedes(actions, status)).toBe(true)
    expect(screen.getAllByTestId('member-quick-actions')).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: new RegExp(en.shares.buy.action) })).toHaveLength(1)
    expect(screen.queryByTestId('scheme-at-a-glance')).not.toBeInTheDocument()
  })
})

describe('My Member Status is collapsible on every dashboard', () => {
  beforeEach(() => vi.clearAllMocks())

  const statusButton = () => screen.getByRole('button', { name: en.dashboard.member.myStatusTitle })
  const card = () => screen.queryByText(en.dashboard.member.totalContributions)

  it('officer dashboard: header visible, cards collapsed, Quick Actions still first and not duplicated', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const status = await screen.findByTestId('my-member-status')
    const actions = await screen.findByTestId('member-quick-actions')
    expect(precedes(actions, status)).toBe(true)
    expect(statusButton()).toHaveAttribute('aria-expanded', 'false')
    expect(card()).not.toBeInTheDocument()
    expect(screen.getAllByTestId('member-quick-actions')).toHaveLength(1)
    expect(screen.getAllByTestId('my-member-status')).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: new RegExp(en.shares.buy.action) })).toHaveLength(1)
  })

  it('officer dashboard: expanding shows the cards inside the section, once', async () => {
    renderAdmin([ROLE_PRESIDENT])
    await userEvent.click(await screen.findByRole('button', { name: en.dashboard.member.myStatusTitle }))
    const status = screen.getByTestId('my-member-status')
    expect(await within(status).findAllByText(en.dashboard.member.totalContributions)).toHaveLength(1)
    expect(screen.getAllByTestId('member-quick-actions')).toHaveLength(1)
  })

  it('member dashboard: header visible, cards collapsed, then shown once on click', async () => {
    wrap(<MemberDashboard cooperativeId="coop-1" />, [ROLE_MEMBER], [])
    const actions = await screen.findByTestId('member-quick-actions')
    const status = await screen.findByTestId('my-member-status')
    expect(precedes(actions, status)).toBe(true)
    expect(statusButton()).toHaveAttribute('aria-expanded', 'false')
    expect(card()).not.toBeInTheDocument()

    await userEvent.click(statusButton())
    expect(await within(status).findAllByText(en.dashboard.member.totalContributions)).toHaveLength(1)
    expect(screen.getAllByTestId('member-quick-actions')).toHaveLength(1)
  })
})

describe('Saving Scheme at a glance', () => {
  beforeEach(() => vi.clearAllMocks())

  it('leadership sees the member status donut with correct active / inactive values', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('glance-members-donut')
    expect(await within(card).findByText('30')).toBeInTheDocument() // total in the center
    expect(within(card).getByText('Active')).toBeInTheDocument()
    expect(within(card).getByText('24')).toBeInTheDocument()
    expect(within(card).getByText('Inactive')).toBeInTheDocument() // 30 - 24
    expect(within(card).getByText('6')).toBeInTheDocument()
    expect(within(card).getByText('80.0%')).toBeInTheDocument()
  })

  it('leadership sees regular vs special contributions with the actual amounts', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('glance-contribution-mix-donut')
    expect(await within(card).findByText('Regular')).toBeInTheDocument()
    expect(within(card).getByText(exactAmount(1000000))).toBeInTheDocument()
    expect(within(card).getByText('Special')).toBeInTheDocument()
    expect(within(card).getByText(exactAmount(250000))).toBeInTheDocument()
    expect(within(card).getByText(exactAmount(1250000))).toBeInTheDocument() // total in the center
    expect(within(card).getByText('80.0%')).toBeInTheDocument()
    expect(within(card).getByText('20.0%')).toBeInTheDocument()
  })

  it('zero totals render safe empty states with no NaN or Infinity', async () => {
    renderAdmin([ROLE_PRESIDENT], {
      summary: {
        ...summary,
        totalMembers: 0,
        activeMembers: 0,
        regularContributionsTotal: 0,
        specialContributionsTotal: 0,
        actualContributionsTotal: 0,
        outstandingLoanPrincipal: 0,
        overdueLoansCount: 0,
      },
      insights: {
        ...insights,
        contributions: { ...insights.contributions, currentMonth: 0 },
        loans: { ...insights.loans, issuedAmountCurrentMonth: 0, repaidCurrentMonth: 0, outstandingPrincipal: 0 },
        fines: { ...insights.fines, issuedAmountCurrentMonth: 0, collectedCurrentMonth: 0, issuedCountCurrentMonth: 0 },
      },
    })
    expect(await screen.findByTestId('glance-members-donut-empty')).toBeInTheDocument()
    expect(screen.getByTestId('glance-contribution-mix-donut-empty')).toBeInTheDocument()
    expect(await screen.findByTestId('loans-issued-vs-repaid-empty')).toBeInTheDocument()
    expect(screen.getByTestId('fine-activity-empty')).toBeInTheDocument()

    const section = screen.getByTestId('scheme-at-a-glance')
    expect(section.textContent).not.toMatch(/NaN|Infinity|undefined/)
    // zero is valid data in the narrative
    await waitFor(() => expect(screen.getByTestId('scheme-summary')).toHaveTextContent(/with 0 overdue loans/))
  })

  it('loan activity card carries the issued and repaid values and the outstanding callout', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('loans-issued-vs-repaid-chart')
    const table = await within(card).findByRole('table', { name: en.dashboard.insights.issuedVsRepaidTitle })
    const issued = within(table).getByRole('row', { name: new RegExp(en.dashboard.insights.loansIssued) })
    expect(issued).toHaveTextContent(amount(700000))
    const repaid = within(table).getByRole('row', { name: new RegExp(en.dashboard.insights.loansRepaid) })
    expect(repaid).toHaveTextContent(amount(420000))
    expect(within(card).getByTestId('loan-outstanding-callout')).toHaveTextContent(amount(1600000))
  })

  it('fine activity card carries issued and collected amounts', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('fine-activity-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.insights.fineActivityTitle })
    expect(within(table).getByRole('row', { name: new RegExp(en.dashboard.insights.finesIssued) })).toHaveTextContent(amount(30000))
    expect(within(table).getByRole('row', { name: new RegExp(en.dashboard.insights.finesCollected) })).toHaveTextContent(amount(22000))
  })

  it('shows a deterministic factual summary built only from API values', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const text = await waitFor(() => {
      const el = screen.getByTestId('scheme-summary')
      expect(el).toHaveTextContent(/24 of 30 members are active/)
      return el.textContent ?? ''
    })
    expect(text).toMatch(/This month, .*1[,.\s]?200[,.\s]?000 was collected in contributions/)
    expect(text).toMatch(/700[,.\s]?000 was issued in loans/)
    expect(text).toMatch(/420[,.\s]?000 was repaid on loans/)
    expect(text).toMatch(/Outstanding loan principal is .*1[,.\s]?600[,.\s]?000, with 3 overdue loans\./)
    expect(text).not.toMatch(/NaN|Infinity|undefined/)
    expect(text).not.toMatch(/\b(good|bad|healthy|poor)\b/i)
  })
})

describe('member insight charts', () => {
  beforeEach(() => vi.clearAllMocks())

  it('top contributors: names, ranks, amounts and member links', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('top-contributors-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.memberInsights.topContributors })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringMatching(/Diana Uwase.*1.*450[,.\s]?000/),
      expect.stringMatching(/Caleb Nkusi.*2.*390[,.\s]?000/),
      expect.stringMatching(/Davie Mugisha.*3.*320[,.\s]?000/),
    ])
    expect(within(table).getByRole('link', { name: 'Diana Uwase' })).toHaveAttribute('href', '/members/m1')
  })

  it('fine follow-up: outstanding amount with fine count and issued as details', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('fine-follow-up-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.memberInsights.fineFollowUp })
    const row = within(table).getByRole('row', { name: /John Doe/ })
    expect(row).toHaveTextContent(amount(30000)) // outstanding
    expect(within(row).getByText('4')).toBeInTheDocument() // fine count
    expect(row).toHaveTextContent(amount(50000)) // issued
  })

  it('overdue loans: outstanding overdue principal with loan count as detail', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('overdue-loans-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.memberInsights.overdueLoans })
    const row = within(table).getByRole('row', { name: /Sam Loan/ })
    expect(row).toHaveTextContent(amount(120000))
    expect(within(row).getByText('2')).toBeInTheDocument()
  })

  it('keeps the empty states', async () => {
    renderAdmin([ROLE_PRESIDENT], {
      memberInsights: { ...memberInsights, topContributors: [], fineFollowUp: [], overdueLoans: [] },
    })
    expect(await screen.findByTestId('top-contributors-card-empty')).toHaveTextContent(/no contribution activity/i)
    expect(screen.getByTestId('fine-follow-up-card-empty')).toHaveTextContent(/no fine balances require follow-up/i)
    expect(screen.getByTestId('overdue-loans-card-empty')).toHaveTextContent(/no overdue loans/i)
  })

  it('does not link member names without membership-manage permission', async () => {
    renderAdmin([ROLE_PRESIDENT], {}, [])
    const card = await screen.findByTestId('top-contributors-card')
    await within(card).findByRole('table')
    expect(within(card).queryByRole('link')).not.toBeInTheDocument()
    expect(within(card).getByText('Diana Uwase')).toBeInTheDocument()
  })
})

describe('advanced insight charts', () => {
  beforeEach(() => vi.clearAllMocks())

  it('frequent borrowers: total principal with loan count as detail', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('frequent-borrowers-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.advancedInsights.frequentBorrowers })
    const jane = within(table).getByRole('row', { name: /Jane Doe/ })
    expect(jane).toHaveTextContent(amount(900000))
    expect(within(jane).getByText('5')).toBeInTheDocument()
    const eric = within(table).getByRole('row', { name: /Eric N\./ })
    expect(eric).toHaveTextContent(amount(700000))
    expect(within(eric).getByText('4')).toBeInTheDocument()
  })

  it('largest active investments: remaining capital with original capital, profit returned and status', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('largest-active-investments-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.advancedInsights.largestInvestments })
    const row = within(table).getByRole('row', { name: /Tea plantation/ })
    const text = row.textContent ?? ''
    expect(text).toMatch(amount(4000000)) // remaining capital (the bar value)
    expect(text).toMatch(amount(5000000)) // original capital
    expect(text).toMatch(amount(200000)) // profit returned
    expect(text).toContain('ACTIVE')
  })

  it('repayment reliability: per-member on-time / late / past-due counts, rate and evaluated total', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const card = await screen.findByTestId('repayment-reliability-card')
    const table = await within(card).findByRole('table', { name: en.dashboard.advancedInsights.repaymentReliability })
    const row = within(table).getByRole('row', { name: /Jane Doe/ })
    expect(within(row).getByText('75.0%')).toBeInTheDocument()
    expect(within(row).getByText('9')).toBeInTheDocument()
    expect(within(row).getByText('2')).toBeInTheDocument()
    expect(within(row).getByText('1')).toBeInTheDocument()
    expect(within(row).getByText('12')).toBeInTheDocument()
    const legend = within(card).getByTestId('repayment-reliability-legend')
    expect(legend).toHaveTextContent(/On time/)
    expect(legend).toHaveTextContent(/Late/)
    expect(legend).toHaveTextContent(/Past due/)
    expect(card.textContent).not.toMatch(/NaN|Infinity|best borrower|worst borrower|credit score|risk score/i)
  })
})

describe('role-based visibility is unchanged', () => {
  beforeEach(() => vi.clearAllMocks())

  it('Secretary: members only, no financial visuals or financial sentences, no insight requests', async () => {
    renderAdmin([ROLE_SECRETARY])
    expect(await screen.findByTestId('glance-members-donut')).toBeInTheDocument()
    expect(screen.queryByTestId('glance-contribution-mix-donut')).not.toBeInTheDocument()
    expect(screen.queryByTestId('loans-issued-vs-repaid-chart')).not.toBeInTheDocument()
    expect(screen.queryByTestId('fine-activity-card')).not.toBeInTheDocument()
    expect(screen.queryByTestId('member-insights')).not.toBeInTheDocument()
    expect(screen.queryByTestId('advanced-insights')).not.toBeInTheDocument()

    const text = await waitFor(() => {
      const el = screen.getByTestId('scheme-summary')
      expect(el).toHaveTextContent(/24 of 30 members are active/)
      return el.textContent ?? ''
    })
    expect(text).not.toMatch(/contributions|loan|fines|collected|repaid/i)
    expect(fetchDashboardInsights).not.toHaveBeenCalled()
  })

  it('Loan Officer: loan visuals only (no members, contribution mix or fines)', async () => {
    renderAdmin([ROLE_LOAN_OFFICER], {}, [])
    const loanCard = await screen.findByTestId('loans-issued-vs-repaid-chart')
    expect(await within(loanCard).findByTestId('loan-outstanding-callout')).toHaveTextContent(amount(1600000))
    expect(screen.queryByTestId('glance-members-donut')).not.toBeInTheDocument()
    expect(screen.queryByTestId('glance-contribution-mix-donut')).not.toBeInTheDocument()
    expect(screen.queryByTestId('fine-activity-card')).not.toBeInTheDocument()
    expect(screen.queryByTestId('top-contributors-card')).not.toBeInTheDocument()
    expect(screen.getByTestId('overdue-loans-card')).toBeInTheDocument()

    const text = await waitFor(() => {
      const el = screen.getByTestId('scheme-summary')
      expect(el).toHaveTextContent(/issued in loans/)
      return el.textContent ?? ''
    })
    expect(text).not.toMatch(/members are active|in contributions|in fines/)
  })

  it('Accountant: contribution mix, loan and fine visuals but no member-count donut', async () => {
    renderAdmin([ROLE_ACCOUNTANT], {}, [])
    expect(await screen.findByTestId('glance-contribution-mix-donut')).toBeInTheDocument()
    expect(screen.getByTestId('loans-issued-vs-repaid-chart')).toBeInTheDocument()
    expect(await screen.findByTestId('fine-activity-card')).toBeInTheDocument()
    expect(screen.queryByTestId('glance-members-donut')).not.toBeInTheDocument()
    expect(screen.getByTestId('top-contributors-card')).toBeInTheDocument()
  })

  it('President: every at-a-glance visual', async () => {
    renderAdmin([ROLE_PRESIDENT])
    expect(await screen.findByTestId('glance-members-donut')).toBeInTheDocument()
    expect(screen.getByTestId('glance-contribution-mix-donut')).toBeInTheDocument()
    expect(await screen.findByTestId('loans-issued-vs-repaid-chart')).toBeInTheDocument()
    expect(await screen.findByTestId('fine-activity-card')).toBeInTheDocument()
  })

  it('the header Actions dropdown is gone (the in-page quick actions section remains)', async () => {
    renderAdmin([ROLE_PRESIDENT])
    await screen.findByTestId('scheme-at-a-glance')
    expect(screen.queryByRole('button', { name: en.common.actions })).not.toBeInTheDocument()
    expect(screen.getByTestId('member-quick-actions')).toBeInTheDocument()
  })
})

describe('dashboard header: current Saving Scheme name replaces the dropdowns', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMyCooperatives).mockResolvedValue([{ id: 'coop-1', name: 'Alpha', code: 'A1' }] as never)
    vi.mocked(fetchCooperative).mockResolvedValue({ id: 'coop-1', name: 'Support Scheme' } as never)
  })

  it('has no Actions or Reports dropdown and no scheme selector', async () => {
    renderAdmin([ROLE_PRESIDENT])
    await screen.findByTestId('scheme-at-a-glance')
    expect(screen.queryByRole('button', { name: en.common.actions })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Reports$/ })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(en.common.selectCooperative)).not.toBeInTheDocument()
  })

  it('shows the current scheme name as read-only text', async () => {
    renderAdmin([ROLE_PRESIDENT])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText('Alpha')).toBeInTheDocument()
    expect(within(name).queryByRole('button')).not.toBeInTheDocument()
  })

  it('takes the name from the current cooperative id, not from the first scheme in the list', async () => {
    vi.mocked(fetchMyCooperatives).mockResolvedValue([
      { id: 'coop-9', name: 'First In List', code: 'F1' },
      { id: 'coop-1', name: 'Name From The API', code: 'A1' },
    ] as never)
    renderAdmin([ROLE_PRESIDENT])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText('Name From The API')).toBeInTheDocument()
    expect(within(name).queryByText('First In List')).not.toBeInTheDocument()
  })

  it('shows a loading placeholder while the name loads, not the fallback', async () => {
    vi.mocked(fetchMyCooperatives).mockReturnValue(new Promise(() => {}))
    renderAdmin([ROLE_PRESIDENT])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(within(name).getByLabelText(en.common.loading)).toBeInTheDocument()
    expect(within(name).queryByText(en.dashboard.schemeNameUnavailable)).not.toBeInTheDocument()
  })

  it('shows a localized fallback, never another scheme, when the name is unavailable', async () => {
    vi.mocked(fetchMyCooperatives).mockResolvedValue([{ id: 'coop-9', name: 'Someone Else', code: 'X' }] as never)
    renderAdmin([ROLE_PRESIDENT])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText(en.dashboard.schemeNameUnavailable)).toBeInTheDocument()
    expect(within(name).queryByText('Someone Else')).not.toBeInTheDocument()
  })

  it('has the fallback and label translated in Kinyarwanda', () => {
    expect(rw.dashboard.schemeNameUnavailable).toBeTruthy()
    expect(rw.dashboard.schemeNameUnavailable).not.toBe(en.dashboard.schemeNameUnavailable)
    expect(rw.dashboard.currentSchemeLabel).toBeTruthy()
  })

  it('uses the verified scheme record for a Super Admin in the support workspace', async () => {
    renderAdmin([ROLE_SUPER_ADMIN])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText('Support Scheme')).toBeInTheDocument()
    expect(fetchCooperative).toHaveBeenCalledWith('coop-1')
    expect(fetchMyCooperatives).not.toHaveBeenCalled()
  })

  it('still renders the existing widgets and summary data', async () => {
    renderAdmin([ROLE_PRESIDENT])
    expect(await screen.findByTestId('scheme-at-a-glance')).toBeInTheDocument()
    expect(await screen.findByTestId('member-quick-actions')).toBeInTheDocument()
    expect(await screen.findByTestId('my-member-status')).toBeInTheDocument()
  })
})

describe('current scheme name on both dashboards, driven by the global selector', () => {
  const twoSchemes = [
    { id: 'coop-1', name: 'Alpha', code: 'A1' },
    { id: 'coop-2', name: 'Beta', code: 'B2' },
  ]

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMyCooperatives).mockResolvedValue(twoSchemes as never)
    vi.mocked(fetchDashboardSummary).mockResolvedValue(summary)
    vi.mocked(fetchDashboardInsights).mockResolvedValue(insights)
    vi.mocked(fetchDashboardMemberInsights).mockResolvedValue(memberInsights)
    vi.mocked(fetchDashboardAdvancedInsights).mockResolvedValue(advancedInsights)
    vi.mocked(fetchLoansDisbursedByMonthChart).mockResolvedValue(emptyMonthly)
    vi.mocked(fetchInvestmentsByMonthChart).mockResolvedValue(emptyInvestments)
  })

  function renderWithSelector(roles: string[]) {
    const store = makeStore(roles, [], ['coop-1', 'coop-2'])
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <Provider store={store}>
        <QueryClientProvider client={client}>
          <ThemeProvider theme={lightTheme}>
            <MemoryRouter>
              <CooperativeSelector />
              <DashboardPage />
            </MemoryRouter>
          </ThemeProvider>
        </QueryClientProvider>
      </Provider>,
    )
    return store
  }

  it('member dashboard shows the current scheme name from the selected cooperative', async () => {
    wrap(<MemberDashboard cooperativeId="coop-1" />, [ROLE_MEMBER], [])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText('Alpha')).toBeInTheDocument()
    expect(within(name).queryByRole('button')).not.toBeInTheDocument()
  })

  it('member dashboard shows loading, then the localized fallback when the name is unavailable', async () => {
    vi.mocked(fetchMyCooperatives).mockReturnValue(new Promise(() => {}))
    const view = wrap(<MemberDashboard cooperativeId="coop-1" />, [ROLE_MEMBER], [])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(within(name).getByLabelText(en.common.loading)).toBeInTheDocument()
    view.unmount()

    vi.mocked(fetchMyCooperatives).mockResolvedValue([])
    wrap(<MemberDashboard cooperativeId="coop-1" />, [ROLE_MEMBER], [])
    expect(
      await within(await screen.findByTestId('dashboard-scheme-name')).findByText(
        en.dashboard.schemeNameUnavailable,
      ),
    ).toBeInTheDocument()
  })

  it('never shows a scheme the user is not a member of, even if a stale cached list contains it', async () => {
    vi.mocked(fetchMyCooperatives).mockResolvedValue([
      ...twoSchemes,
      { id: 'coop-9', name: 'Other Tenant', code: 'O9' },
    ] as never)
    // The user's own cooperatives are only coop-1; coop-9 arrives as a manipulated/stale id.
    wrap(<MemberDashboard cooperativeId="coop-9" />, [ROLE_MEMBER], [])
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText(en.dashboard.schemeNameUnavailable)).toBeInTheDocument()
    expect(screen.queryByText('Other Tenant')).not.toBeInTheDocument()
    expect(fetchDashboardSummary).not.toHaveBeenCalledWith('coop-9')
  })

  it.each([
    ['member dashboard', [ROLE_MEMBER]],
    ['officer dashboard', [ROLE_PRESIDENT]],
  ])('%s follows the global selector without a reload', async (_label, roles) => {
    const user = userEvent.setup()
    const store = renderWithSelector(roles)
    const name = await screen.findByTestId('dashboard-scheme-name')
    expect(await within(name).findByText('Alpha')).toBeInTheDocument()

    // The global selector is still present and interactive.
    await user.click(screen.getByRole('combobox', { name: new RegExp(en.common.selectCooperative) }))
    await user.click(await screen.findByRole('option', { name: 'Beta' }))

    expect(store.getState().auth.selectedCooperativeId).toBe('coop-2')
    expect(await within(await screen.findByTestId('dashboard-scheme-name')).findByText('Beta')).toBeInTheDocument()
    expect(screen.queryByText('Alpha', { selector: 'p' })).not.toBeInTheDocument()
  })

  it('updates the name when the selected cooperative changes in the store', async () => {
    const store = renderWithSelector([ROLE_MEMBER])
    expect(await within(await screen.findByTestId('dashboard-scheme-name')).findByText('Alpha')).toBeInTheDocument()
    store.dispatch(setSelectedCooperativeId('coop-2'))
    expect(await within(await screen.findByTestId('dashboard-scheme-name')).findByText('Beta')).toBeInTheDocument()
  })
})
