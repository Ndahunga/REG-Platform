import { describe, expect, it } from 'vitest'
import { isPublicNavActive, publicFooterLinks, publicNavTarget, publicPrimaryLinks } from './publicNav'
import { ROUTES } from '@/shared/constants/routes'

describe('publicNav', () => {
  it('includes Pricing as a home hash link', () => {
    const pricing = publicPrimaryLinks.find((l) => l.hash === 'pricing')
    expect(pricing).toBeTruthy()
    expect(publicNavTarget(pricing!)).toEqual({ pathname: ROUTES.home, hash: 'pricing' })
  })

  it('has no privacy page link in the header or footer, and keeps Terms and Contact in the footer', () => {
    const all = [...publicPrimaryLinks, ...publicFooterLinks]
    expect(all.some((l) => l.path === '/privacy' || l.labelKey.includes('privacy'))).toBe(false)
    expect(publicFooterLinks.map((l) => l.path)).toEqual(expect.arrayContaining([ROUTES.terms, ROUTES.contact, ROUTES.about]))
  })

  it('activates Pricing only when hash matches', () => {
    const pricing = publicPrimaryLinks.find((l) => l.hash === 'pricing')!
    const home = publicPrimaryLinks.find((l) => l.labelKey === 'public.nav.home')!
    expect(isPublicNavActive(pricing, '/', '#pricing')).toBe(true)
    expect(isPublicNavActive(pricing, '/', '')).toBe(false)
    expect(isPublicNavActive(home, '/', '#pricing')).toBe(false)
    expect(isPublicNavActive(home, '/', '')).toBe(true)
  })
})
