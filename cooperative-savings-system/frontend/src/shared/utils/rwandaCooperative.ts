/**
 * Rwanda contact and cooperative registration helpers used by the create/edit form.
 */

const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/
const RWANDA_MOBILE = /^07\d{8}$/
const REGISTRATION_NUMBER = /^[A-Za-z0-9]+(?:[/-][A-Za-z0-9]+)*$/

export const RWANDA_CURRENCY = 'RWF'
export const MIN_REGISTRATION_DATE = '1950-01-01'
export const MIN_CONTRIBUTION_DUE_DAY = 1
export const MAX_CONTRIBUTION_DUE_DAY = 28

export function normalizeRwandanPhone(raw: string): string {
  let digits = raw.trim().replace(/[\s().-]/g, '')
  if (digits.startsWith('+')) digits = digits.slice(1)
  if (digits.startsWith('250') && digits.length === 12) {
    digits = `0${digits.slice(3)}`
  } else if (digits.length === 9 && digits.startsWith('7')) {
    digits = `0${digits}`
  }
  return digits
}

export function isValidRwandanPhone(raw: string): boolean {
  return RWANDA_MOBILE.test(normalizeRwandanPhone(raw))
}

export function isValidCooperativeEmail(raw: string): boolean {
  const value = raw.trim()
  return value.length > 0 && value.length <= 255 && EMAIL.test(value)
}

export function normalizeRegistrationNumber(raw: string): string {
  return raw.trim().replace(/\s+/g, '').toUpperCase()
}

export function isValidRegistrationNumber(raw: string): boolean {
  const value = normalizeRegistrationNumber(raw)
  return value.length >= 4 && value.length <= 32 && REGISTRATION_NUMBER.test(value)
}

export function todayInKigaliIso(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Kigali',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
}

export function isValidRegistrationDate(iso: string, todayIso = todayInKigaliIso()): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return false
  return iso >= MIN_REGISTRATION_DATE && iso <= todayIso
}
