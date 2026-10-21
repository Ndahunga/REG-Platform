import axios from 'axios'
import type { TFunction } from 'i18next'
import i18n from '@/i18n'

/**
 * A backend failure in the user's language, from the stable error code the backend returns. The backend's own English
 * message is for logs and diagnostics: it is never shown to a user, and no English text is translated here. A code with
 * no translation shows the generic message followed by the code, so support can still identify it.
 *
 * @param prefix the i18n group holding the translations of this screen's codes (for example `historicalImport.errorCodes`)
 */
export function localizedApiError(t: TFunction, error: unknown, prefix: string): string {
  if (!axios.isAxiosError(error)) return t('errors.generic')
  if (!error.response) return t('errors.network')
  const status = error.response.status
  const code = (error.response.data as { code?: unknown } | undefined)?.code
  if (typeof code === 'string' && code) {
    const key = `${prefix}.${code}`
    if (i18n.exists(key)) return t(key)
    if (status === 401) return t('errors.unauthorized')
    if (status === 403) return t('errors.forbidden')
    return `${t('errors.generic')} (${code})`
  }
  if (status === 401) return t('errors.unauthorized')
  if (status === 403) return t('errors.forbidden')
  if (status === 404) return t('errors.notFoundItem')
  return t('errors.generic')
}
