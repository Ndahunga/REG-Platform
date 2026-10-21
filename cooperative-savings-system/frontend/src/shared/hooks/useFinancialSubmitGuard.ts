import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useOnlineStatus } from './useOnlineStatus'
import { useServerReachable } from './useServerReachable'

export const OFFLINE_FINANCE_MESSAGE =
  'You are offline. Financial actions require an internet connection.'

export const SERVER_UNREACHABLE_MESSAGE =
  'The server cannot be reached. Financial actions are disabled until connectivity is restored.'

/** Throws/returns a clear error when offline (for form submit handlers). */
export function assertOnlineForFinance(): void {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    throw new Error(OFFLINE_FINANCE_MESSAGE)
  }
}

/** Pure helper for tests / interceptors — mutating API calls must not proceed offline. */
export function assertOfflineBlocksMutation(
  method: string | undefined,
  online: boolean,
): string | null {
  const m = (method ?? 'get').toUpperCase()
  if (!online && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(m)) {
    return OFFLINE_FINANCE_MESSAGE
  }
  return null
}

export type FinancialSubmitGuard = {
  canSubmit: boolean
  reason: string | null
  online: boolean
  serverReachable: boolean | null
  checkServer: () => Promise<boolean>
}

/**
 * Online + optional health check. Use before financial submits;
 * axios also rejects mutating API calls when offline.
 */
export function useFinancialSubmitGuard(options?: {
  requireServerReachable?: boolean
}): FinancialSubmitGuard {
  const { t } = useTranslation()
  const online = useOnlineStatus()
  const requireServer = options?.requireServerReachable ?? false
  const { reachable, check } = useServerReachable({ enabled: requireServer })

  return useMemo(() => {
    if (!online) {
      return {
        canSubmit: false,
        reason: t('pwa.offlineFinanceBlocked'),
        online,
        serverReachable: reachable,
        checkServer: check,
      }
    }
    if (requireServer && reachable === false) {
      return {
        canSubmit: false,
        reason: t('pwa.serverUnreachable'),
        online,
        serverReachable: reachable,
        checkServer: check,
      }
    }
    return {
      canSubmit: true,
      reason: null,
      online,
      serverReachable: reachable,
      checkServer: check,
    }
  }, [check, online, reachable, requireServer, t])
}
