import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

/**
 * Small, framework-free validation primitives shared by forms (react-hook-form/yup schemas and manual checks).
 * They mirror the backend: money is stored with scale 4 in NUMERIC(19,4), so at most 15 whole digits and
 * 4 decimals. The backend stays authoritative; these prevent obviously invalid submissions.
 */

export const MONEY_MAX_INTEGER_DIGITS = 15
export const MONEY_MAX_DECIMALS = 4

const MONEY_PATTERN = new RegExp(`^\\d{1,${MONEY_MAX_INTEGER_DIGITS}}(\\.\\d{1,${MONEY_MAX_DECIMALS}})?$`)

export type MoneyProblem =
  | 'required'
  | 'invalid'
  | 'precision'
  | 'tooLarge'
  | 'notPositive'
  | 'negative'
  | 'belowMinimum'
  | null

export interface MoneyRules {
  /** Zero is an acceptable value (default false = must be greater than zero). */
  allowZero?: boolean
  /** Blank is acceptable (the field is optional). */
  allowEmpty?: boolean
  /** Smallest accepted amount, e.g. '0.01' to mirror a backend @DecimalMin("0.01"). */
  min?: string
}

/**
 * Classifies a user-typed money amount. Rejects non-numeric text, NaN/Infinity, exponent notation, negatives,
 * more than 4 decimals, more than 15 whole digits, and (unless allowZero) zero.
 */
export function checkMoney(raw: string | null | undefined, rules: MoneyRules = {}): MoneyProblem {
  const value = (raw ?? '').trim()
  if (!value) return rules.allowEmpty ? null : 'required'
  if (value.startsWith('-')) return /^-\d+(\.\d+)?$/.test(value) ? 'negative' : 'invalid'
  if (!/^\d+(\.\d+)?$/.test(value)) return 'invalid'
  const [whole, fraction = ''] = value.split('.')
  if (whole.replace(/^0+(?=\d)/, '').length > MONEY_MAX_INTEGER_DIGITS) return 'tooLarge'
  if (fraction.length > MONEY_MAX_DECIMALS) return 'precision'
  if (!MONEY_PATTERN.test(value)) return 'invalid'
  if (!rules.allowZero && /^0*(\.0*)?$/.test(value)) return 'notPositive'
  if (rules.min && scaledMoney(value) < scaledMoney(rules.min)) return 'belowMinimum'
  return null
}

/** Exact comparison of non-negative decimal strings at the storage scale. */
function scaledMoney(value: string): bigint {
  const [whole, fraction = ''] = value.split('.')
  const scale = BigInt(MONEY_MAX_DECIMALS)
  return BigInt(whole || '0') * 10n ** scale + BigInt(fraction.padEnd(MONEY_MAX_DECIMALS, '0') || '0')
}

export function isMoneyValid(raw: string | null | undefined, rules: MoneyRules = {}): boolean {
  return checkMoney(raw, rules) === null
}

/** Real calendar date in strict YYYY-MM-DD form (rejects 2026-02-31 and similar). */
export function isValidIsoDate(value: string | null | undefined): boolean {
  const v = (value ?? '').trim()
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return false
  const [y, m, d] = v.split('-').map(Number)
  const date = new Date(Date.UTC(y, m - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
}

/** True when the date is after today in Africa/Kigali (the zone the backend uses for "today"). */
export function isFutureIsoDate(value: string, today: string = todayInKigaliIso()): boolean {
  return value > today
}

export function exceedsLength(value: string | null | undefined, max: number): boolean {
  return (value ?? '').trim().length > max
}
