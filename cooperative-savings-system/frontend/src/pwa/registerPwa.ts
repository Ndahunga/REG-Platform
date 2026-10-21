export type PwaUpdateCallbacks = {
  onNeedRefresh?: () => void
  onOfflineReady?: () => void
}

type UpdateSW = (reloadPage?: boolean) => Promise<void>

let updateSW: UpdateSW | null = null
let needRefreshListeners = new Set<() => void>()
let offlineReadyListeners = new Set<() => void>()

function notifyNeedRefresh() {
  needRefreshListeners.forEach((listener) => listener())
}

function notifyOfflineReady() {
  offlineReadyListeners.forEach((listener) => listener())
}

/**
 * Register the service worker when VITE_ENABLE_PWA=true.
 * Caches app shell / static assets only — never financial API mutations offline.
 * Uses a dynamic import so unit tests do not need the Vite PWA virtual module.
 */
export function registerPwa(callbacks?: PwaUpdateCallbacks): void {
  if (import.meta.env.VITE_ENABLE_PWA !== 'true') {
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
      void navigator.serviceWorker.getRegistrations().then((regs) => {
        for (const reg of regs) void reg.unregister()
      })
    }
    return
  }

  if (callbacks?.onNeedRefresh) needRefreshListeners.add(callbacks.onNeedRefresh)
  if (callbacks?.onOfflineReady) offlineReadyListeners.add(callbacks.onOfflineReady)

  void import('virtual:pwa-register')
    .then(({ registerSW }) => {
      updateSW = registerSW({
        immediate: true,
        onNeedRefresh() {
          notifyNeedRefresh()
        },
        onOfflineReady() {
          notifyOfflineReady()
        },
      })
    })
    .catch((error: unknown) => {
      console.warn('[PWA] Failed to register service worker', error)
    })
}

/** Subscribe to "update available" (for banners). Returns unsubscribe. */
export function onNeedRefresh(listener: () => void): () => void {
  needRefreshListeners.add(listener)
  return () => {
    needRefreshListeners.delete(listener)
  }
}

/** Subscribe to offline-ready (shell cached). Returns unsubscribe. */
export function onOfflineReady(listener: () => void): () => void {
  offlineReadyListeners.add(listener)
  return () => {
    offlineReadyListeners.delete(listener)
  }
}

/** Apply a waiting service worker update (reload when true). */
export async function applyPwaUpdate(reloadPage = true): Promise<void> {
  if (!updateSW) return
  await updateSW(reloadPage)
}

/** Check for waiting SW updates. */
export async function checkForUpdates(): Promise<void> {
  if (import.meta.env.VITE_ENABLE_PWA !== 'true') return
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
  const registration = await navigator.serviceWorker.getRegistration()
  await registration?.update()
}
