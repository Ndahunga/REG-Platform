import * as yup from 'yup'
import type { CooperativeCreateRequest, SchemeOperatingModel } from '@/shared/types/cooperative'
import { isOperatingModel, OPERATING_MODEL_REQUIRED_MESSAGE } from './operatingModel'
import { checkMoney } from '@/shared/utils/formValidation'
import { findPresidentProblem } from './presidentValidation'
import {
  isValidCooperativeEmail,
  isValidRegistrationDate,
  isValidRegistrationNumber,
  isValidRwandanPhone,
  MAX_CONTRIBUTION_DUE_DAY,
  MIN_CONTRIBUTION_DUE_DAY,
  MIN_REGISTRATION_DATE,
  normalizeRegistrationNumber,
  normalizeRwandanPhone,
  RWANDA_CURRENCY,
  todayInKigaliIso,
} from '@/shared/utils/rwandaCooperative'

const OPERATING_MODELS_WITH_EMPTY: (SchemeOperatingModel | '')[] = ['', 'ACCUMULATING', 'ROTATING', 'HYBRID']

export type PresidentAssignmentMode = 'new' | 'existing'

export type CooperativeFormValues = {
  name: string
  description: string
  registrationNumber: string
  contactEmail: string
  contactPhone: string
  address: string
  currency: string
  financialYearStartMonth: number
  monthlyContributionAmount: string
  contributionDueDay: number
  registrationDate: string
  /** '' = nothing selected yet; there is no default model. Only used when creating. */
  operatingModel: SchemeOperatingModel | ''
  startTrial: boolean
  assignPresidentNow: boolean
  presidentMode: PresidentAssignmentMode
  presidentUserId: string
  presidentUsername: string
  presidentEmail: string
  presidentFirstName: string
  presidentLastName: string
  presidentPhone: string
  presidentTemporaryPassword: string
}

export const cooperativeFormDefaults: CooperativeFormValues = {
  name: '',
  description: '',
  registrationNumber: '',
  contactEmail: '',
  contactPhone: '',
  address: '',
  currency: RWANDA_CURRENCY,
  financialYearStartMonth: 1,
  monthlyContributionAmount: '0',
  contributionDueDay: 1,
  registrationDate: '',
  operatingModel: '',
  startTrial: true,
  assignPresidentNow: false,
  presidentMode: 'new',
  presidentUserId: '',
  presidentUsername: '',
  presidentEmail: '',
  presidentFirstName: '',
  presidentLastName: '',
  presidentPhone: '',
  presidentTemporaryPassword: '',
}

export const cooperativeFormSchema: yup.ObjectSchema<CooperativeFormValues> = yup.object({
  name: yup.string().trim().required('Name is required').max(255, 'Name must be 255 characters or fewer'),
  description: yup.string().trim().max(2000, 'Description must be 2000 characters or fewer').default(''),
  registrationNumber: yup
    .string()
    .trim()
    .required('Registration number is required')
    .test(
      'registration',
      'Use a valid registration number (4–32 characters, letters/digits with / or -)',
      (v) => Boolean(v && isValidRegistrationNumber(v)),
    ),
  contactEmail: yup
    .string()
    .trim()
    .required('Contact email is required')
    .test('email', 'Enter a valid email address', (v) => Boolean(v && isValidCooperativeEmail(v))),
  contactPhone: yup
    .string()
    .trim()
    .required('Contact phone is required')
    .test(
      'phone',
      'Enter a Rwandan mobile number (10 digits starting with 07)',
      (v) => Boolean(v && isValidRwandanPhone(v)),
    ),
  address: yup.string().trim().max(512, 'Address must be 512 characters or fewer').default(''),
  currency: yup
    .string()
    .trim()
    .required('Currency is required')
    .oneOf([RWANDA_CURRENCY], 'Currency must be RWF'),
  financialYearStartMonth: yup
    .number()
    .required()
    .min(1, 'Month must be 1–12')
    .max(12, 'Month must be 1–12'),
  monthlyContributionAmount: yup
    .string()
    .trim()
    .required('Monthly contribution is required')
    // Backend: @DecimalMin(0.0) stored as NUMERIC(19,4): at most 15 whole digits and 4 decimals.
    .test('amount', 'Enter a valid amount (up to 15 digits and 4 decimals)', (v) =>
      checkMoney(v, { allowZero: true }) === null,
    ),
  contributionDueDay: yup
    .number()
    .required('Contribution due day is required')
    .min(MIN_CONTRIBUTION_DUE_DAY, 'Due day must be 1–28')
    .max(MAX_CONTRIBUTION_DUE_DAY, 'Due day must be 1–28'),
  registrationDate: yup
    .string()
    .trim()
    .required('Registration date is required')
    .test(
      'registrationDate',
      `Date must be between ${MIN_REGISTRATION_DATE} and today`,
      (v) => Boolean(v && isValidRegistrationDate(v)),
    ),
  operatingModel: yup
    .mixed<SchemeOperatingModel | ''>()
    .oneOf([...OPERATING_MODELS_WITH_EMPTY], OPERATING_MODEL_REQUIRED_MESSAGE)
    .default(''),
  startTrial: yup.boolean().required(),
  assignPresidentNow: yup.boolean().required(),
  presidentMode: yup.mixed<PresidentAssignmentMode>().oneOf(['new', 'existing']).required(),
  presidentUserId: yup.string().trim().default(''),
  presidentUsername: yup.string().trim().default(''),
  presidentEmail: yup.string().trim().default(''),
  presidentFirstName: yup.string().trim().default(''),
  presidentLastName: yup.string().trim().default(''),
  presidentPhone: yup.string().trim().default(''),
  presidentTemporaryPassword: yup.string().default(''),
}).test('president-required-when-assigning', function (values) {
  if (!values.assignPresidentNow) return true
  const problem = findPresidentProblem({
    mode: values.presidentMode,
    userId: values.presidentUserId ?? '',
    username: values.presidentUsername ?? '',
    email: values.presidentEmail ?? '',
    firstName: values.presidentFirstName ?? '',
    lastName: values.presidentLastName ?? '',
    phone: values.presidentPhone ?? '',
    temporaryPassword: values.presidentTemporaryPassword ?? '',
  })
  if (!problem) return true
  const path = {
    userId: 'presidentUserId',
    username: 'presidentUsername',
    email: 'presidentEmail',
    firstName: 'presidentFirstName',
    lastName: 'presidentLastName',
    phone: 'presidentPhone',
    temporaryPassword: 'presidentTemporaryPassword',
  }[problem.field]
  return this.createError({ path, message: problem.message })
})

/** The create form additionally requires a chosen operating model; editing never touches it. */
export const cooperativeCreateSchema: yup.ObjectSchema<CooperativeFormValues> = cooperativeFormSchema.shape({
  operatingModel: yup
    .mixed<SchemeOperatingModel | ''>()
    .test('operating-model-required', OPERATING_MODEL_REQUIRED_MESSAGE, (v) => isOperatingModel(v))
    .default(''),
}) as unknown as yup.ObjectSchema<CooperativeFormValues>

export function toCooperativePayload(
  values: CooperativeFormValues,
  mode: 'create' | 'edit' = 'create',
): CooperativeCreateRequest {
  const payload: CooperativeCreateRequest = {
    name: values.name.trim(),
    description: values.description.trim() || undefined,
    registrationNumber: normalizeRegistrationNumber(values.registrationNumber),
    contactEmail: values.contactEmail.trim().toLowerCase(),
    contactPhone: normalizeRwandanPhone(values.contactPhone),
    address: values.address.trim() || undefined,
    currency: RWANDA_CURRENCY,
    financialYearStartMonth: Number(values.financialYearStartMonth),
    monthlyContributionAmount: values.monthlyContributionAmount.trim(),
    contributionDueDay: Number(values.contributionDueDay),
    registrationDate: values.registrationDate.trim(),
  } as CooperativeCreateRequest
  if (mode !== 'create') {
    return payload
  }
  payload.operatingModel = values.operatingModel as SchemeOperatingModel
  payload.subscriptionInitialization = values.startTrial ? 'START_TRIAL' : 'NONE'
  if (values.assignPresidentNow) {
    payload.president =
      values.presidentMode === 'existing'
        ? { userId: values.presidentUserId.trim() }
        : {
            username: values.presidentUsername.trim(),
            email: values.presidentEmail.trim().toLowerCase(),
            firstName: values.presidentFirstName.trim(),
            lastName: values.presidentLastName.trim(),
            phone: values.presidentPhone.trim() || undefined,
            temporaryPassword: values.presidentTemporaryPassword || undefined,
          }
  }
  return payload
}

export { todayInKigaliIso }
