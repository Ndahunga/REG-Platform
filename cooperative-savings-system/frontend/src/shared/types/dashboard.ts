export interface DashboardSummary {
  totalMembers: number
  activeMembers: number
  regularContributionsTotal: string | number
  specialContributionsTotal: string | number
  actualContributionsTotal: string | number
  availableGroupFunds: string | number
  pendingSpecialApprovals?: number
  /** Present once Phase 5 loans backend is live. */
  totalLoanPrincipal?: string | number
  outstandingLoanPrincipal?: string | number
  loanInterestEarned?: string | number
  overdueLoansCount?: number
  /** Present once Phase 6 fines backend is live. */
  totalFines?: string | number
  unpaidFines?: string | number
  paidFines?: string | number
  approvedFineIncome?: string | number
  pendingFinePayments?: number
  membersWithFines?: number
  approvedFinePayments?: number
  rejectedFinePayments?: number
  /** Present once Phase 7 social fund backend is live. Separate from availableGroupFunds. */
  socialFundBalance?: string | number
  socialContributionsTotal?: string | number
  socialDisbursementsTotal?: string | number
  pendingSocialApprovals?: number
  /** Present once Phase 8 investments / income-expense backend is live. */
  activeInvestmentsCount?: number
  investmentCapital?: string | number
  investmentProfits?: string | number
  otherIncomeTotal?: string | number
  generalExpensesTotal?: string | number
  interestExpensesTotal?: string | number
  availableInterest?: string | number
  /** Present once Phase 9 payouts backend is live. */
  pendingPayoutsCount?: number
  totalConfirmedPayouts?: string | number
  currency?: string
}

export interface MonthlyContributionChartPoint {
  month: number
  totalPaid: string | number
}

export interface LoansDisbursedByMonthPoint {
  month: number
  loanCount: number
  principalAmount: string | number
}

export interface InvestmentsByMonthPoint {
  month: number
  capitalDeployed: string | number
  investmentCount: number
}

export interface AdvancedInsightsPeriod {
  year: number
  asOf: string
}

export interface FrequentBorrowerRow {
  memberId: string
  displayName: string
  numberOfLoansDisbursed: number
  totalPrincipalBorrowed: string | number
  rank: number
}

export interface LargestActiveInvestmentRow {
  investmentId: string
  name: string
  originalCapital: string | number
  remainingCapital: string | number
  profitReturned: string | number
  status: string
  rank: number
}

export interface DashboardAdvancedInsights {
  period: AdvancedInsightsPeriod
  largestActiveInvestments: LargestActiveInvestmentRow[]
  frequentBorrowers: FrequentBorrowerRow[]
  repaymentReliability?: RepaymentReliabilitySection | null
  currency?: string
  timezone?: string
}

export interface RepaymentReliabilitySection {
  period: string
  minimumSample: number
  dataQualityExcludedTotal?: number
  members: RepaymentReliabilityRow[]
}

export interface RepaymentReliabilityRow {
  memberId: string
  displayName: string
  installmentsDue: number
  installmentsPaidOnTime: number
  installmentsPaidLate: number
  installmentsUnpaidPastDue: number
  dataQualityExcluded?: number
  onTimeRate: string | number
  rank: number
}

export interface PlatformOverview {
  totalCooperatives: number
  activeCooperatives: number
  inactiveCooperatives: number
  suspendedCooperatives: number
  archivedCooperatives: number
  totalMembers: number
  activeMembers: number
  totalUsers: number
  pendingContributionReviews: number
  pendingSpecialContributions: number
  pendingLoans: number
  overdueLoans: number
  pendingFinePayments: number
  pendingSocialContributions: number
  pendingPayouts: number
}

export type MomChangeState = 'UP' | 'DOWN' | 'FLAT' | 'NO_BASELINE'

export interface DashboardInsightsPeriod {
  year: number
  month: number
  label?: string
  previousYear: number
  previousMonth: number
}

export interface ContributionsInsights {
  currentMonth: string | number
  previousMonth: string | number
  changePercent: string | number | null
  changeState: MomChangeState
}

export interface LoansInsights {
  issuedCountCurrentMonth: number
  issuedAmountCurrentMonth: string | number
  issuedCountPreviousMonth: number
  issuedAmountPreviousMonth: string | number
  issuedAmountChangePercent: string | number | null
  issuedAmountChangeState: MomChangeState
  repaidCurrentMonth: string | number
  repaidPreviousMonth: string | number
  repaidChangePercent?: string | number | null
  repaidChangeState?: MomChangeState
  outstandingPrincipal: string | number
}

export interface FinesInsights {
  issuedCountCurrentMonth: number
  issuedAmountCurrentMonth: string | number
  collectedCurrentMonth: string | number
  collectedPreviousMonth?: string | number
  collectedChangePercent?: string | number | null
  collectedChangeState?: MomChangeState
}

export interface DashboardInsights {
  period: DashboardInsightsPeriod
  contributions: ContributionsInsights
  loans: LoansInsights
  fines: FinesInsights
  currency?: string
  timezone?: string
}

export interface MemberInsightsPeriod {
  start: string
  end: string
}

export interface TopContributorRow {
  memberId: string
  displayName: string
  amount: string | number
  rank: number
}

export interface FineFollowUpRow {
  memberId: string
  displayName: string
  issuedAmount: string | number
  paidAmount: string | number
  outstandingAmount: string | number
  fineCount: number
  rank: number
}

export interface OverdueLoanInsightRow {
  memberId: string
  displayName: string
  overdueLoanCount: number
  outstandingPrincipal: string | number
  oldestDueDate?: string | null
  rank: number
}

export interface DashboardMemberInsights {
  period: MemberInsightsPeriod
  topContributors: TopContributorRow[]
  fineFollowUp: FineFollowUpRow[]
  overdueLoans: OverdueLoanInsightRow[]
  currency?: string
  timezone?: string
}

export function mapDashboardSummary(raw: DashboardSummary): DashboardSummary {
  return {
    totalMembers: Number(raw.totalMembers ?? 0),
    activeMembers: Number(raw.activeMembers ?? 0),
    regularContributionsTotal: raw.regularContributionsTotal ?? 0,
    specialContributionsTotal: raw.specialContributionsTotal ?? 0,
    actualContributionsTotal: raw.actualContributionsTotal ?? 0,
    availableGroupFunds: raw.availableGroupFunds ?? 0,
    pendingSpecialApprovals: raw.pendingSpecialApprovals ?? 0,
    totalLoanPrincipal:
      raw.totalLoanPrincipal != null ? raw.totalLoanPrincipal : undefined,
    outstandingLoanPrincipal:
      raw.outstandingLoanPrincipal != null ? raw.outstandingLoanPrincipal : undefined,
    loanInterestEarned:
      raw.loanInterestEarned != null ? raw.loanInterestEarned : undefined,
    overdueLoansCount:
      raw.overdueLoansCount != null ? Number(raw.overdueLoansCount) : undefined,
    totalFines: raw.totalFines != null ? raw.totalFines : undefined,
    unpaidFines: raw.unpaidFines != null ? raw.unpaidFines : undefined,
    paidFines: raw.paidFines != null ? raw.paidFines : undefined,
    approvedFineIncome:
      raw.approvedFineIncome != null ? raw.approvedFineIncome : undefined,
    pendingFinePayments:
      raw.pendingFinePayments != null ? Number(raw.pendingFinePayments) : undefined,
    membersWithFines:
      raw.membersWithFines != null ? Number(raw.membersWithFines) : undefined,
    approvedFinePayments:
      raw.approvedFinePayments != null ? Number(raw.approvedFinePayments) : undefined,
    rejectedFinePayments:
      raw.rejectedFinePayments != null ? Number(raw.rejectedFinePayments) : undefined,
    socialFundBalance:
      raw.socialFundBalance != null ? raw.socialFundBalance : undefined,
    socialContributionsTotal:
      raw.socialContributionsTotal != null ? raw.socialContributionsTotal : undefined,
    socialDisbursementsTotal:
      raw.socialDisbursementsTotal != null ? raw.socialDisbursementsTotal : undefined,
    pendingSocialApprovals:
      raw.pendingSocialApprovals != null ? Number(raw.pendingSocialApprovals) : undefined,
    activeInvestmentsCount:
      raw.activeInvestmentsCount != null ? Number(raw.activeInvestmentsCount) : undefined,
    investmentCapital:
      raw.investmentCapital != null ? raw.investmentCapital : undefined,
    investmentProfits:
      raw.investmentProfits != null ? raw.investmentProfits : undefined,
    otherIncomeTotal: raw.otherIncomeTotal != null ? raw.otherIncomeTotal : undefined,
    generalExpensesTotal:
      raw.generalExpensesTotal != null ? raw.generalExpensesTotal : undefined,
    interestExpensesTotal:
      raw.interestExpensesTotal != null ? raw.interestExpensesTotal : undefined,
    availableInterest:
      raw.availableInterest != null ? raw.availableInterest : undefined,
    pendingPayoutsCount:
      raw.pendingPayoutsCount != null ? Number(raw.pendingPayoutsCount) : undefined,
    totalConfirmedPayouts:
      raw.totalConfirmedPayouts != null ? raw.totalConfirmedPayouts : undefined,
    currency: raw.currency || 'RWF',
  }
}

export function mapMonthlyContributionChartPoint(
  raw: MonthlyContributionChartPoint,
): MonthlyContributionChartPoint {
  return {
    month: Number(raw.month),
    totalPaid: raw.totalPaid ?? 0,
  }
}

export function mapLoansDisbursedByMonthPoint(
  raw: LoansDisbursedByMonthPoint,
): LoansDisbursedByMonthPoint {
  return {
    month: Number(raw.month),
    loanCount: Number(raw.loanCount ?? 0),
    principalAmount: raw.principalAmount ?? 0,
  }
}

export function mapInvestmentsByMonthPoint(raw: InvestmentsByMonthPoint): InvestmentsByMonthPoint {
  return {
    month: Number(raw.month),
    capitalDeployed: raw.capitalDeployed ?? 0,
    investmentCount: Number(raw.investmentCount ?? 0),
  }
}

export function mapDashboardAdvancedInsights(
  raw: DashboardAdvancedInsights,
): DashboardAdvancedInsights {
  return {
    period: {
      year: Number(raw.period?.year ?? 0),
      asOf: raw.period?.asOf ?? '',
    },
    largestActiveInvestments: (raw.largestActiveInvestments ?? []).map((row, index) => ({
      investmentId: String(row.investmentId),
      name: row.name || String(row.investmentId),
      originalCapital: row.originalCapital ?? 0,
      remainingCapital: row.remainingCapital ?? 0,
      profitReturned: row.profitReturned ?? 0,
      status: row.status || '',
      rank: Number(row.rank ?? index + 1),
    })),
    frequentBorrowers: (raw.frequentBorrowers ?? []).map((row, index) => ({
      memberId: String(row.memberId),
      displayName: row.displayName || String(row.memberId),
      numberOfLoansDisbursed: Number(row.numberOfLoansDisbursed ?? 0),
      totalPrincipalBorrowed: row.totalPrincipalBorrowed ?? 0,
      rank: Number(row.rank ?? index + 1),
    })),
    repaymentReliability: raw.repaymentReliability
      ? {
          period: raw.repaymentReliability.period || 'LIFETIME',
          minimumSample: Number(raw.repaymentReliability.minimumSample ?? 3),
          dataQualityExcludedTotal: Number(raw.repaymentReliability.dataQualityExcludedTotal ?? 0),
          members: (raw.repaymentReliability.members ?? []).map((row, index) => ({
            memberId: String(row.memberId),
            displayName: row.displayName || String(row.memberId),
            installmentsDue: Number(row.installmentsDue ?? 0),
            installmentsPaidOnTime: Number(row.installmentsPaidOnTime ?? 0),
            installmentsPaidLate: Number(row.installmentsPaidLate ?? 0),
            installmentsUnpaidPastDue: Number(row.installmentsUnpaidPastDue ?? 0),
            dataQualityExcluded: Number(row.dataQualityExcluded ?? 0),
            onTimeRate: row.onTimeRate ?? 0,
            rank: Number(row.rank ?? index + 1),
          })),
        }
      : null,
    currency: raw.currency || 'RWF',
    timezone: raw.timezone || 'Africa/Kigali',
  }
}

export function mapPlatformOverview(raw: PlatformOverview): PlatformOverview {
  return {
    totalCooperatives: Number(raw.totalCooperatives ?? 0),
    activeCooperatives: Number(raw.activeCooperatives ?? 0),
    inactiveCooperatives: Number(raw.inactiveCooperatives ?? 0),
    suspendedCooperatives: Number(raw.suspendedCooperatives ?? 0),
    archivedCooperatives: Number(raw.archivedCooperatives ?? 0),
    totalMembers: Number(raw.totalMembers ?? 0),
    activeMembers: Number(raw.activeMembers ?? 0),
    totalUsers: Number(raw.totalUsers ?? 0),
    pendingContributionReviews: Number(raw.pendingContributionReviews ?? 0),
    pendingSpecialContributions: Number(raw.pendingSpecialContributions ?? 0),
    pendingLoans: Number(raw.pendingLoans ?? 0),
    overdueLoans: Number(raw.overdueLoans ?? 0),
    pendingFinePayments: Number(raw.pendingFinePayments ?? 0),
    pendingSocialContributions: Number(raw.pendingSocialContributions ?? 0),
    pendingPayouts: Number(raw.pendingPayouts ?? 0),
  }
}

function mapMomState(raw: string | undefined): MomChangeState {
  if (raw === 'UP' || raw === 'DOWN' || raw === 'FLAT' || raw === 'NO_BASELINE') return raw
  return 'FLAT'
}

export function mapDashboardInsights(raw: DashboardInsights): DashboardInsights {
  return {
    period: {
      year: Number(raw.period?.year ?? 0),
      month: Number(raw.period?.month ?? 0),
      label: raw.period?.label,
      previousYear: Number(raw.period?.previousYear ?? 0),
      previousMonth: Number(raw.period?.previousMonth ?? 0),
    },
    contributions: {
      currentMonth: raw.contributions?.currentMonth ?? 0,
      previousMonth: raw.contributions?.previousMonth ?? 0,
      changePercent:
        raw.contributions?.changePercent === undefined || raw.contributions?.changePercent === null
          ? null
          : raw.contributions.changePercent,
      changeState: mapMomState(raw.contributions?.changeState),
    },
    loans: {
      issuedCountCurrentMonth: Number(raw.loans?.issuedCountCurrentMonth ?? 0),
      issuedAmountCurrentMonth: raw.loans?.issuedAmountCurrentMonth ?? 0,
      issuedCountPreviousMonth: Number(raw.loans?.issuedCountPreviousMonth ?? 0),
      issuedAmountPreviousMonth: raw.loans?.issuedAmountPreviousMonth ?? 0,
      issuedAmountChangePercent:
        raw.loans?.issuedAmountChangePercent === undefined ||
        raw.loans?.issuedAmountChangePercent === null
          ? null
          : raw.loans.issuedAmountChangePercent,
      issuedAmountChangeState: mapMomState(raw.loans?.issuedAmountChangeState),
      repaidCurrentMonth: raw.loans?.repaidCurrentMonth ?? 0,
      repaidPreviousMonth: raw.loans?.repaidPreviousMonth ?? 0,
      repaidChangePercent:
        raw.loans?.repaidChangePercent === undefined || raw.loans?.repaidChangePercent === null
          ? null
          : raw.loans.repaidChangePercent,
      repaidChangeState: mapMomState(raw.loans?.repaidChangeState),
      outstandingPrincipal: raw.loans?.outstandingPrincipal ?? 0,
    },
    fines: {
      issuedCountCurrentMonth: Number(raw.fines?.issuedCountCurrentMonth ?? 0),
      issuedAmountCurrentMonth: raw.fines?.issuedAmountCurrentMonth ?? 0,
      collectedCurrentMonth: raw.fines?.collectedCurrentMonth ?? 0,
      collectedPreviousMonth: raw.fines?.collectedPreviousMonth ?? 0,
      collectedChangePercent:
        raw.fines?.collectedChangePercent === undefined ||
        raw.fines?.collectedChangePercent === null
          ? null
          : raw.fines.collectedChangePercent,
      collectedChangeState: mapMomState(raw.fines?.collectedChangeState),
    },
    currency: raw.currency || 'RWF',
    timezone: raw.timezone || 'Africa/Kigali',
  }
}

export function mapDashboardMemberInsights(raw: DashboardMemberInsights): DashboardMemberInsights {
  return {
    period: {
      start: raw.period?.start ?? '',
      end: raw.period?.end ?? '',
    },
    topContributors: (raw.topContributors ?? []).map((row, index) => ({
      memberId: String(row.memberId),
      displayName: row.displayName || String(row.memberId),
      amount: row.amount ?? 0,
      rank: Number(row.rank ?? index + 1),
    })),
    fineFollowUp: (raw.fineFollowUp ?? []).map((row, index) => ({
      memberId: String(row.memberId),
      displayName: row.displayName || String(row.memberId),
      issuedAmount: row.issuedAmount ?? 0,
      paidAmount: row.paidAmount ?? 0,
      outstandingAmount: row.outstandingAmount ?? 0,
      fineCount: Number(row.fineCount ?? 0),
      rank: Number(row.rank ?? index + 1),
    })),
    overdueLoans: (raw.overdueLoans ?? []).map((row, index) => ({
      memberId: String(row.memberId),
      displayName: row.displayName || String(row.memberId),
      overdueLoanCount: Number(row.overdueLoanCount ?? 0),
      outstandingPrincipal: row.outstandingPrincipal ?? 0,
      oldestDueDate: row.oldestDueDate ?? null,
      rank: Number(row.rank ?? index + 1),
    })),
    currency: raw.currency || 'RWF',
    timezone: raw.timezone || 'Africa/Kigali',
  }
}
