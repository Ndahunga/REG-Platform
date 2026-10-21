/**
 * Push notification architecture prep (Phase 12).
 *
 * Intended future flow:
 * 1. Request Notification permission
 * 2. Ensure an active service worker registration
 * 3. Call PushManager.subscribe with a VAPID public key from the backend
 * 4. POST the subscription endpoint + keys to a push backend
 *
 * No real push server is required in this phase — subscribeToPush is a stub.
 */

export async function subscribeToPush(): Promise<void> {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    throw new Error('Notifications are not supported in this browser')
  }

  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    throw new Error('Notification permission was not granted')
  }

  if (!('serviceWorker' in navigator)) {
    throw new Error('Service workers are not supported in this browser')
  }

  const registration = await navigator.serviceWorker.ready
  if (!registration.pushManager) {
    throw new Error('PushManager is not available')
  }

  // Architecture only — do not subscribe without a configured VAPID key / backend.
  const message = 'Push backend not configured (Phase 12 prep)'
  console.info(`[PWA] ${message}`, { registrationScope: registration.scope })
  throw new Error(message)
}
