import { ROUTES } from '@/shared/constants/routes'

/** Last entrance beat (tagline delay 2.05s + 0.55s). Continue is available at this handoff. */
export const OU_WEALTH_SPLASH_ENTRANCE_MS = 2600

/** Short fade before navigating to the post-login destination. */
export const OU_WEALTH_SPLASH_EXIT_MS = 280

/**
 * Reduced-motion users skip the SVG/text animation. Keep a tiny gate so the
 * Enter key that submitted the login form cannot dismiss the splash.
 */
export const OU_WEALTH_SPLASH_REDUCED_MOTION_READY_MS = 50

export interface LoginSuccessLocationState {
  fromLogin: true
  next?: string
}

export function isLoginSuccessLocationState(
  value: unknown,
): value is LoginSuccessLocationState {
  return Boolean(
    value &&
      typeof value === 'object' &&
      (value as LoginSuccessLocationState).fromLogin === true,
  )
}

/** Successful password login currently always continues to Dashboard. */
export function resolvePostLoginDestination(next: unknown): string {
  if (
    typeof next === 'string' &&
    next.startsWith('/') &&
    !next.startsWith('//') &&
    next !== ROUTES.loginSuccess
  ) {
    return next
  }
  return ROUTES.dashboard
}

export function prefersReducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false
}

export function isCoarsePointer(): boolean {
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia('(pointer: coarse)').matches
  }
  return 'ontouchstart' in window
}

const MODIFIER_ONLY_KEYS = new Set(['Shift', 'Control', 'Alt', 'Meta'])

export function isContinueKey(event: KeyboardEvent): boolean {
  if (event.repeat) return false
  if (MODIFIER_ONLY_KEYS.has(event.key)) return false
  if (event.key === 'Dead' || event.key === 'Process' || event.key === 'Unidentified') {
    return false
  }
  return true
}

export const LOGIN_SUCCESS_STATE: LoginSuccessLocationState = {
  fromLogin: true,
  next: ROUTES.dashboard,
}
