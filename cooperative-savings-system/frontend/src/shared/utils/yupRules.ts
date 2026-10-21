import * as yup from 'yup'
import {
  checkMoney,
  isFutureIsoDate,
  isValidIsoDate,
  type MoneyProblem,
  type MoneyRules,
} from './formValidation'
import { todayInKigaliIso } from './rwandaCooperative'

/**
 * yup building blocks over the shared validation primitives, so every form reports the same problems the
 * same way. Messages are plain English like the surrounding schemas; the backend stays authoritative.
 */

export const MIN_POSITIVE_AMOUNT = '0.01'

export function moneyMessage(problem: Exclude<MoneyProblem, null>, label = 'Amount', min?: string): string {
  switch (problem) {
    case 'required':
      return `${label} is required`
    case 'invalid':
      return 'Enter a valid amount'
    case 'precision':
      return 'Use at most 4 decimal places'
    case 'tooLarge':
      return `${label} is too large`
    case 'notPositive':
      return `${label} must be greater than 0`
    case 'negative':
      return `${label} cannot be negative`
    case 'belowMinimum':
      return `${label} must be at least ${min ?? MIN_POSITIVE_AMOUNT}`
  }
}

export interface MoneyFieldOptions extends MoneyRules {
  label?: string
}

/**
 * A money amount typed as text. By default it is required and must be at least 0.01 (the backend's
 * `@DecimalMin("0.01")`). Use `allowEmpty` for optional amounts and `allowZero` where zero is legal.
 */
export function moneyField({ label = 'Amount', min, ...rules }: MoneyFieldOptions = {}) {
  const effectiveMin = rules.allowZero ? min : (min ?? MIN_POSITIVE_AMOUNT)
  return yup
    .string()
    .trim()
    .default('')
    .test('money', function (value) {
      const problem = checkMoney(value, { ...rules, min: effectiveMin })
      return problem
        ? this.createError({ message: moneyMessage(problem, label, effectiveMin) })
        : true
    })
}

export interface DateFieldOptions {
  required?: boolean
  label?: string
  /** Backend `@PastOrPresent`: reject dates after today (Africa/Kigali). Default true. */
  noFuture?: boolean
}

/** A YYYY-MM-DD date typed into a date input. */
export function dateField({ required = true, label = 'Date', noFuture = true }: DateFieldOptions = {}) {
  return yup
    .string()
    .trim()
    .default('')
    .test('date', function (value) {
      if (!value) return required ? this.createError({ message: `${label} is required` }) : true
      if (!isValidIsoDate(value)) return this.createError({ message: `Enter a valid ${label.toLowerCase()}` })
      if (noFuture && isFutureIsoDate(value, todayInKigaliIso())) {
        return this.createError({ message: `${label} cannot be in the future` })
      }
      return true
    })
}

/** Whole number within [min, max]; blank is allowed unless `required`. */
export function integerField({
  min,
  max,
  required = false,
  message,
}: {
  min: number
  max: number
  required?: boolean
  message: string
}) {
  return yup
    .string()
    .trim()
    .default('')
    .test('integer', message, (value) => {
      if (!value) return !required
      if (!/^\d+$/.test(value)) return false
      const n = Number(value)
      return Number.isSafeInteger(n) && n >= min && n <= max
    })
}
