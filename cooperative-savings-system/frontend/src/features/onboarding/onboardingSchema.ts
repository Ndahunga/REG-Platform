import * as yup from 'yup'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import { isOperatingModel, OPERATING_MODEL_REQUIRED_MESSAGE } from '@/features/cooperatives/operatingModel'
import {
  isValidCooperativeEmail,
  isValidRegistrationDate,
  isValidRwandanPhone,
  MAX_CONTRIBUTION_DUE_DAY,
  MIN_CONTRIBUTION_DUE_DAY,
  MIN_REGISTRATION_DATE,
  normalizeRwandanPhone,
  RWANDA_CURRENCY,
  todayInKigaliIso,
} from '@/shared/utils/rwandaCooperative'

export type OnboardingFormValues = {
  name: string
  contactEmail: string
  contactPhone: string
  address: string
  financialYearStartMonth: number
  monthlyContributionAmount: string
  contributionDueDay: number
  registrationDate: string
  /** '' = nothing selected yet; there is no default model. */
  operatingModel: SchemeOperatingModel | ''
  firstName: string
  lastName: string
  username: string
  email: string
  phone: string
  password: string
  confirmPassword: string
}

export const onboardingDefaults: OnboardingFormValues = {
  name: '',
  contactEmail: '',
  contactPhone: '',
  address: '',
  financialYearStartMonth: 1,
  monthlyContributionAmount: '0',
  contributionDueDay: 1,
  registrationDate: '',
  operatingModel: '',
  firstName: '',
  lastName: '',
  username: '',
  email: '',
  phone: '',
  password: '',
  confirmPassword: '',
}

export const ONBOARDING_STEP1_FIELDS: (keyof OnboardingFormValues)[] = [
  'name',
  'contactEmail',
  'contactPhone',
  'address',
  'financialYearStartMonth',
  'monthlyContributionAmount',
  'contributionDueDay',
  'registrationDate',
  'operatingModel',
]

export const ONBOARDING_STEP2_FIELDS: (keyof OnboardingFormValues)[] = [
  'firstName',
  'lastName',
  'username',
  'email',
  'phone',
  'password',
  'confirmPassword',
]

export const onboardingSchema: yup.ObjectSchema<OnboardingFormValues> = yup.object({
  name: yup.string().trim().required('Name is required').max(255),
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
  address: yup.string().trim().max(512).default(''),
  financialYearStartMonth: yup.number().required().min(1).max(12),
  monthlyContributionAmount: yup
    .string()
    .trim()
    .required('Monthly contribution is required')
    .matches(/^\d+(\.\d{1,4})?$/, 'Enter a valid amount'),
  contributionDueDay: yup
    .number()
    .required('Contribution due day is required')
    .min(MIN_CONTRIBUTION_DUE_DAY)
    .max(MAX_CONTRIBUTION_DUE_DAY),
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
    .test('operating-model-required', OPERATING_MODEL_REQUIRED_MESSAGE, (v) => isOperatingModel(v))
    .default(''),
  firstName: yup.string().trim().required('First name is required').max(128),
  lastName: yup.string().trim().required('Last name is required').max(128),
  username: yup.string().trim().min(3, 'At least 3 characters').max(64).required('Username is required'),
  email: yup
    .string()
    .trim()
    .required('Email is required')
    .test('creator-email', 'Enter a valid email address', (v) => Boolean(v && isValidCooperativeEmail(v))),
  phone: yup
    .string()
    .trim()
    .default('')
    .test('optional-phone', 'Enter a Rwandan mobile number (10 digits starting with 07)', (v) => {
      if (!v) return true
      return isValidRwandanPhone(v)
    }),
  password: yup.string().min(8, 'At least 8 characters').max(128).required('Password is required'),
  confirmPassword: yup
    .string()
    .oneOf([yup.ref('password')], 'Passwords must match')
    .required('Confirm your password'),
})

export interface PublicOnboardingPayload {
  cooperative: {
    name: string
    contactEmail: string
    contactPhone: string
    address?: string
    currency: string
    financialYearStartMonth: number
    monthlyContributionAmount: string
    contributionDueDay: number
    registrationDate: string
    operatingModel: SchemeOperatingModel
  }
  creator: {
    firstName: string
    lastName: string
    username: string
    email: string
    phone?: string
    password: string
  }
}

export function toOnboardingPayload(values: OnboardingFormValues): PublicOnboardingPayload {
  return {
    cooperative: {
      name: values.name.trim(),
      contactEmail: values.contactEmail.trim().toLowerCase(),
      contactPhone: normalizeRwandanPhone(values.contactPhone),
      address: values.address.trim() || undefined,
      currency: RWANDA_CURRENCY,
      financialYearStartMonth: Number(values.financialYearStartMonth),
      monthlyContributionAmount: values.monthlyContributionAmount.trim(),
      contributionDueDay: Number(values.contributionDueDay),
      registrationDate: values.registrationDate.trim(),
      operatingModel: values.operatingModel as SchemeOperatingModel,
    },
    creator: {
      firstName: values.firstName.trim(),
      lastName: values.lastName.trim(),
      username: values.username.trim(),
      email: values.email.trim().toLowerCase(),
      phone: values.phone.trim() ? normalizeRwandanPhone(values.phone) : undefined,
      password: values.password,
    },
  }
}

export { todayInKigaliIso, MIN_REGISTRATION_DATE, MAX_CONTRIBUTION_DUE_DAY, MIN_CONTRIBUTION_DUE_DAY, RWANDA_CURRENCY }
