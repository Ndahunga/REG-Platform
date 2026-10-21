import * as yup from 'yup'
import type {
  InvestmentCreateRequest,
  InvestmentLossRequest,
  InvestmentReturnCreateRequest,
} from '@/shared/types/investment'
import { isValidIsoDate } from '@/shared/utils/formValidation'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { dateField, moneyField } from '@/shared/utils/yupRules'

/** Backend: @DecimalMin("0.01"), at most 15 whole digits and 4 decimals. */
const positiveMoney = moneyField({ label: 'Amount' })

/** Backend: portions and expected return @DecimalMin(0); blank means none. */
const optionalMoney = (label: string) => moneyField({ allowEmpty: true, allowZero: true, label })

export type InvestmentCreateFormValues = {
  name: string
  amount: string
  expectedReturnAmount: string
  expectedReturnDate: string
  description: string
  documentFileKey: string
}

export const investmentCreateDefaults: InvestmentCreateFormValues = {
  name: '',
  amount: '',
  expectedReturnAmount: '',
  expectedReturnDate: '',
  description: '',
  documentFileKey: '',
}

export const investmentCreateSchema: yup.ObjectSchema<InvestmentCreateFormValues> =
  yup.object({
    name: yup.string().trim().required('Name is required').max(200),
    amount: positiveMoney,
    expectedReturnAmount: optionalMoney('Expected return amount'),
    // Backend: @FutureOrPresent.
    expectedReturnDate: yup
      .string()
      .trim()
      .default('')
      .test('expected-date', 'Enter a valid expected return date', (v) => !v || isValidIsoDate(v))
      .test(
        'expected-not-past',
        'Expected return date cannot be in the past',
        (v) => !v || !isValidIsoDate(v) || v >= todayInKigaliIso(),
      ),
    description: yup.string().trim().max(2000).default(''),
    documentFileKey: yup.string().trim().max(512).default(''),
  })

export function toInvestmentCreatePayload(
  values: InvestmentCreateFormValues,
): InvestmentCreateRequest {
  return {
    name: values.name.trim(),
    amount: values.amount.trim(),
    expectedReturnAmount: values.expectedReturnAmount.trim() || undefined,
    expectedReturnDate: values.expectedReturnDate.trim() || undefined,
    description: values.description.trim() || undefined,
    documentFileKey: values.documentFileKey.trim() || undefined,
  }
}

export type InvestmentReturnFormValues = {
  returnDate: string
  capitalPortion: string
  profitPortion: string
  notes: string
  reference: string
}

export const investmentReturnDefaults = (): InvestmentReturnFormValues => ({
  returnDate: todayInKigaliIso(),
  capitalPortion: '',
  profitPortion: '',
  notes: '',
  reference: '',
})

export const investmentReturnSchema: yup.ObjectSchema<InvestmentReturnFormValues> =
  yup.object({
    returnDate: dateField({ label: 'Return date' }),
    capitalPortion: optionalMoney('Capital portion'),
    profitPortion: optionalMoney('Profit portion'),
    notes: yup.string().trim().max(2000).default(''),
    reference: yup.string().trim().max(128).default(''),
  }).test(
    'at-least-one-portion',
    'Enter a capital and/or profit portion greater than 0',
    function (value) {
      const capital = Number(value?.capitalPortion) || 0
      const profit = Number(value?.profitPortion) || 0
      if (capital > 0 || profit > 0) return true
      // Attach to a field so the message is displayed (an object-level error has no input to show it on).
      return this.createError({
        path: 'capitalPortion',
        message: 'Enter a capital and/or profit portion greater than 0',
      })
    },
  )

export function toInvestmentReturnPayload(
  values: InvestmentReturnFormValues,
): InvestmentReturnCreateRequest {
  const capital = values.capitalPortion.trim()
  const profit = values.profitPortion.trim()
  return {
    returnDate: values.returnDate.trim(),
    capitalPortion: capital && Number(capital) > 0 ? capital : undefined,
    profitPortion: profit && Number(profit) > 0 ? profit : undefined,
    notes: values.notes.trim() || undefined,
    reference: values.reference.trim() || undefined,
  }
}

export type InvestmentLossFormValues = {
  notes: string
  reference: string
}

export const investmentLossDefaults: InvestmentLossFormValues = {
  notes: '',
  reference: '',
}

export const investmentLossSchema: yup.ObjectSchema<InvestmentLossFormValues> = yup.object({
  notes: yup.string().trim().max(2000).default(''),
  reference: yup.string().trim().max(128).default(''),
})

export function toInvestmentLossPayload(
  values: InvestmentLossFormValues,
): InvestmentLossRequest {
  return {
    notes: values.notes.trim() || undefined,
    reference: values.reference.trim() || undefined,
  }
}
