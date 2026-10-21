import { render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AppProviders } from '@/app/providers/AppProviders'
import {
  LANDING_BODY_FONT,
  LANDING_HEADING_FONT,
  landingCardBodySx,
  landingCardTitleSx,
  landingLabelSx,
  landingSectionTitleSx,
} from '@/features/landing/landingStyles'
import { LandingPage } from '@/pages/LandingPage'

function renderLanding() {
  return render(
    <MemoryRouter>
      <AppProviders>
        <LandingPage />
      </AppProviders>
    </MemoryRouter>,
  )
}

const family = (el: Element) => getComputedStyle(el).fontFamily

describe('landing font stacks', () => {
  it('uses the exact system stacks, with no bundled fonts', () => {
    expect(LANDING_BODY_FONT).toBe("Candara, Calibri, 'Segoe UI', sans-serif")
    expect(LANDING_HEADING_FONT).toBe("'Tempus Sans ITC', Candara, Calibri, 'Segoe UI', sans-serif")
    expect(LANDING_HEADING_FONT).not.toMatch(/Georgia|Times/i)
  })

  it('no landing source forces Georgia any more', () => {
    for (const file of [
      'src/pages/LandingPage.tsx',
      'src/features/landing/LandingJourneySection.tsx',
      'src/features/landing/LandingPricingSection.tsx',
      'src/features/landing/landingStyles.ts',
      'src/features/landing/LandingIconBadge.tsx',
    ]) {
      expect(readFileSync(file, 'utf8'), file).not.toMatch(/Georgia/)
    }
  })

  it('body copy and the landing root use Candara', () => {
    renderLanding()
    expect(family(screen.getByTestId('landing-page'))).toContain('Candara')
    const hero = screen.getByRole('heading', { level: 1 })
    const subtitle = hero.parentElement!.querySelector('p')!
    expect(family(subtitle)).toContain('Candara')
    expect(family(subtitle)).not.toContain('Tempus')
  })

  it('headings (h1, h2 sections, h3 card titles, plan names) use Tempus Sans ITC with Candara as fallback', () => {
    renderLanding()
    const landing = screen.getByTestId('landing-page')
    const headings = landing.querySelectorAll('h1, h2, h3')
    expect(headings.length).toBeGreaterThan(8)
    for (const heading of headings) {
      const stack = family(heading)
      expect(stack, heading.textContent ?? '').toContain('Tempus Sans ITC')
      expect(stack).toContain('Candara')
      expect(stack).not.toMatch(/Georgia/)
    }
  })

  it('short brand statements in the visuals use the heading stack, not a serif', () => {
    renderLanding()
    const statements = screen.getByTestId('landing-page').querySelectorAll('p.MuiTypography-h5')
    expect(statements.length).toBeGreaterThan(0)
    for (const statement of statements) {
      expect(family(statement)).toContain('Tempus Sans ITC')
      expect(family(statement)).not.toMatch(/Georgia/)
    }

  })

  it('keeps the heading hierarchy: one h1 and no skipped levels', () => {
    renderLanding()
    const levels = [...screen.getByTestId('landing-page').querySelectorAll('h1, h2, h3, h4')].map((h) =>
      Number(h.tagName[1]),
    )
    expect(levels.filter((l) => l === 1)).toHaveLength(1)
    levels.reduce((previous, level) => {
      expect(level - previous).toBeLessThanOrEqual(1)
      return level
    }, 1)
  })
})

describe('landing type scale stays calm', () => {
  it('uses the agreed sizes and never a 700 weight', () => {
    expect(landingSectionTitleSx.fontSize).toEqual({ xs: '1.45rem', md: '1.75rem' })
    expect(landingCardTitleSx.fontSize).toBe('1rem')
    expect(landingCardBodySx.fontSize).toBe('0.9rem')
    expect(landingLabelSx.fontSize).toBe('0.72rem')
    for (const style of [landingSectionTitleSx, landingCardTitleSx, landingCardBodySx, landingLabelSx]) {
      expect(style.fontWeight).toBeLessThanOrEqual(600)
    }
    expect(landingCardBodySx.lineHeight).toBeGreaterThanOrEqual(1.55)
    expect(landingCardBodySx.lineHeight).toBeLessThanOrEqual(1.65)
  })

  it('keeps the other landing files free of 700 weights and over-large headings', () => {
    for (const file of [
      'src/pages/LandingPage.tsx',
      'src/features/landing/LandingJourneySection.tsx',
      'src/features/landing/LandingPricingSection.tsx',
    ]) {
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(/fontWeight: 700/)
      expect(source, file).not.toMatch(/fontSize: (\{[^}]*)?'(2\.[4-9]|[3-9])\d*rem'/)
    }
  })

  it('keeps the global theme untouched (heading family is only overridden inside the landing page)', async () => {
    const { lightTheme } = await import('@/theme/theme')
    expect(String(lightTheme.typography.h2.fontFamily)).toMatch(/Georgia/)
    expect(String(lightTheme.typography.fontFamily)).toContain('Candara')
  })
})
