import * as yup from 'yup'
import type { FineCalculationMode } from '@/shared/types/fine'
import type { FineCreateRequest } from '@/shared/types/fine'
import type { FinePaymentCreateRequest, FinePaymentMethod } from '@/shared/types/fine'
import type { FineSettingsUpdateRequest } from '@/shared/types/fine'
import { checkMoney } from '@/shared/utils/formValidation'
import { dateField, moneyField } from '@/shared/utils/yupRules'

const positiveMoney = moneyField({ label: 'Amount' })

export type FineIssueFormValues = {
  memberUserId: string
  calculationMode: FineCalculationMode
  amount: string
  baseAmount: string
  dailyIncrement: string
  overdueDays: string
  reason: string
  notes: string
  issuedDate: string
  dueDate: string
}

export const fineIssueDefaults: FineIssueFormValues = {
  memberUserId: '',
  calculationMode: 'FIXED',
  amount: '',
  baseAmount: '',
  dailyIncrement: '',
  overdueDays: '',
  reason: '',
  notes: '',
  issuedDate: '',
  dueDate: '',
}

export const fineIssueSchema: yup.ObjectSchema<FineIssueFormValues> = yup.object({
  memberUserId: yup.string().trim().required('Select a member'),
  calculationMode: yup.mixed<FineCalculationMode>().oneOf(['FIXED', 'PROGRESSIVE']).required(),
  amount: yup
    .string()
    .trim()
    .default('')
    .when('calculationMode', {
      is: 'FIXED',
      then: (schema) =>
        schema.test('fixed-amount', function (value) {
          const problem = checkMoney(value, { min: '0.01' })
          return problem === null
            ? true
            : this.createError({
                message:
                  problem === 'required'
                    ? 'Amount is required'
                    : problem === 'invalid'
                      ? 'Enter a valid amount'
                      : problem === 'precision'
                        ? 'Use at most 4 decimal places'
                        : problem === 'tooLarge'
                          ? 'Amount is too large'
                          : 'Amount must be at least 0.01',
              })
        }),
      otherwise: (schema) => schema.default(''),
    }),
  baseAmount: yup
    .string()
    .trim()
    .default('')
    .when('calculationMode', {
      is: 'PROGRESSIVE',
      then: (schema) =>
        schema
          .required('Base amount is required')
          .test('base-money', 'Enter a valid amount (0 or more, up to 4 decimals)', (v) =>
            checkMoney(v, { allowZero: true }) === null,
          )
          .test('progressive-total', 'The fine total must be greater than 0', function (value) {
            // Total = base + daily increment x overdue days. When the increment is blank the backend uses the
            // cooperative's setting, so only a total that is provably 0 is rejected here.
            const increment = String(this.parent.dailyIncrement ?? '').trim()
            const days = String(this.parent.overdueDays ?? '').trim()
            if (checkMoney(value, { allowZero: true }) !== null || !increment) return true
            if (checkMoney(increment, { allowZero: true }) !== null) return true
            const total = Number(value) + Number(increment) * Number(days || 0)
            return total > 0
          }),
      otherwise: (schema) => schema.default(''),
    }),
  dailyIncrement: moneyField({ allowEmpty: true, allowZero: true, label: 'Daily increment' }),
  overdueDays: yup
    .string()
    .trim()
    .default('')
    .test('days', 'Enter a whole number of days (0 to 100000)', (v) => {
      if (!v) return true
      return /^\d+$/.test(v) && Number(v) <= 100000
    }),
  reason: yup.string().trim().required('Reason is required').max(2000),
  notes: yup.string().trim().max(2000).default(''),
  issuedDate: dateField({ required: false, label: 'Issued date' }),
  dueDate: dateField({ required: false, noFuture: false, label: 'Due date' }).test(
    'due-after-issued',
    'Due date must be on or after the issued date',
    function (value) {
      const issued = String(this.parent.issuedDate ?? '').trim()
      if (!value || !issued) return true
      return value >= issued
    },
  ),
})

export function toFineCreatePayload(values: FineIssueFormValues): FineCreateRequest {
  const payload: FineCreateRequest = {
    memberUserId: values.memberUserId.trim(),
    calculationMode: values.calculationMode,
    reason: values.reason.trim(),
    notes: values.notes.trim() || undefined,
    issuedDate: values.issuedDate.trim() || undefined,
    dueDate: values.dueDate.trim() || undefined,
  }
  if (values.calculationMode === 'FIXED') {
    payload.amount = values.amount.trim()
  } else {
    payload.baseAmount = values.baseAmount.trim()
    if (values.dailyIncrement.trim()) {
      payload.dailyIncrement = values.dailyIncrement.trim()
    }
    if (values.overdueDays.trim()) {
      payload.overdueDays = Number(values.overdueDays.trim())
    }
  }
  return payload
}

export type FinePaymentFormValues = {
  amount: string
  paymentDate: string
  paymentMethod: FinePaymentMethod
  paymentMethodDetail: string
  paymentReference: string
  notes: string
  evidenceFileKey: string
}

export const finePaymentDefaults: FinePaymentFormValues = {
  amount: '',
  paymentDate: '',
  paymentMethod: 'CASH',
  paymentMethodDetail: '',
  paymentReference: '',
  notes: '',
  evidenceFileKey: '',
}

export function finePaymentSchema(
  outstanding?: number | null,
): yup.ObjectSchema<FinePaymentFormValues> {
  return yup.object({
    amount: positiveMoney.test('max-outstanding', 'Amount cannot exceed outstanding', (v) => {
      if (!v) return false
      if (outstanding == null || Number.isNaN(outstanding)) return true
      return Number(v) <= outstanding
    }),
    paymentDate: dateField({ label: 'Payment date' }),
    paymentMethod: yup
      .mixed<FinePaymentMethod>()
      .oneOf(['CASH', 'MOBILE_MONEY', 'BANK_TRANSFER', 'OTHER'])
      .required('Payment method is required'),
    paymentMethodDetail: yup.string().trim().max(255).default(''),
    paymentReference: yup.string().trim().max(128).default(''),
    notes: yup.string().trim().max(2000).default(''),
    evidenceFileKey: yup.string().trim().max(512).default(''),
  })
}

export function toFinePaymentPayload(
  values: FinePaymentFormValues,
): FinePaymentCreateRequest {
  return {
    amount: values.amount.trim(),
    paymentDate: values.paymentDate.trim(),
    paymentMethod: values.paymentMethod,
    paymentMethodDetail: values.paymentMethodDetail.trim() || undefined,
    paymentReference: values.paymentReference.trim() || undefined,
    notes: values.notes.trim() || undefined,
    evidenceFileKey: values.evidenceFileKey.trim() || undefined,
  }
}

export type FineSettingsFormValues = {
  autoFinesEnabled: boolean
  fineMode: FineCalculationMode
  baseFineAmount: string
  dailyIncrement: string
  graceDays: string
}

export const fineSettingsDefaults: FineSettingsFormValues = {
  autoFinesEnabled: true,
  fineMode: 'FIXED',
  baseFineAmount: '0',
  dailyIncrement: '0',
  graceDays: '0',
}

export const fineSettingsSchema: yup.ObjectSchema<FineSettingsFormValues> = yup.object({
  autoFinesEnabled: yup.boolean().required(),
  fineMode: yup.mixed<FineCalculationMode>().oneOf(['FIXED', 'PROGRESSIVE']).required(),
  baseFineAmount: yup
    .string()
    .trim()
    .required('Base amount is required')
    .matches(/^\d+(\.\d{1,4})?$/, 'Enter a valid amount')
    .test('nonneg', 'Amount must be 0 or greater', (v) => Boolean(v) && Number(v) >= 0),
  dailyIncrement: yup
    .string()
    .trim()
    .required('Daily increment is required')
    .matches(/^\d+(\.\d{1,4})?$/, 'Enter a valid amount')
    .test('nonneg', 'Amount must be 0 or greater', (v) => Boolean(v) && Number(v) >= 0),
  graceDays: yup
    .string()
    .trim()
    .required('Grace days is required')
    .test('days', 'Enter a valid number of days (0–365)', (v) => {
      if (!v) return false
      const n = Number(v)
      return Number.isInteger(n) && n >= 0 && n <= 365
    }),
})

export function toFineSettingsPayload(
  values: FineSettingsFormValues,
): FineSettingsUpdateRequest {
  return {
    autoFinesEnabled: values.autoFinesEnabled,
    fineMode: values.fineMode,
    baseFineAmount: values.baseFineAmount.trim(),
    dailyIncrement: values.dailyIncrement.trim(),
    graceDays: Number(values.graceDays.trim()),
  }
}

export type FineGenerateFormValues = {
  year: string
  month: string
}

export const fineGenerateDefaults = (): FineGenerateFormValues => {
  const now = new Date()
  return {
    year: String(now.getFullYear()),
    month: String(now.getMonth() + 1),
  }
}
