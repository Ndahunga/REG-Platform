import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { darkTheme, lightTheme } from '@/theme/theme'
import { LandingIconBadge } from './LandingIconBadge'
import { landingCardSx } from './landingStyles'

describe('landingCardSx', () => {
  it('lifts on hover only for hover-capable pointers, and never moves under reduced motion', () => {
    for (const theme of [lightTheme, darkTheme]) {
      const style = landingCardSx('primary')(theme) as Record<string, any>

      const hover = style['@media (hover: hover) and (pointer: fine)']['&:hover']
      expect(hover.transform).toBe('translateY(-3px)')

      const reduced = style['@media (prefers-reduced-motion: reduce)']
      expect(reduced['&:hover'].transform).toBe('none')
      // movement is not transitioned at all when motion is reduced; border/shadow feedback stays
      expect(reduced.transition).not.toMatch(/transform/)
      expect(style.transition).toMatch(/transform 200ms/)
    }
  })

  it('keeps the highlighted (best value) card soft, with an accent border but no heavy shadow', () => {
    const style = landingCardSx('secondary', true)(lightTheme) as Record<string, any>
    expect(style.boxShadow).toMatch(/0 6px 18px/)
    expect(style.borderColor).not.toBe('divider')
  })
})

describe('LandingIconBadge', () => {
  it('is decorative and does not add an accessible name', () => {
    render(
      <LandingIconBadge>
        <svg data-testid="icon" />
      </LandingIconBadge>,
    )
    expect(screen.getByTestId('icon').parentElement).toHaveAttribute('aria-hidden', 'true')
  })
})
