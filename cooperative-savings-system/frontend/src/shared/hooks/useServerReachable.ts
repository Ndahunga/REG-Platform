import { useCallback, useEffect, useState } from 'react'
import { useOnlineStatus } from './useOnlineStatus'

const DEFAULT_TIMEOUT_MS = 4000

/**
 * Lightweight reachability probe against the public health endpoint.
 * Uses fetch (not apiClient) so auth interceptors do not interfere.
 */
export async function probeServerReachable(timeoutMs = DEFAULT_TIMEOUT_MS): Promise<boolean> {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    return false
  }

  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  const url = `${base.replace(/\/$/, '')}/public/health`
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)

  try {
    const response = await fetch(url, {
      method: 'GET',
      signal: controller.signal,
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
    return response.ok
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}

export function useServerReachable(options?: { enabled?: boolean; intervalMs?: number }) {
  const online = useOnlineStatus()
  const enabled = options?.enabled ?? true
  const intervalMs = options?.intervalMs
  const [reachable, setReachable] = useState<boolean | null>(null)
  const [checking, setChecking] = useState(false)

  const check = useCallback(async () => {
    if (!enabled) return true
    if (!online) {
      setReachable(false)
      return false
    }
    setChecking(true)
    const ok = await probeServerReachable()
    setReachable(ok)
    setChecking(false)
    return ok
  }, [enabled, online])

  useEffect(() => {
    if (!enabled) {
      setReachable(null)
      return
    }
    void check()
    if (!intervalMs) return
    const id = window.setInterval(() => void check(), intervalMs)
    return () => window.clearInterval(id)
  }, [check, enabled, intervalMs])

  return { reachable, checking, check, online }
}
