import type { SchemeOperatingModel } from '@/shared/types/cooperative'

/** The three Ikimina operating models, in the order they are offered. Never defaulted. */
export const OPERATING_MODELS: readonly SchemeOperatingModel[] = [
  'ACCUMULATING',
  'ROTATING',
  'HYBRID',
] as const

export function isOperatingModel(value: unknown): value is SchemeOperatingModel {
  return typeof value === 'string' && (OPERATING_MODELS as readonly string[]).includes(value)
}

/** English fallback used by form validation messages (the forms' schemas use plain English like their siblings). */
export const OPERATING_MODEL_REQUIRED_MESSAGE = 'Select a Saving Scheme operating model'
