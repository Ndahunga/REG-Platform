import type { TFunction } from 'i18next'
import * as yup from 'yup'
import type { ContactPayload } from '@/shared/api/contact'
import { isValidRwandanPhone, normalizeRwandanPhone } from '@/shared/utils/rwandaCooperative'

/**
 * Only Rwanda is offered for now: OuWealth support is a Rwandan service and we do not want to invent an
 * international dialling list. The value is still a real field so more countries can be added later.
 */
export const COUNTRY_CODES = [{ iso: 'RW', dial: '+250', label: 'RW (+250)' }] as const

export interface ContactFormValues {
  firstName: string
  lastName: string
  email: string
  countryCode: string
  phone: string
  message: string
}

export const CONTACT_LIMITS = { name: 80, email: 255, phone: 20, message: 1000 } as const

export const contactDefaults: ContactFormValues = {
  firstName: '',
  lastName: '',
  email: '',
  countryCode: COUNTRY_CODES[0].dial,
  phone: '',
  message: '',
}

export function buildContactSchema(t: TFunction): yup.ObjectSchema<ContactFormValues> {
  const tooLong = t('public.contact.validation.tooLong')
  return yup.object({
    firstName: yup
      .string()
      .trim()
      .required(t('public.contact.validation.firstNameRequired'))
      .max(CONTACT_LIMITS.name, tooLong),
    lastName: yup
      .string()
      .trim()
      .required(t('public.contact.validation.lastNameRequired'))
      .max(CONTACT_LIMITS.name, tooLong),
    email: yup
      .string()
      .trim()
      .required(t('public.contact.validation.emailRequired'))
      .max(CONTACT_LIMITS.email, tooLong)
      .email(t('public.contact.validation.emailInvalid')),
    countryCode: yup
      .string()
      .trim()
      .required(t('public.contact.validation.countryRequired'))
      .oneOf(
        COUNTRY_CODES.map((c) => c.dial as string),
        t('public.contact.validation.countryRequired'),
      ),
    phone: yup
      .string()
      .trim()
      .default('')
      .max(CONTACT_LIMITS.phone, tooLong)
      .test('rwanda-phone', t('public.contact.validation.phoneInvalid'), (value) => !value || isValidRwandanPhone(value)),
    message: yup
      .string()
      .trim()
      .required(t('public.contact.validation.messageRequired'))
      .max(CONTACT_LIMITS.message, tooLong),
  })
}

/**
 * The request body for POST /public/contact. The phone is sent as plain national digits (07XXXXXXXX), which is what the
 * backend accepts however the visitor typed it (spaces, dots, +250). There is deliberately no recipient field.
 */
export function toContactPayload(values: ContactFormValues): ContactPayload {
  const phone = values.phone.trim()
  return {
    firstName: values.firstName.trim(),
    lastName: values.lastName.trim(),
    email: values.email.trim(),
    countryCode: values.countryCode.trim(),
    ...(phone ? { phoneNumber: normalizeRwandanPhone(phone) } : {}),
    message: values.message.trim(),
  }
}
