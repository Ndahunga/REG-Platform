import { ROUTES } from '@/shared/constants/routes'

export interface PublicNavLink {
  labelKey: string
  path: string
  /** Optional in-page anchor (e.g. pricing on the landing page). */
  hash?: string
}

/** Primary header links (Home / Pricing / About / Contact). Auth CTAs are separate. */
export const publicPrimaryLinks: PublicNavLink[] = [
  { labelKey: 'public.nav.home', path: ROUTES.home },
  { labelKey: 'public.nav.pricing', path: ROUTES.home, hash: 'pricing' },
  { labelKey: 'public.nav.about', path: ROUTES.about },
  { labelKey: 'public.nav.contact', path: ROUTES.contact },
]

export const publicFooterLinks: PublicNavLink[] = [
  { labelKey: 'public.nav.about', path: ROUTES.about },
  { labelKey: 'public.nav.pricing', path: ROUTES.home, hash: 'pricing' },
  { labelKey: 'public.nav.terms', path: ROUTES.terms },
  { labelKey: 'public.nav.contact', path: ROUTES.contact },
  { labelKey: 'public.nav.login', path: ROUTES.login },
  { labelKey: 'public.nav.createScheme', path: ROUTES.signup },
]

export function publicNavTarget(item: PublicNavLink) {
  return item.hash ? { pathname: item.path, hash: item.hash } : item.path
}

export function isPublicNavActive(
  item: PublicNavLink,
  pathname: string,
  hash: string,
): boolean {
  if (item.hash) {
    return pathname === item.path && hash === `#${item.hash}`
  }
  // Home should not stay highlighted when a landing hash (e.g. #pricing) is active.
  if (item.path === ROUTES.home) {
    return pathname === item.path && (!hash || hash === '#')
  }
  return pathname === item.path
}
