export interface CooperativeSettings {
  id: string
  cooperativeId: string
  timezone: string
  locale: string
  notifyContributions: boolean
  notifyLoans: boolean
  notifyFines: boolean
  notifyPayouts: boolean
  baseSharePrice?: string | number | null
  createdAt?: string
  updatedAt?: string
  version?: number
}

export interface CooperativeSettingsUpdateRequest {
  timezone: string
  locale: string
  notifyContributions: boolean
  notifyLoans: boolean
  notifyFines: boolean
  notifyPayouts: boolean
  baseSharePrice?: number | null
  clearBaseSharePrice?: boolean
}

export const DEFAULT_TIMEZONE = 'Africa/Kigali'
export const DEFAULT_LOCALE = 'en'

export const COMMON_TIMEZONES = [
  'Africa/Kigali',
  'Africa/Nairobi',
  'Africa/Kampala',
  'UTC',
] as const

export const SUPPORTED_LOCALES = ['en', 'rw'] as const
