import * as yup from 'yup'
import type {
  IncomeExpenseCategory,
  IncomeExpenseCreateRequest,
  LedgerEffect,
} from '@/shared/types/incomeExpense'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { dateField, moneyField } from '@/shared/utils/yupRules'

/** Backend: @DecimalMin("0.01"), at most 15 whole digits and 4 decimals. */
const positiveMoney = moneyField({ label: 'Amount' })

export type TransactionCreateFormValues = {
  category: IncomeExpenseCategory | ''
  amount: string
  transactionDate: string
  reference: string
  description: string
  notes: string
  ledgerEffect: LedgerEffect | ''
  supportingFileKey: string
}

export const transactionCreateDefaults = (): TransactionCreateFormValues => ({
  category: '',
  amount: '',
  transactionDate: todayInKigaliIso(),
  reference: '',
  description: '',
  notes: '',
  ledgerEffect: '',
  supportingFileKey: '',
})

export const transactionCreateSchema: yup.ObjectSchema<TransactionCreateFormValues> =
  yup.object({
    category: yup
      .mixed<IncomeExpenseCategory | ''>()
      .oneOf(
        ['', 'OTHER_INCOME', 'GENERAL_EXPENSE', 'INTEREST_EXPENSE', 'ADJUSTMENT'],
        'Select a category',
      )
      .required('Select a category')
      .test('required-category', 'Select a category', (v) => Boolean(v)),
    amount: positiveMoney,
    // Backend: @NotNull @PastOrPresent.
    transactionDate: dateField({ label: 'Transaction date' }),
    reference: yup.string().trim().max(128).default(''),
    description: yup.string().trim().max(2000).default(''),
    notes: yup.string().trim().max(2000).default(''),
    ledgerEffect: yup
      .mixed<LedgerEffect | ''>()
      .oneOf(['', 'CREDIT', 'DEBIT'])
      .default('')
      .when('category', {
        is: 'ADJUSTMENT',
        // The backend requires a credit/debit effect for adjustments. An empty string is not "missing" to
        // yup's required(), so check the value explicitly.
        then: (schema) =>
          schema.test('adjustment-effect', 'Select credit or debit', (value) => value === 'CREDIT' || value === 'DEBIT'),
        otherwise: (schema) => schema.notRequired(),
      }),
    supportingFileKey: yup.string().trim().max(512).default(''),
  })

export function toTransactionCreatePayload(
  values: TransactionCreateFormValues,
): IncomeExpenseCreateRequest {
  const payload: IncomeExpenseCreateRequest = {
    category: values.category,
    amount: values.amount.trim(),
    transactionDate: values.transactionDate.trim(),
    reference: values.reference.trim() || undefined,
    description: values.description.trim() || undefined,
    notes: values.notes.trim() || undefined,
    supportingFileKey: values.supportingFileKey.trim() || undefined,
  }
  if (values.category === 'ADJUSTMENT' && values.ledgerEffect) {
    payload.ledgerEffect = values.ledgerEffect
  }
  return payload
}

export type TransactionRejectFormValues = {
  rejectionReason: string
}

export const transactionRejectDefaults: TransactionRejectFormValues = {
  rejectionReason: '',
}

export const transactionRejectSchema: yup.ObjectSchema<TransactionRejectFormValues> =
  yup.object({
    rejectionReason: yup
      .string()
      .trim()
      .required('Rejection reason is required')
      .max(2000),
  })
