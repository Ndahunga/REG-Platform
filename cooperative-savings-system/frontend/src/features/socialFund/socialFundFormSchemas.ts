import * as yup from 'yup'
import type {
  SocialContributionCreateRequest,
  SocialDisbursementCreateRequest,
  SocialFundSettingsUpdateRequest,
} from '@/shared/types/socialFund'
import { dateField, moneyField } from '@/shared/utils/yupRules'

/** Backend: @DecimalMin("0.01"), at most 15 whole digits and 4 decimals. */
const positiveMoney = moneyField({ label: 'Amount' })

export type SocialContributionFormValues = {
  amount: string
  contributionDate: string
  paymentReference: string
  notes: string
  memberUserId: string
  evidenceFileKey: string
}

export const socialContributionDefaults: SocialContributionFormValues = {
  amount: '',
  contributionDate: '',
  paymentReference: '',
  notes: '',
  memberUserId: '',
  evidenceFileKey: '',
}

export const socialContributionSchema: yup.ObjectSchema<SocialContributionFormValues> =
  yup.object({
    amount: positiveMoney,
    contributionDate: dateField({ label: 'Contribution date' }),
    paymentReference: yup.string().trim().max(128).default(''),
    notes: yup.string().trim().max(2000).default(''),
    memberUserId: yup.string().trim().default(''),
    evidenceFileKey: yup.string().trim().max(512).default(''),
  })

export function toSocialContributionPayload(
  values: SocialContributionFormValues,
  options?: { includeMember?: boolean },
): SocialContributionCreateRequest {
  const payload: SocialContributionCreateRequest = {
    amount: values.amount.trim(),
    contributionDate: values.contributionDate.trim() || undefined,
    paymentReference: values.paymentReference.trim() || undefined,
    notes: values.notes.trim() || undefined,
    evidenceFileKey: values.evidenceFileKey.trim() || undefined,
  }
  if (options?.includeMember && values.memberUserId.trim()) {
    payload.memberUserId = values.memberUserId.trim()
  }
  return payload
}

export type SocialDisbursementFormValues = {
  beneficiaryMemberUserId: string
  amount: string
  reason: string
  disbursementDate: string
  notes: string
  evidenceFileKey: string
}

export const socialDisbursementDefaults: SocialDisbursementFormValues = {
  beneficiaryMemberUserId: '',
  amount: '',
  reason: '',
  disbursementDate: '',
  notes: '',
  evidenceFileKey: '',
}

export const socialDisbursementSchema: yup.ObjectSchema<SocialDisbursementFormValues> =
  yup.object({
    beneficiaryMemberUserId: yup.string().trim().required('Select a beneficiary'),
    amount: positiveMoney,
    reason: yup.string().trim().required('Reason is required').max(2000),
    disbursementDate: dateField({ label: 'Disbursement date' }),
    notes: yup.string().trim().max(2000).default(''),
    evidenceFileKey: yup.string().trim().max(512).default(''),
  })

export function toSocialDisbursementPayload(
  values: SocialDisbursementFormValues,
): SocialDisbursementCreateRequest {
  return {
    beneficiaryMemberUserId: values.beneficiaryMemberUserId.trim(),
    amount: values.amount.trim(),
    reason: values.reason.trim(),
    disbursementDate: values.disbursementDate.trim() || undefined,
    notes: values.notes.trim() || undefined,
    evidenceFileKey: values.evidenceFileKey.trim() || undefined,
  }
}

export type SocialFundSettingsFormValues = {
  suggestedContributionAmount: string
  enabled: boolean
}

export const socialFundSettingsDefaults: SocialFundSettingsFormValues = {
  suggestedContributionAmount: '',
  enabled: true,
}

export const socialFundSettingsSchema: yup.ObjectSchema<SocialFundSettingsFormValues> =
  yup.object({
    // Backend: @NotNull @DecimalMin(0.0). Blank is not silently sent as null; enter 0 for "no suggestion".
    suggestedContributionAmount: moneyField({ allowZero: true, label: 'Suggested amount' }),
    enabled: yup.boolean().required(),
  })

export function toSocialFundSettingsPayload(
  values: SocialFundSettingsFormValues,
): SocialFundSettingsUpdateRequest {
  return {
    suggestedContributionAmount: values.suggestedContributionAmount.trim() || null,
    enabled: values.enabled,
  }
}

export type SocialFundReportFormValues = {
  from: string
  to: string
}

export const socialFundReportDefaults = (): SocialFundReportFormValues => {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  return {
    from: `${now.getFullYear()}-01-01`,
    to: today,
  }
}

export const socialFundReportSchema: yup.ObjectSchema<SocialFundReportFormValues> = yup.object({
  from: yup
    .string()
    .trim()
    .required('Start date is required')
    .test('not-future', 'Start date cannot be in the future', (value) => {
      if (!value) return true
      const now = new Date()
      const pad = (n: number) => String(n).padStart(2, '0')
      const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
      return value <= today
    }),
  to: yup
    .string()
    .trim()
    .required('End date is required')
    .test('range', 'End date must be on or after start date', function (value) {
      const { from } = this.parent as SocialFundReportFormValues
      if (!from || !value) return true
      return value >= from
    })
    .test('not-future', 'End date cannot be in the future', (value) => {
      if (!value) return true
      const now = new Date()
      const pad = (n: number) => String(n).padStart(2, '0')
      const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
      return value <= today
    }),
})
