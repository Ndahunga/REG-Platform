import * as yup from 'yup'
import type {
  InterestType,
  LoanGuaranteeMode,
  LoanPenaltyFrequency,
  LoanPenaltyType,
  LoanRepaymentComponent,
  LoanRepaymentDateModel,
} from '@/shared/types/loan'
import type { LoanApproveRequest, LoanCreateRequest } from '@/shared/types/loan'
import type { LoanRepaymentCreateRequest } from '@/shared/types/loan'
import type { LoanSettingsUpdateRequest } from '@/shared/types/loan'
import { checkMoney } from '@/shared/utils/formValidation'
import { dateField, integerField, moneyField } from '@/shared/utils/yupRules'

/** Backend: @DecimalMin("0.01"), at most 15 whole digits and 4 decimals. */
const positiveMoney = moneyField({ label: 'Amount' })

/** Backend: termMonths @Min(1) @Max(600). */
const TERM_MESSAGE = 'Enter a valid term in months (1 to 600)'
export const MAX_LOAN_TERM_MONTHS = 600

export type LoanRequestFormValues = {
  memberUserId: string
  amount: string
  termMonths: string
  purpose: string
  notes: string
  guaranteeMode: LoanGuaranteeMode
  guarantorUserId: string
  guaranteedAmount: string
}

export const loanRequestDefaults: LoanRequestFormValues = {
  memberUserId: '',
  amount: '',
  termMonths: '',
  purpose: '',
  notes: '',
  guaranteeMode: 'SELF',
  guarantorUserId: '',
  guaranteedAmount: '',
}

export function loanRequestSchema(requireMember: boolean): yup.ObjectSchema<LoanRequestFormValues> {
  return yup.object({
    memberUserId: requireMember
      ? yup.string().trim().required('Select a member')
      : yup.string().trim().default(''),
    amount: positiveMoney,
    termMonths: integerField({ min: 1, max: MAX_LOAN_TERM_MONTHS, message: TERM_MESSAGE }),
    purpose: requireMember
      ? yup.string().trim().max(500).default('')
      : yup.string().trim().required('Loan purpose is required').max(500),
    notes: yup.string().trim().max(2000).default(''),
    guaranteeMode: yup.mixed<LoanGuaranteeMode>().oneOf(['SELF', 'GUARANTOR']).required(),
    guarantorUserId: yup
      .string()
      .trim()
      .default('')
      .test('guarantor', 'Select a guarantor', function (value) {
        if (this.parent.guaranteeMode !== 'GUARANTOR') return true
        return Boolean(value)
      }),
    guaranteedAmount: yup
      .string()
      .trim()
      .default('')
      .test('guaranteed', 'Enter a valid guaranteed amount', function (value) {
        if (this.parent.guaranteeMode !== 'GUARANTOR') return true
        if (!value) return false
        return checkMoney(value, { min: '0.01' }) === null
      })
      .test('guaranteed-max', 'Guaranteed amount cannot exceed the requested loan amount', function (value) {
        if (this.parent.guaranteeMode !== 'GUARANTOR') return true
        const amount = String(this.parent.amount ?? '').trim()
        if (checkMoney(value, { min: '0.01' }) !== null || checkMoney(amount, { min: '0.01' }) !== null) return true
        return Number(value) <= Number(amount)
      }),
  })
}

export function toLoanCreatePayload(
  values: LoanRequestFormValues,
  includeMember: boolean,
): LoanCreateRequest {
  const payload: LoanCreateRequest = {
    amount: values.amount.trim(),
    purpose: values.purpose.trim() || undefined,
    notes: values.notes.trim() || undefined,
    guaranteeMode: values.guaranteeMode,
  }
  if (values.termMonths.trim()) {
    payload.termMonths = Number(values.termMonths.trim())
  }
  if (includeMember && values.memberUserId.trim()) {
    payload.memberUserId = values.memberUserId.trim()
  }
  if (values.guaranteeMode === 'GUARANTOR') {
    if (values.guarantorUserId.trim()) {
      payload.guarantorUserId = values.guarantorUserId.trim()
    }
    if (values.guaranteedAmount.trim()) {
      payload.guaranteedAmount = values.guaranteedAmount.trim()
    }
  }
  return payload
}

export type LoanApproveFormValues = {
  approvedAmount: string
  termMonths: string
}

export const loanApproveDefaults: LoanApproveFormValues = {
  approvedAmount: '',
  termMonths: '',
}

export const loanApproveSchema: yup.ObjectSchema<LoanApproveFormValues> = yup.object({
  approvedAmount: yup
    .string()
    .trim()
    .default('')
    .test('money', 'Enter a valid amount', (v) => !v || /^\d+(\.\d+)?$/.test(v))
    .test('money-range', 'Amount must be at least 0.01 with at most 4 decimals', (v) => {
      if (!v || !/^\d+(\.\d+)?$/.test(v)) return true
      return checkMoney(v, { min: '0.01' }) === null
    }),
  termMonths: integerField({ min: 1, max: MAX_LOAN_TERM_MONTHS, message: TERM_MESSAGE }),
})

export function toLoanApprovePayload(values: LoanApproveFormValues): LoanApproveRequest {
  const payload: LoanApproveRequest = {}
  if (values.approvedAmount.trim()) {
    payload.approvedAmount = values.approvedAmount.trim()
  }
  if (values.termMonths.trim()) {
    payload.termMonths = Number(values.termMonths.trim())
  }
  return payload
}

export type LoanRejectFormValues = {
  rejectionReason: string
}

export const loanRejectDefaults: LoanRejectFormValues = {
  rejectionReason: '',
}

export const loanRejectSchema: yup.ObjectSchema<LoanRejectFormValues> = yup.object({
  rejectionReason: yup.string().trim().required('Rejection reason is required').max(1000),
})

export type RepaymentFormValues = {
  amount: string
  paymentDate: string
  paymentReference: string
  notes: string
}

export const repaymentDefaults: RepaymentFormValues = {
  amount: '',
  paymentDate: '',
  paymentReference: '',
  notes: '',
}

export const repaymentSchema: yup.ObjectSchema<RepaymentFormValues> = yup.object({
  amount: positiveMoney,
  paymentDate: dateField({ label: 'Payment date' }),
  paymentReference: yup.string().trim().max(128).default(''),
  notes: yup.string().trim().max(2000).default(''),
})

export function toRepaymentPayload(values: RepaymentFormValues): LoanRepaymentCreateRequest {
  return {
    amount: values.amount.trim(),
    paymentDate: values.paymentDate.trim() || undefined,
    paymentReference: values.paymentReference.trim() || undefined,
    notes: values.notes.trim() || undefined,
    allocateInterestFirst: true,
  }
}

export type LoanShareTierFormValues = {
  minSharePercent: string
  maxLoanAmount: string
}

export type LoanSettingsFormValues = {
  interestRatePercent: string
  interestType: InterestType
  maxLoanAmount: string
  maxTermMonths: string
  minMembershipMonths: string
  allowMemberRequests: boolean
  repaymentDateModel: LoanRepaymentDateModel
  loanPenaltyEnabled: boolean
  penaltyType: LoanPenaltyType
  penaltyRateOrAmount: string
  penaltyFrequency: LoanPenaltyFrequency
  gracePeriodDays: string
  allocationOrder: LoanRepaymentComponent[]
  shareTiers: LoanShareTierFormValues[]
}

export const loanSettingsDefaults: LoanSettingsFormValues = {
  interestRatePercent: '0',
  interestType: 'FLAT',
  maxLoanAmount: '',
  maxTermMonths: '',
  minMembershipMonths: '0',
  allowMemberRequests: true,
  repaymentDateModel: 'SAME_DAY_OF_MONTH',
  loanPenaltyEnabled: false,
  penaltyType: 'FIXED_AMOUNT',
  penaltyRateOrAmount: '0',
  penaltyFrequency: 'ONE_TIME',
  gracePeriodDays: '0',
  allocationOrder: ['PENALTY', 'INTEREST', 'PRINCIPAL'],
  shareTiers: [],
}

export const loanSettingsSchema: yup.ObjectSchema<LoanSettingsFormValues> = yup.object({
  interestRatePercent: yup
    .string()
    .trim()
    .required('Interest rate is required')
    .matches(/^\d+(\.\d{1,4})?$/, 'Enter a valid rate')
    .test('range', 'Rate must be 0–100', (v) => {
      if (!v) return false
      const n = Number(v)
      return n >= 0 && n <= 100
    }),
  interestType: yup
    .mixed<InterestType>()
    .oneOf(['FLAT'], 'Reducing-balance interest is not currently available. Please use Flat Interest.')
    .required(),
  maxLoanAmount: yup
    .string()
    .trim()
    .default('')
    .test('money', 'Enter a valid amount', (v) => !v || /^\d+(\.\d{1,4})?$/.test(v)),
  maxTermMonths: integerField({ min: 1, max: MAX_LOAN_TERM_MONTHS, message: TERM_MESSAGE }),
  minMembershipMonths: integerField({
    min: 0,
    max: MAX_LOAN_TERM_MONTHS,
    message: 'Enter a whole number of months between 0 and 600',
  }).default('0'),
  allowMemberRequests: yup.boolean().required(),
  repaymentDateModel: yup
    .mixed<LoanRepaymentDateModel>()
    .oneOf(['SAME_DAY_OF_MONTH', 'MONTH_END'])
    .required(),
  loanPenaltyEnabled: yup.boolean().required(),
  penaltyType: yup
    .mixed<LoanPenaltyType>()
    .oneOf([
      'FIXED_AMOUNT',
      'PERCENTAGE_OF_OVERDUE_INSTALLMENT',
      'PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE',
    ])
    .required(),
  penaltyRateOrAmount: yup
    .string()
    .trim()
    .default('0')
    .matches(/^\d+(\.\d{1,4})?$/, 'Enter a valid amount or rate')
    .test('penalty-required', 'Enter a penalty amount or rate', function (value) {
      if (!this.parent.loanPenaltyEnabled) return true
      return Boolean(value && Number(value) > 0)
    }),
  penaltyFrequency: yup
    .mixed<LoanPenaltyFrequency>()
    .oneOf(['ONE_TIME', 'DAILY', 'MONTHLY'])
    .required(),
  gracePeriodDays: yup
    .string()
    .trim()
    .default('0')
    .test('grace', 'Enter a valid number of days', (v) => {
      if (!v) return true
      const n = Number(v)
      return Number.isInteger(n) && n >= 0 && n <= 365
    }),
  allocationOrder: yup
    .array()
    .of(yup.mixed<LoanRepaymentComponent>().oneOf(['PENALTY', 'INTEREST', 'PRINCIPAL']).required())
    .length(3, 'Choose penalty, interest, and principal exactly once')
    .test('unique', 'Each allocation component can appear only once', (value) => {
      if (!value) return false
      return new Set(value).size === 3
    })
    .required(),
  shareTiers: yup
    .array()
    .of(
      yup.object({
        minSharePercent: yup
          .string()
          .trim()
          .required('Share % is required')
          .test('pct', 'Enter a percentage between 0.0001 and 100 (up to 4 decimals)', (v) => {
            if (!v || !/^\d+(\.\d{1,4})?$/.test(v)) return false
            const n = Number(v)
            return n >= 0.0001 && n <= 100
          }),
        maxLoanAmount: yup
          .string()
          .trim()
          .required('Loan amount is required')
          .test('positive', 'Enter an amount of at least 0.01 with at most 4 decimals', (v) =>
            checkMoney(v, { min: '0.01' }) === null,
          ),
      }),
    )
    .default([]),
})

export function toLoanSettingsPayload(
  values: LoanSettingsFormValues,
  includeShareTiers: boolean,
): LoanSettingsUpdateRequest {
  const payload: LoanSettingsUpdateRequest = {
    interestRatePercent: values.interestRatePercent.trim(),
    interestType: values.interestType,
    maxLoanAmount: values.maxLoanAmount.trim() || null,
    maxTermMonths: values.maxTermMonths.trim()
      ? Number(values.maxTermMonths.trim())
      : null,
    minMembershipMonths: values.minMembershipMonths.trim()
      ? Number(values.minMembershipMonths.trim())
      : 0,
    allowMemberRequests: values.allowMemberRequests,
    lateFeeEnabled: values.loanPenaltyEnabled,
    loanPenaltyEnabled: values.loanPenaltyEnabled,
    repaymentDateModel: values.repaymentDateModel,
    penaltyType: values.penaltyType,
    penaltyRateOrAmount: values.penaltyRateOrAmount.trim() || '0',
    penaltyFrequency: values.penaltyFrequency,
    gracePeriodDays: values.gracePeriodDays.trim() ? Number(values.gracePeriodDays.trim()) : 0,
    allocationOrder: values.allocationOrder,
  }
  if (includeShareTiers) {
    payload.shareTiers = values.shareTiers.map((tier) => ({
      minSharePercent: tier.minSharePercent.trim(),
      maxLoanAmount: tier.maxLoanAmount.trim(),
    }))
  }
  return payload
}
