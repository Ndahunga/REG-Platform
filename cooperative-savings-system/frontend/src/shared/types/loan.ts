import type { ApprovalEvent } from './approval'
import { mapApprovalEvent } from './approval'

export type LoanStatus =
  | 'PENDING'
  | 'AWAITING_SECOND_APPROVAL'
  | 'APPROVED'
  | 'ACTIVE'
  | 'OVERDUE'
  | 'REJECTED'
  | 'CLOSED'
  | 'WRITTEN_OFF'

export type InterestType = 'FLAT' | 'REDUCING'

export type LoanRepaymentDateModel = 'SAME_DAY_OF_MONTH' | 'MONTH_END'

export type LoanPenaltyType =
  | 'FIXED_AMOUNT'
  | 'PERCENTAGE_OF_OVERDUE_INSTALLMENT'
  | 'PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE'

export type LoanPenaltyFrequency = 'ONE_TIME' | 'DAILY' | 'MONTHLY'

export type LoanRepaymentComponent = 'PENALTY' | 'INTEREST' | 'PRINCIPAL'

export const LOAN_REPAYMENT_DATE_MODELS: LoanRepaymentDateModel[] = [
  'SAME_DAY_OF_MONTH',
  'MONTH_END',
]

export const LOAN_PENALTY_TYPES: LoanPenaltyType[] = [
  'FIXED_AMOUNT',
  'PERCENTAGE_OF_OVERDUE_INSTALLMENT',
  'PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE',
]

export const LOAN_PENALTY_FREQUENCIES: LoanPenaltyFrequency[] = ['ONE_TIME', 'DAILY', 'MONTHLY']

export const LOAN_REPAYMENT_COMPONENTS: LoanRepaymentComponent[] = [
  'PENALTY',
  'INTEREST',
  'PRINCIPAL',
]

export type LoanGuaranteeMode = 'SELF' | 'GUARANTOR'

export const LOAN_GUARANTEE_MODES: LoanGuaranteeMode[] = ['SELF', 'GUARANTOR']

export const LOAN_STATUSES: LoanStatus[] = [
  'PENDING',
  'AWAITING_SECOND_APPROVAL',
  'APPROVED',
  'ACTIVE',
  'OVERDUE',
  'REJECTED',
  'CLOSED',
  'WRITTEN_OFF',
]

export const INTEREST_TYPES: InterestType[] = ['FLAT']

/** Display-only — REDUCING is blocked until business rule is confirmed. */
export const INTEREST_TYPES_DISPLAY: InterestType[] = ['FLAT', 'REDUCING']

export interface LoanShareTier {
  id?: string
  minSharePercent: string | number
  maxLoanAmount: string | number
}

export interface LoanSettings {
  id?: string
  cooperativeId?: string
  interestRatePercent: string | number
  interestType: InterestType | string
  maxLoanAmount?: string | number | null
  maxTermMonths?: number | null
  minMembershipMonths?: number | null
  allowMemberRequests: boolean
  lateFeeEnabled?: boolean
  loanPenaltyEnabled?: boolean
  repaymentDateModel?: LoanRepaymentDateModel | string
  penaltyType?: LoanPenaltyType | string
  penaltyRateOrAmount?: string | number | null
  penaltyFrequency?: LoanPenaltyFrequency | string
  gracePeriodDays?: number
  allocationOrder?: LoanRepaymentComponent[] | string[]
  currency?: string
  shareTiers?: LoanShareTier[]
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface LoanSettingsUpdateRequest {
  interestRatePercent: string | number
  interestType: InterestType | string
  maxLoanAmount?: string | number | null
  maxTermMonths?: number | null
  minMembershipMonths?: number | null
  allowMemberRequests: boolean
  lateFeeEnabled?: boolean
  loanPenaltyEnabled?: boolean
  repaymentDateModel?: LoanRepaymentDateModel | string
  penaltyType?: LoanPenaltyType | string
  penaltyRateOrAmount?: string | number | null
  penaltyFrequency?: LoanPenaltyFrequency | string
  gracePeriodDays?: number
  allocationOrder?: LoanRepaymentComponent[]
  shareTiers?: LoanShareTier[]
}

export interface LoanApplicationForm {
  cooperativeId?: string
  cooperativeName?: string | null
  currency?: string | null
  memberUserId?: string
  memberFullName?: string | null
  username?: string | null
  email?: string | null
  phone?: string | null
  nationalId?: string | null
  address?: string | null
  membershipDate?: string | null
  membershipStatus?: string | null
  roleInCooperative?: string | null
  requestedAmount?: string | number | null
  purpose?: string | null
  termMonths?: number | null
  interestRatePercent?: string | number | null
  interestType?: InterestType | string | null
  requestDate?: string | null
  submittedAt?: string | null
  eligibility?: LoanEligibility | null
}

export type LoanGuarantorStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'

export interface LoanEligibility {
  memberUserId?: string
  eligible: boolean
  reason?: string | null
  existingLoanAmount?: string | number | null
  amountAlreadyRepaid?: string | number | null
  outstandingBalance?: string | number | null
  requestedAmount?: string | number | null
  shareCount?: number | null
  totalShares?: number | null
  sharePercent?: string | number | null
  maxLoanByShares?: string | number | null
  maxEligibleAmount?: string | number | null
}

export interface LoanGuarantor {
  id: string
  cooperativeId?: string
  loanId: string
  borrowerUserId?: string | null
  borrowerName?: string | null
  loanAmount?: string | number | null
  loanStatus?: LoanStatus | string | null
  guarantorUserId: string
  guarantorName?: string | null
  guaranteedAmount?: string | number | null
  status: LoanGuarantorStatus | string
  requestedBy?: string | null
  requestedAt?: string | null
  respondedAt?: string | null
  responseComment?: string | null
}

export interface Loan {
  id: string
  cooperativeId?: string
  memberUserId: string
  memberName?: string | null
  fullName?: string
  username?: string
  requestedAmount: string | number
  approvedAmount?: string | number | null
  principalAmount?: string | number | null
  interestRatePercent: string | number
  interestType: InterestType | string
  termMonths: number
  interestAmount?: string | number | null
  prorataEnabled?: boolean
  firstPeriodDays?: number | null
  regularMonthlyInterest?: string | number | null
  firstPeriodInterest?: string | number | null
  totalRepayment?: string | number | null
  equalInstallmentAmount?: string | number | null
  repaymentDateModel?: LoanRepaymentDateModel | string | null
  loanPenaltyEnabled?: boolean
  penaltyType?: LoanPenaltyType | string | null
  penaltyRateOrAmount?: string | number | null
  penaltyFrequency?: LoanPenaltyFrequency | string | null
  gracePeriodDays?: number | null
  allocationOrder?: string | null
  scheduleFinalized?: boolean
  repaymentSchedule?: LoanInstallment[]
  outstandingPrincipal?: string | number | null
  outstandingInterest?: string | number | null
  outstandingPenalty?: string | number | null
  totalRepaidPrincipal?: string | number | null
  totalRepaidInterest?: string | number | null
  totalRepaidPenalty?: string | number | null
  requestDate?: string | null
  approvalDate?: string | null
  disbursementDate?: string | null
  dueDate?: string | null
  status: LoanStatus | string
  guaranteeMode?: LoanGuaranteeMode | string | null
  shareCount?: number | null
  sharePercent?: string | number | null
  maxLoanByShares?: string | number | null
  purpose?: string | null
  notes?: string | null
  rejectionReason?: string | null
  requestedBy?: string | null
  approvedBy?: string | null
  disbursedBy?: string | null
  firstApprovedBy?: string | null
  firstApprovedAt?: string | null
  firstApproverRole?: string | null
  applicationForm?: LoanApplicationForm | null
  eligibility?: LoanEligibility | null
  guarantor?: LoanGuarantor | null
  approvalHistory?: ApprovalEvent[]
  createdAt?: string
  updatedAt?: string
  version?: number
}

export type LoanInstallmentStatus = 'PENDING' | 'DUE' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE'

export interface LoanInstallment {
  id?: string
  installmentNumber: number
  dueDate?: string | null
  openingPrincipalBalance?: string | number | null
  paymentAmount?: string | number | null
  scheduledInstallmentAmount?: string | number | null
  principalComponent?: string | number | null
  interestComponent?: string | number | null
  penaltyDue?: string | number | null
  remainingPrincipal?: string | number | null
  status?: LoanInstallmentStatus | string | null
  amountPaid?: string | number | null
  balance?: string | number | null
  principalPaid?: string | number | null
  interestPaid?: string | number | null
  penaltyPaid?: string | number | null
  remainingAmount?: string | number | null
}

export interface LoanSchedulePreview {
  principal?: string | number | null
  monthlyInterestRatePercent?: string | number | null
  numberOfInstallments?: number | null
  repaymentDateModel?: LoanRepaymentDateModel | string | null
  prorataEnabled: boolean
  firstPeriodDays?: number | null
  daysInFirstMonth?: number | null
  regularMonthlyInterest?: string | number | null
  firstPeriodInterest?: string | number | null
  totalInterest?: string | number | null
  totalRepayment?: string | number | null
  equalInstallmentAmount?: string | number | null
  scheduleFinalized?: boolean
  installments?: LoanInstallment[]
}

export interface LoanRepaymentPreviewRequest {
  amount: string | number
  termMonths?: number
  referenceDate?: string
}

export interface LoanCreateRequest {
  /** Required when an admin issues a loan for another member. */
  memberUserId?: string
  amount: string | number
  termMonths?: number
  purpose?: string
  notes?: string
  guaranteeMode?: LoanGuaranteeMode
  guarantorUserId?: string
  guaranteedAmount?: string | number
}

export interface LoanApproveRequest {
  approvedAmount?: string | number
  termMonths?: number
  dueDate?: string
}

export interface LoanRejectRequest {
  rejectionReason: string
}

export interface LoanRepayment {
  id: string
  loanId: string
  cooperativeId?: string
  memberUserId?: string
  paymentDate: string
  amountTotal: string | number
  principalPortion: string | number
  interestPortion: string | number
  penaltyPortion?: string | number | null
  paymentReference?: string | null
  notes?: string | null
  recordedBy?: string | null
  createdAt?: string
}

export interface LoanRepaymentCreateRequest {
  amount: string | number
  paymentDate?: string
  paymentReference?: string
  notes?: string
  allocateInterestFirst?: boolean
}

export interface LoanListQuery {
  q?: string
  status?: string
  memberUserId?: string
  pendingApproval?: boolean
  page?: number
  size?: number
  sort?: string
}

export function mapLoanSettings(raw: LoanSettings): LoanSettings {
  return {
    ...raw,
    id: raw.id != null ? String(raw.id) : undefined,
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    interestRatePercent: raw.interestRatePercent ?? 0,
    interestType: raw.interestType || 'FLAT',
    allowMemberRequests: Boolean(raw.allowMemberRequests),
    lateFeeEnabled: Boolean(raw.lateFeeEnabled ?? raw.loanPenaltyEnabled),
    loanPenaltyEnabled: Boolean(raw.loanPenaltyEnabled ?? raw.lateFeeEnabled),
    repaymentDateModel: (raw.repaymentDateModel as LoanRepaymentDateModel) || 'SAME_DAY_OF_MONTH',
    penaltyType: (raw.penaltyType as LoanPenaltyType) || 'FIXED_AMOUNT',
    penaltyRateOrAmount: raw.penaltyRateOrAmount ?? 0,
    penaltyFrequency: (raw.penaltyFrequency as LoanPenaltyFrequency) || 'ONE_TIME',
    gracePeriodDays: Number(raw.gracePeriodDays ?? 0),
    allocationOrder:
      raw.allocationOrder && raw.allocationOrder.length > 0
        ? raw.allocationOrder
        : ['PENALTY', 'INTEREST', 'PRINCIPAL'],
    shareTiers: raw.shareTiers ?? [],
  }
}

export function mapLoan(raw: Loan): Loan {
  return {
    ...raw,
    id: String(raw.id),
    memberUserId: String(raw.memberUserId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    fullName: raw.fullName || raw.memberName || undefined,
    requestedAmount: raw.requestedAmount ?? 0,
    termMonths: Number(raw.termMonths ?? 0),
    interestRatePercent: raw.interestRatePercent ?? 0,
    interestType: raw.interestType || 'FLAT',
    prorataEnabled: Boolean(raw.prorataEnabled),
    repaymentSchedule: raw.repaymentSchedule ?? [],
    status: raw.status || 'PENDING',
    firstApprovedBy: raw.firstApprovedBy != null ? String(raw.firstApprovedBy) : null,
    approvalHistory: (raw.approvalHistory ?? []).map(mapApprovalEvent),
  }
}

export function mapLoanRepayment(raw: LoanRepayment): LoanRepayment {
  return {
    ...raw,
    id: String(raw.id),
    loanId: String(raw.loanId),
    cooperativeId: raw.cooperativeId != null ? String(raw.cooperativeId) : undefined,
    memberUserId: raw.memberUserId != null ? String(raw.memberUserId) : undefined,
    amountTotal: raw.amountTotal ?? 0,
    principalPortion: raw.principalPortion ?? 0,
    interestPortion: raw.interestPortion ?? 0,
  }
}

export function loanDisplayName(loan: Pick<Loan, 'fullName' | 'username' | 'memberUserId'>): string {
  return loan.fullName || loan.username || loan.memberUserId
}
