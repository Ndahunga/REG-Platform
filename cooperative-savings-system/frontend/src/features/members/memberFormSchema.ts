import * as yup from 'yup'
import type {
  MemberCreateRequest,
  MemberUpdateRequest,
  RoleInCooperative,
} from '@/shared/types/member'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

export type MemberFormValues = {
  firstName: string
  lastName: string
  username: string
  email: string
  phone: string
  nationalId: string
  address: string
  membershipDate: string
  temporaryPassword: string
  roleInCooperative: RoleInCooperative
  shareCount: string
}

const todayIso = () => todayInKigaliIso()

// Backend: email @Email, max 255.
const emailSchema = yup
  .string()
  .trim()
  .required('Email is required')
  .max(255, 'Email must be 255 characters or fewer')
  .email('Enter a valid email')

const nationalIdSchema = yup
  .string()
  .trim()
  .default('')
  .test(
    'rwandan-nid',
    'National ID must be exactly 16 digits (numbers only)',
    (value) => !value || /^\d{16}$/.test(value),
  )

const membershipDateSchema = yup
  .string()
  .trim()
  .default('')
  .test('not-future', 'Membership date cannot be in the future', (value) => {
    if (!value) return true
    return value <= todayIso()
  })
  .test('not-too-old', 'Membership date cannot be before 1950-01-01', (value) => {
    if (!value) return true
    return value >= '1950-01-01'
  })

const shareCountSchema = yup
  .string()
  .trim()
  .required()
  .test('shares', 'Share count must be between 0 and 1000', (value) => {
    const n = Number(value)
    return Number.isInteger(n) && n >= 0 && n <= 1000
  })

export const memberFormDefaults: MemberFormValues = {
  firstName: '',
  lastName: '',
  username: '',
  email: '',
  phone: '',
  nationalId: '',
  address: '',
  membershipDate: '',
  temporaryPassword: '',
  roleInCooperative: 'MEMBER',
  shareCount: '0',
}

export const memberCreateSchema: yup.ObjectSchema<MemberFormValues> = yup.object({
  firstName: yup.string().trim().required('First name is required').max(128),
  lastName: yup.string().trim().required('Last name is required').max(128),
  username: yup
    .string()
    .trim()
    .required('Username is required')
    .min(3)
    .max(64)
    .matches(/^[a-zA-Z0-9._-]+$/, 'Use letters, numbers, . _ - only'),
  email: emailSchema,
  phone: yup.string().trim().max(32, 'Phone must be 32 characters or fewer').default(''),
  nationalId: nationalIdSchema,
  address: yup.string().trim().max(512, 'Address must be 512 characters or fewer').default(''),
  membershipDate: membershipDateSchema,
  temporaryPassword: yup
    .string()
    .default('')
    .test('pwd', 'At least 8 characters if provided', (v) => !v || v.length >= 8)
    .test('pwd-max', 'At most 128 characters', (v) => !v || v.length <= 128),
  roleInCooperative: yup
    .mixed<RoleInCooperative>()
    .oneOf(['MEMBER', 'PRESIDENT', 'VICE_PRESIDENT', 'SECRETARY', 'ACCOUNTANT', 'LOAN_OFFICER'])
    .required(),
  shareCount: shareCountSchema,
})

export const memberUpdateSchema = yup.object({
  firstName: yup.string().trim().required('First name is required').max(128),
  lastName: yup.string().trim().required('Last name is required').max(128),
  username: yup
    .string()
    .trim()
    .required('Username is required')
    .min(3)
    .max(64)
    .matches(/^[a-zA-Z0-9._-]+$/, 'Use letters, numbers, . _ - only'),
  email: emailSchema,
  phone: yup.string().trim().max(32, 'Phone must be 32 characters or fewer').default(''),
  nationalId: nationalIdSchema,
  address: yup.string().trim().max(512, 'Address must be 512 characters or fewer').default(''),
  membershipDate: membershipDateSchema,
  roleInCooperative: yup
    .mixed<RoleInCooperative>()
    .oneOf(['MEMBER', 'PRESIDENT', 'VICE_PRESIDENT', 'SECRETARY', 'ACCOUNTANT', 'LOAN_OFFICER'])
    .required(),
  // Read-only while editing (the backend ignores it), so it must never block a save.
  shareCount: yup.string().default('0'),
})

export type MemberUpdateFormValues = Omit<MemberFormValues, 'temporaryPassword' | 'shareCount'>

export function toMemberCreatePayload(values: MemberFormValues): MemberCreateRequest {
  return {
    firstName: values.firstName.trim(),
    lastName: values.lastName.trim(),
    username: values.username.trim(),
    email: values.email.trim(),
    phone: values.phone.trim() || undefined,
    nationalId: values.nationalId.trim() || undefined,
    address: values.address.trim() || undefined,
    membershipDate: values.membershipDate.trim() || undefined,
    temporaryPassword: values.temporaryPassword.trim() || undefined,
    roleInCooperative: values.roleInCooperative,
  }
}

export function toMemberUpdatePayload(
  values: MemberUpdateFormValues,
  originalRole?: RoleInCooperative,
): MemberUpdateRequest {
  return {
    firstName: values.firstName.trim(),
    lastName: values.lastName.trim(),
    username: values.username.trim(),
    email: values.email.trim(),
    phone: values.phone.trim() || undefined,
    nationalId: values.nationalId.trim() || undefined,
    address: values.address.trim() || undefined,
    membershipDate: values.membershipDate.trim() || undefined,
    roleInCooperative:
      originalRole && originalRole === values.roleInCooperative ? undefined : values.roleInCooperative,
  }
}
