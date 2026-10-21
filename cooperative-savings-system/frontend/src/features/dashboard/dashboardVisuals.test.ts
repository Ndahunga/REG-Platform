import i18n from 'i18next'
import { describe, expect, it } from 'vitest'
import type {
  DashboardInsights,
  DashboardSummary,
  RepaymentReliabilityRow,
} from '@/shared/types/dashboard'
import '@/i18n'
import {
  buildSchemeSummary,
  contributionMixData,
  fineActivityData,
  fineFollowUpBars,
  frequentBorrowerBars,
  investmentBars,
  loanActivityData,
  memberStatusData,
  overdueLoanBars,
  reliabilityBars,
  topContributorBars,
  toFiniteNumber,
} from './dashboardVisuals'

const summary: DashboardSummary = {
  totalMembers: 30,
  activeMembers: 24,
  regularContributionsTotal: 1000,
  specialContributionsTotal: 200,
  actualContributionsTotal: 1200,
  availableGroupFunds: 5000,
  outstandingLoanPrincipal: 1600000,
  overdueLoansCount: 3,
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
    outstandingPrincipal: 1600000,
  },
  fines: {
    issuedCountCurrentMonth: 5,
    issuedAmountCurrentMonth: 30000,
    collectedCurrentMonth: 22000,
  },
}

const money = (value: number) => `RWF ${value.toLocaleString('en-US')}`
const t = (key: string, options?: Record<string, unknown>) => i18n.t(key, options) as string
const all = { members: true, contributions: true, loans: true, fines: true }
const none = { members: false, contributions: false, loans: false, fines: false }

describe('toFiniteNumber', () => {
  it('turns anything non-finite into 0 and keeps real zero', () => {
    expect(toFiniteNumber('12.5')).toBe(12.5)
    expect(toFiniteNumber(0)).toBe(0)
    expect(toFiniteNumber(undefined)).toBe(0)
    expect(toFiniteNumber(null)).toBe(0)
    expect(toFiniteNumber('abc')).toBe(0)
    expect(toFiniteNumber(Infinity)).toBe(0)
    expect(toFiniteNumber(NaN)).toBe(0)
  })
})

describe('at-a-glance data', () => {
  it('member status: active vs inactive = max(total - active, 0)', () => {
    expect(memberStatusData(summary)).toEqual({ total: 30, active: 24, inactive: 6 })
    expect(memberStatusData({ totalMembers: 5, activeMembers: 9 })).toEqual({ total: 5, active: 9, inactive: 0 })
    expect(memberStatusData({ totalMembers: 0, activeMembers: 0 })).toEqual({ total: 0, active: 0, inactive: 0 })
  })

  it('contribution mix: regular and special are the parts of the total', () => {
    expect(contributionMixData(summary)).toEqual({ regular: 1000, special: 200, total: 1200 })
    expect(contributionMixData({ regularContributionsTotal: '0', specialContributionsTotal: 0 })).toEqual({
      regular: 0,
      special: 0,
      total: 0,
    })
  })

  it('contribution mix never yields NaN/Infinity or negatives for bad input', () => {
    const mix = contributionMixData({
      regularContributionsTotal: 'oops',
      specialContributionsTotal: -50,
    })
    expect(mix).toEqual({ regular: 0, special: 0, total: 0 })
    for (const value of Object.values(mix)) expect(Number.isFinite(value)).toBe(true)
  })

  it('loan activity passes issued, repaid, count and outstanding through unchanged', () => {
    expect(loanActivityData(insights.loans)).toEqual({
      issued: 700000,
      repaid: 420000,
      issuedCount: 8,
      outstandingPrincipal: 1600000,
      hasActivity: true,
    })
    expect(loanActivityData(undefined).hasActivity).toBe(false)
    expect(
      loanActivityData({ ...insights.loans, issuedAmountCurrentMonth: 0, repaidCurrentMonth: 0 }).hasActivity,
    ).toBe(false)
  })

  it('fine activity passes issued and collected amounts through unchanged', () => {
    expect(fineActivityData(insights.fines)).toEqual({
      issued: 30000,
      collected: 22000,
      issuedCount: 5,
      hasActivity: true,
    })
    expect(fineActivityData(undefined)).toEqual({ issued: 0, collected: 0, issuedCount: 0, hasActivity: false })
  })
})

describe('ranked bar data', () => {
  const href = (id: string) => `/members/${id}`

  it('top contributors: value is the contribution amount, links only when allowed', () => {
    const rows = [
      { memberId: 'm1', displayName: 'Diana', amount: 450000, rank: 1 },
      { memberId: 'm2', displayName: 'Caleb', amount: '390000', rank: 2 },
    ]
    expect(topContributorBars(rows, href)).toEqual([
      { id: 'm1', rank: 1, label: 'Diana', value: 450000, href: '/members/m1', meta: {} },
      { id: 'm2', rank: 2, label: 'Caleb', value: 390000, href: '/members/m2', meta: {} },
    ])
    expect(topContributorBars(rows)[0].href).toBeUndefined()
  })

  it('fine follow-up: value is the outstanding amount; count and issued/paid are details', () => {
    const [bar] = fineFollowUpBars(
      [
        {
          memberId: 'm4',
          displayName: 'John',
          issuedAmount: 50000,
          paidAmount: 20000,
          outstandingAmount: 30000,
          fineCount: 4,
          rank: 1,
        },
      ],
      href,
    )
    expect(bar.value).toBe(30000)
    expect(bar.meta).toEqual({ fineCount: 4, issuedAmount: 50000, paidAmount: 20000 })
    expect(bar.href).toBe('/members/m4')
  })

  it('overdue loans: value is the outstanding overdue principal; loan count is a detail', () => {
    const [bar] = overdueLoanBars([
      { memberId: 'm5', displayName: 'Sam', overdueLoanCount: 2, outstandingPrincipal: 120000, rank: 1 },
    ])
    expect(bar.value).toBe(120000)
    expect(bar.meta).toEqual({ overdueLoanCount: 2 })
  })

  it('frequent borrowers: value is total principal borrowed; loan count is a detail', () => {
    const [bar] = frequentBorrowerBars([
      { memberId: 'm1', displayName: 'Jane', numberOfLoansDisbursed: 5, totalPrincipalBorrowed: 900000, rank: 1 },
    ])
    expect(bar.value).toBe(900000)
    expect(bar.meta).toEqual({ loans: 5 })
  })

  it('investments: value is remaining capital; original capital, profit and status are details', () => {
    const [bar] = investmentBars([
      {
        investmentId: 'i1',
        name: 'Tea plantation',
        originalCapital: 5000000,
        remainingCapital: 4000000,
        profitReturned: 200000,
        status: 'ACTIVE',
        rank: 1,
      },
    ])
    expect(bar).toMatchObject({ id: 'i1', label: 'Tea plantation', value: 4000000 })
    expect(bar.meta).toEqual({ originalCapital: 5000000, profitReturned: 200000, status: 'ACTIVE' })
  })

  it('is safe with malformed amounts', () => {
    const [bar] = topContributorBars([{ memberId: 'm', displayName: 'X', amount: 'n/a', rank: 1 }])
    expect(bar.value).toBe(0)
  })
})

describe('repayment reliability data', () => {
  const row = (over: Partial<RepaymentReliabilityRow>): RepaymentReliabilityRow => ({
    memberId: 'm1',
    displayName: 'Jane',
    installmentsDue: 12,
    installmentsPaidOnTime: 9,
    installmentsPaidLate: 2,
    installmentsUnpaidPastDue: 1,
    onTimeRate: 75,
    rank: 1,
    ...over,
  })

  it('splits evaluated installments into on-time / late / past-due shares that total 100', () => {
    const [bar] = reliabilityBars([row({})])
    expect(bar).toMatchObject({ onTime: 9, paidLate: 2, pastDue: 1, evaluated: 12, onTimeRate: 75 })
    expect(bar.onTimePct).toBeCloseTo(75)
    expect(bar.paidLatePct).toBeCloseTo(16.666, 2)
    expect(bar.pastDuePct).toBeCloseTo(8.333, 2)
    expect(bar.onTimePct + bar.paidLatePct + bar.pastDuePct).toBeCloseTo(100)
  })

  it('has no NaN/Infinity when nothing was evaluated, and keeps a null rate honest', () => {
    const [bar] = reliabilityBars([
      row({
        installmentsPaidOnTime: 0,
        installmentsPaidLate: 0,
        installmentsUnpaidPastDue: 0,
        installmentsDue: 0,
        onTimeRate: 'n/a',
      }),
    ])
    expect(bar.onTimePct).toBe(0)
    expect(bar.paidLatePct).toBe(0)
    expect(bar.pastDuePct).toBe(0)
    expect(bar.onTimeRate).toBeNull()
  })
})

describe('buildSchemeSummary', () => {
  it('states only facts from the API and handles plural counts', () => {
    const lines = buildSchemeSummary({ summary, insights, show: all }, t, money)
    expect(lines).toEqual([
      '24 of 30 members are active in this Saving Scheme.',
      'This month, RWF 1,200,000 was collected in contributions, RWF 700,000 was issued in loans, RWF 420,000 was repaid on loans and RWF 22,000 was collected in fines.',
      'Outstanding loan principal is RWF 1,600,000, with 3 overdue loans.',
    ])
  })

  it('uses singular wording for one member and one overdue loan', () => {
    const lines = buildSchemeSummary(
      {
        summary: { ...summary, totalMembers: 1, activeMembers: 1, overdueLoansCount: 1 },
        insights,
        show: { ...none, members: true, loans: true },
      },
      t,
      money,
    )
    expect(lines[0]).toBe('1 of 1 member is active in this Saving Scheme.')
    expect(lines.at(-1)).toBe('Outstanding loan principal is RWF 1,600,000, with 1 overdue loan.')
  })

  it('treats zero as valid data rather than omitting it', () => {
    const lines = buildSchemeSummary(
      {
        summary: { ...summary, overdueLoansCount: 0, outstandingLoanPrincipal: 0 },
        insights: {
          ...insights,
          contributions: { ...insights.contributions, currentMonth: 0 },
          loans: { ...insights.loans, issuedAmountCurrentMonth: 0, repaidCurrentMonth: 0 },
        },
        show: all,
      },
      t,
      money,
    )
    const text = lines.join(' ')
    expect(text).toContain('RWF 0 was collected in contributions')
    expect(text).toContain('RWF 0 was issued in loans')
    expect(text).toContain('Outstanding loan principal is RWF 0, with 0 overdue loans.')
  })

  it('only mentions metrics the role may see', () => {
    const membersOnly = buildSchemeSummary({ summary, insights, show: { ...none, members: true } }, t, money)
    expect(membersOnly).toEqual(['24 of 30 members are active in this Saving Scheme.'])

    const loansOnly = buildSchemeSummary({ summary, insights, show: { ...none, loans: true } }, t, money).join(' ')
    expect(loansOnly).toContain('issued in loans')
    expect(loansOnly).not.toMatch(/members|contributions|fines/)

    expect(buildSchemeSummary({ summary, insights, show: none }, t, money)).toEqual([])
  })

  it('omits unavailable metrics instead of inventing them', () => {
    const noInsights = buildSchemeSummary({ summary, show: all }, t, money)
    expect(noInsights.join(' ')).not.toContain('This month')
    expect(noInsights.some((line) => line.includes('Outstanding loan principal'))).toBe(true)

    const bare = buildSchemeSummary(
      {
        summary: { ...summary, outstandingLoanPrincipal: undefined, overdueLoansCount: undefined },
        show: { ...none, loans: true },
      },
      t,
      money,
    )
    expect(bare).toEqual([])
  })

  it('omits invalid values instead of showing them as zero, and never emits NaN/Infinity or evaluative wording', () => {
    const lines = buildSchemeSummary(
      {
        summary: {
          ...summary,
          totalMembers: Number.NaN,
          activeMembers: Infinity,
          outstandingLoanPrincipal: 'oops',
          overdueLoansCount: Number.NaN,
        },
        insights: {
          ...insights,
          contributions: { ...insights.contributions, currentMonth: 'bad' },
          loans: {
            ...insights.loans,
            issuedAmountCurrentMonth: Infinity,
            repaidCurrentMonth: Number.NaN,
            outstandingPrincipal: '',
          },
          fines: { ...insights.fines, collectedCurrentMonth: 'x' },
        },
        show: all,
      },
      t,
      money,
    )
    expect(lines).toEqual([])

    const partial = buildSchemeSummary(
      { summary, insights: { ...insights, loans: { ...insights.loans, repaidCurrentMonth: Number.NaN } }, show: all },
      t,
      money,
    ).join(' ')
    expect(partial).toContain('was issued in loans')
    expect(partial).not.toContain('was repaid')
    expect(partial).not.toMatch(/NaN|Infinity|undefined|null/)
    expect(partial).not.toMatch(/(good|bad|healthy|poor|excellent|worrying|concerning)/i)
  })
})
