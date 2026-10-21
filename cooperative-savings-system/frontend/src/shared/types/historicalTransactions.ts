import type { SchemeOperatingModel } from '@/shared/types/cooperative'

export type HistoricalBatchStatus = 'PREVIEWED' | 'POSTED' | 'CANCELLED'

export interface HistoricalIssue {
  code: string
  field: string
  message: string
}

export interface HistoricalYearTotals {
  year: number
  credits: string | number
  debits: string | number
  net: string | number
  rows: number
}

export interface HistoricalTypeTotals {
  type: string
  rows: number
  amount: string | number
}

export interface HistoricalLoanSummary {
  reference: string
  username: string
  disbursed: string | number
  principalRepaid: string | number
  interestPaid: string | number
  closed: boolean
}

export interface HistoricalReconciliation {
  currency: string
  opening: string | number | null
  declaredClosing: string | number | null
  computedClosing: string | number | null
  variance: string | number | null
  balanced: boolean
  historyEnd: string | null
  byYear: HistoricalYearTotals[]
  byType: HistoricalTypeTotals[]
  loans: HistoricalLoanSummary[]
  /** Contributions with no original-period rotation attribution: never payout-eligible. */
  unresolvedAttribution: { amount: string | number; rows: number; byModel: Record<string, string | number> }
}

export interface HistoricalBatch {
  id: string
  status: HistoricalBatchStatus
  fileName: string
  /** True when the same file was uploaded before and the existing batch is returned. */
  reused: boolean
  totalRows: number
  validRows: number
  errorRows: number
  errorCount: number
  canApprove: boolean
  currency: string | null
  historyEnd: string | null
  openingBalance: string | number | null
  declaredClosingBalance: string | number | null
  computedClosingBalance: string | number | null
  balanceVariance: string | number | null
  varianceAcknowledged: boolean
  varianceNote: string | null
  uploadedBy: string
  uploadedAt: string
  approvedBy: string | null
  approvedAt: string | null
  cancelledAt: string | null
  summary: {
    reconciliation: HistoricalReconciliation
    warnings: string[]
    sheetIssues: HistoricalIssue[]
  } | null
}

export interface HistoricalRow {
  sheet: 'MEMBERS' | 'TRANSACTIONS'
  rowNumber: number
  rowType: string
  username: string | null
  date: string | null
  amount: string | number | null
  originalReference: string | null
  periodYear: number | null
  periodMonth: number | null
  loanReference: string | null
  operatingModel: string | null
  settingKey: string | null
  settingValue: string | null
  valid: boolean
  errors: HistoricalIssue[]
  posted: boolean
}

export interface HistoricalEvent {
  actorUserId: string
  actorRole: string
  fromStatus: HistoricalBatchStatus | null
  toStatus: HistoricalBatchStatus
  comment: string | null
  createdAt: string
}

// ---------------------------------------------------------------- model report

export interface ModelReport {
  cooperativeId: string
  currency: string
  operatingModel: SchemeOperatingModel | null
  from: string
  to: string
  generatedAt: string
  position: {
    expectedContributions: string | number
    postedCollections: string | number
    contributionArrears: string | number
    outstandingContributions: string | number
    retainedCollections: string | number
    unclassifiedCollections: string | number
    unresolvedRotatingCollections: string | number
    rotatingCollected: string | number
    rotatingRequiringReconciliation: string | number
    rotatingExpected: string | number
    rotatingArrears: string | number
    recipientEntitlementsOwed: string | number
    totalRotatingLiabilities: string | number
    payoutsDisbursed: string | number
    payoutsOpen: string | number
    availableFunds: string | number
    availableAfterEntitlements: string | number
  }
  movements: {
    contributionsCollected: string | number
    loansDisbursed: string | number
    principalRepaid: string | number
    memberPayouts: string | number
  }
  loans: { outstandingPrincipal: string | number; unpaidInterest: string | number }
  shares: { totalShares: number; members: number; holders: { userId: string; name: string; shares: number }[] }
  earnings: {
    interestIncome: string | number
    fineIncome: string | number
    investmentProfit: string | number
    otherIncome: string | number
    totalIncome: string | number
    operatingExpenses: string | number
    interestExpense: string | number
    totalExpenses: string | number
    netEarnings: string | number
    /** Always UNAVAILABLE_PENDING_POLICY: no distribution policy is approved. */
    distributionStatus: string
  }
  rotation: {
    periods: {
      periodId: string
      year: number
      month: number
      recipientName: string
      expectedRotating: string | number
      collectedRotating: string | number
      unreconciledRotating: string | number
      rotatingArrears: string | number
      distributed: string | number
      outstandingEntitlement: string | number
    }[]
    verifiedCollected: string | number
    unreconciled: string | number
    unresolvedRotating: string | number
    unpaidEntitlements: string | number
    paidOut: string | number
  } | null
  hybrid: {
    retainedCapital: string | number
    rotatingHeld: string | number
    retainedCapitalShareBps: number | null
    earningsAttributableToRetained: string | number | null
    attributionBasis: string
  } | null
  notes: string[]
}
