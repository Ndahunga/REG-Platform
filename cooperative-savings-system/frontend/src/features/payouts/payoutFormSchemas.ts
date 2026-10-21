import * as yup from 'yup'
import type { PayoutPreviewRequest } from '@/shared/types/payout'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { moneyField } from '@/shared/utils/yupRules'

/** Backend: payoutPoolAmount @DecimalMin("0.01") when given; blank means "use the available fund". */
const optionalMoney = moneyField({ allowEmpty: true, label: 'Payout pool' })

export type PayoutPreviewFormValues = {
  name: string
  periodFrom: string
  periodTo: string
  includeRegular: boolean
  includeSpecial: boolean
  payoutPoolAmount: string
  notes: string
}

export const payoutPreviewDefaults = (): PayoutPreviewFormValues => {
  const todayStr = todayInKigaliIso()
  const yearStart = `${todayStr.slice(0, 4)}-01-01`
  return {
    name: '',
    periodFrom: yearStart,
    periodTo: todayStr,
    includeRegular: true,
    includeSpecial: true,
    payoutPoolAmount: '',
    notes: '',
  }
}

export const payoutPreviewSchema: yup.ObjectSchema<PayoutPreviewFormValues> = yup
  .object({
    name: yup.string().trim().max(200).default(''),
    periodFrom: yup.string().trim().required('Period start date is required'),
    periodTo: yup.string().trim().required('Period end date is required'),
    includeRegular: yup.boolean().required().default(true),
    includeSpecial: yup.boolean().required().default(true),
    payoutPoolAmount: optionalMoney,
    notes: yup.string().trim().max(2000).default(''),
  })
  .test('period-order', 'End date must be on or after start date', function (value) {
    if (!value?.periodFrom || !value?.periodTo) return true
    return value.periodTo >= value.periodFrom
  })
  .test(
    'include-at-least-one',
    'Select regular and/or special contributions',
    function (value) {
      return Boolean(value?.includeRegular || value?.includeSpecial)
    },
  )

export function toPayoutPreviewPayload(
  values: PayoutPreviewFormValues,
): PayoutPreviewRequest {
  const pool = values.payoutPoolAmount.trim()
  return {
    periodFrom: values.periodFrom.trim(),
    periodTo: values.periodTo.trim(),
    includeRegular: values.includeRegular,
    includeSpecial: values.includeSpecial,
    payoutPoolAmount: pool || undefined,
    name: values.name.trim() || undefined,
    notes: values.notes.trim() || undefined,
  }
}
