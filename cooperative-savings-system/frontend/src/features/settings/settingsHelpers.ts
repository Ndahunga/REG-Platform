import {
  DEFAULT_LOCALE,
  DEFAULT_TIMEZONE,
  type CooperativeSettings,
  type CooperativeSettingsUpdateRequest,
} from '@/shared/types/cooperativeSettings'
import { checkMoney } from '@/shared/utils/formValidation'

export interface CooperativeSettingsFormValues {
  timezone: string
  locale: string
  notifyContributions: boolean
  notifyLoans: boolean
  notifyFines: boolean
  notifyPayouts: boolean
  baseSharePrice: string
}

export const cooperativeSettingsDefaults: CooperativeSettingsFormValues = {
  timezone: DEFAULT_TIMEZONE,
  locale: DEFAULT_LOCALE,
  notifyContributions: true,
  notifyLoans: true,
  notifyFines: true,
  notifyPayouts: true,
  baseSharePrice: '',
}

export function toSettingsFormValues(
  settings: CooperativeSettings,
): CooperativeSettingsFormValues {
  return {
    timezone: settings.timezone || DEFAULT_TIMEZONE,
    locale: settings.locale || DEFAULT_LOCALE,
    notifyContributions: Boolean(settings.notifyContributions),
    notifyLoans: Boolean(settings.notifyLoans),
    notifyFines: Boolean(settings.notifyFines),
    notifyPayouts: Boolean(settings.notifyPayouts),
    baseSharePrice:
      settings.baseSharePrice != null && settings.baseSharePrice !== ''
        ? String(settings.baseSharePrice)
        : '',
  }
}

export function validateBaseSharePrice(value: string): true | string {
  const trimmed = value.trim()
  if (trimmed === '') {
    return true
  }
  const problem = checkMoney(trimmed)
  if (problem === 'precision') return 'Use at most 4 decimal places'
  if (problem === 'tooLarge') return 'Base share price is too large'
  if (problem) {
    return 'Base share price must be greater than zero, or left empty to clear'
  }
  return true
}

export function toSettingsPayload(
  values: CooperativeSettingsFormValues,
): CooperativeSettingsUpdateRequest {
  const trimmedPrice = values.baseSharePrice.trim()
  const parsedPrice = trimmedPrice === '' ? null : Number(trimmedPrice)
  const hasPositivePrice =
    parsedPrice != null && Number.isFinite(parsedPrice) && parsedPrice > 0
  return {
    timezone: values.timezone.trim() || DEFAULT_TIMEZONE,
    locale: values.locale.trim() || DEFAULT_LOCALE,
    notifyContributions: values.notifyContributions,
    notifyLoans: values.notifyLoans,
    notifyFines: values.notifyFines,
    notifyPayouts: values.notifyPayouts,
    baseSharePrice: hasPositivePrice ? parsedPrice : null,
    clearBaseSharePrice: !hasPositivePrice,
  }
}
