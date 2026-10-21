import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18n from 'i18next'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import { LandingPage } from '@/pages/LandingPage'
import { AppProviders } from '@/app/providers/AppProviders'
import { ROUTES } from '@/shared/constants/routes'

function renderLanding(initial = '/') {
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <AppProviders>
        <LandingPage />
      </AppProviders>
    </MemoryRouter>,
  )
}

describe('LandingPage Phase E', () => {
  it('shows public subscription pricing amounts and trial messaging', () => {
    renderLanding()
    const pricing = screen.getByTestId('landing-pricing')
    expect(within(pricing).getByTestId('pricing-trial-lead')).toHaveTextContent(/4 months free/i)
    expect(within(pricing).getByTestId('pricing-trial-months')).toHaveTextContent(/4 months/i)
    expect(within(pricing).getByTestId('pricing-monthly-amount')).toHaveTextContent(/RWF\s*2[\s,]?000/)
    expect(within(pricing).getByTestId('pricing-annual-amount')).toHaveTextContent(/RWF\s*18[\s,]?000/)
    expect(within(pricing).getByTestId('pricing-annual-list')).toHaveTextContent(/RWF\s*24[\s,]?000/)
    expect(within(pricing).getByTestId('pricing-annual-discount')).toHaveTextContent(/25%/)
    expect(within(pricing).getByTestId('pricing-annual-savings')).toHaveTextContent(/RWF\s*6[\s,]?000/)
    expect(within(pricing).getByTestId('pricing-annual-equivalent')).toHaveTextContent(/RWF\s*1[\s,]?500/)
    expect(within(pricing).getByText(/No subscription payment is required/i)).toBeInTheDocument()
  })

  it('routes pricing CTAs to signup and login', () => {
    renderLanding()
    expect(screen.getByTestId('pricing-cta-signup')).toHaveAttribute('href', ROUTES.signup)
    expect(screen.getByTestId('pricing-cta-login')).toHaveAttribute('href', ROUTES.login)
  })

  it('renders journey steps for contributions, loans, investments, and visibility', async () => {
    const user = userEvent.setup({ delay: null })
    renderLanding()
    const journey = screen.getByTestId('landing-journey')
    expect(within(journey).getByRole('heading', { name: /From contributions to shared progress/i })).toBeInTheDocument()

    await user.click(screen.getByTestId('journey-step-tab-contributions'))
    expect(screen.getByTestId('journey-panel-contributions')).toHaveTextContent(/expected to contribute/i)

    await user.click(screen.getByTestId('journey-step-tab-loans'))
    expect(screen.getByTestId('journey-panel-loans')).toHaveTextContent(/loan requests/i)

    await user.click(screen.getByTestId('journey-step-tab-investments'))
    expect(screen.getByTestId('journey-panel-investments')).toHaveTextContent(/investments/i)

    await user.click(screen.getByTestId('journey-step-tab-visibility'))
    expect(screen.getByTestId('journey-panel-visibility')).toHaveTextContent(/financial position/i)

    await user.click(screen.getByTestId('journey-step-tab-progress'))
    expect(screen.getByTestId('journey-panel-progress')).toHaveTextContent(/transparent records/i)

    await user.click(screen.getByTestId('journey-step-tab-community'))
    expect(screen.getByTestId('journey-panel-community')).toHaveTextContent(/organize your Saving Scheme/i)
  })

  it('exposes keyboard-accessible journey controls without auto-rotation', async () => {
    const user = userEvent.setup({ delay: null })
    renderLanding()
    expect(screen.getByTestId('journey-prev')).toHaveAccessibleName(/previous journey step/i)
    expect(screen.getByTestId('journey-next')).toHaveAccessibleName(/next journey step/i)
    await user.click(screen.getByTestId('journey-next'))
    expect(screen.getByTestId('journey-panel-contributions')).toBeInTheDocument()
    await user.click(screen.getByTestId('journey-prev'))
    expect(screen.getByTestId('journey-panel-community')).toBeInTheDocument()
  })

  it('does not invent fake customer statistics', () => {
    renderLanding()
    const landing = screen.getByTestId('landing-page')
    const text = landing.textContent ?? ''
    expect(text).not.toMatch(/happy customers/i)
    expect(text).not.toMatch(/98%\s*repayment/i)
    expect(text).not.toMatch(/12\s*M\s*invested/i)
    expect(text).not.toMatch(/500\s+members/i)
  })

  it('contains no member-named advanced analytics', () => {
    renderLanding()
    const landing = screen.getByTestId('landing-page')
    const text = landing.textContent ?? ''
    expect(screen.queryByTestId('advanced-insights')).not.toBeInTheDocument()
    expect(screen.queryByTestId('frequent-borrowers-card')).not.toBeInTheDocument()
    expect(screen.queryByTestId('repayment-reliability-card')).not.toBeInTheDocument()
    expect(screen.queryByTestId('member-insights')).not.toBeInTheDocument()
    expect(text).not.toMatch(/frequent borrowers/i)
    expect(text).not.toMatch(/largest active investments/i)
    expect(text).not.toMatch(/repayment reliability/i)
  })

  it('stacks pricing cards for mobile layout structure', () => {
    renderLanding()
    const cards = screen.getByTestId('landing-pricing-cards')
    expect(cards).toHaveStyle({ display: 'grid' })
    expect(screen.getByTestId('pricing-card-trial')).toBeInTheDocument()
    expect(screen.getByTestId('pricing-card-monthly')).toBeInTheDocument()
    expect(screen.getByTestId('pricing-card-annual')).toBeInTheDocument()
  })
})

const DASHES = /[\u2014\u2013]/

function collectStrings(value: unknown, acc: string[] = []): string[] {
  if (typeof value === 'string') acc.push(value)
  else if (value && typeof value === 'object') Object.values(value).forEach((v) => collectStrings(v, acc))
  return acc
}

describe('LandingPage sections and routing', () => {
  it('still renders every public section with the CTAs routed as before', () => {
    renderLanding()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/Manage your Saving Scheme with confidence/i)
    for (const testId of ['landing-hero-visual', 'landing-journey', 'landing-pricing']) {
      expect(screen.getByTestId(testId)).toBeInTheDocument()
    }
    expect(screen.getByTestId('landing-cta-signup')).toHaveAttribute('href', ROUTES.signup)
    expect(screen.getByTestId('landing-cta-login')).toHaveAttribute('href', ROUTES.login)
    expect(screen.getByTestId('pricing-cta-signup')).toHaveAttribute('href', ROUTES.signup)
    expect(screen.getByTestId('pricing-cta-login')).toHaveAttribute('href', ROUTES.login)
    expect(screen.getByTestId('landing-about-link')).toHaveAttribute('href', ROUTES.about)
    expect(screen.getByTestId('landing-contact-link')).toHaveAttribute('href', ROUTES.contact)
  })

  it('keeps the how-it-works, capability and pricing cards', () => {
    renderLanding()
    const how = screen.getByRole('region', { name: /how it works|how/i })
    expect(within(how).getAllByRole('heading', { level: 3 })).toHaveLength(6)
    expect(within(how).getByText(/step 1/i)).toBeInTheDocument()

    const capabilities = screen.getByRole('region', { name: new RegExp(en.public.landing.capabilities.title, 'i') })
    expect(within(capabilities).getAllByRole('heading', { level: 3 })).toHaveLength(10)

    const pricing = screen.getByTestId('landing-pricing-cards')
    for (const id of ['pricing-card-trial', 'pricing-card-monthly', 'pricing-card-annual']) {
      expect(within(pricing).getByTestId(id)).toBeInTheDocument()
    }
  })

  it('keeps a sensible heading hierarchy: one h1, h2 sections, h3 card titles, no skipped levels', () => {
    renderLanding()
    const landing = screen.getByTestId('landing-page')
    const levels = Array.from(landing.querySelectorAll('h1,h2,h3,h4,h5,h6')).map((h) => Number(h.tagName[1]))
    expect(levels[0]).toBe(1)
    expect(levels.filter((level) => level === 1)).toHaveLength(1)
    expect(levels.filter((level) => level === 2)).toHaveLength(7)
    levels.forEach((level, index) => {
      if (index > 0) expect(level).toBeLessThanOrEqual(levels[index - 1] + 1)
    })
  })

  it('keeps pricing values and the trial length unchanged', () => {
    renderLanding()
    const pricing = screen.getByTestId('landing-pricing')
    expect(pricing).toHaveTextContent(/4 months/)
    expect(within(pricing).getByTestId('pricing-monthly-amount')).toHaveTextContent(/RWF\s*2[\s,]?000/)
    expect(within(pricing).getByTestId('pricing-annual-amount')).toHaveTextContent(/RWF\s*18[\s,]?000/)
    expect(within(pricing).getByTestId('pricing-annual-discount')).toHaveTextContent(/25%/)
  })

  it('shows the annual list price as a parenthetical, not behind a dash', () => {
    renderLanding()
    const list = screen.getByTestId('pricing-annual-list')
    expect(list).toHaveTextContent(/RWF\s*24[\s,]?000\s*\(normal annual value\)/)
    expect(list.textContent).not.toMatch(DASHES)
  })
})

describe('LandingPage journey behavior', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not auto-rotate the journey', () => {
    vi.useFakeTimers()
    renderLanding()
    expect(screen.getByTestId('journey-panel-community')).toBeInTheDocument()
    act(() => {
      vi.advanceTimersByTime(120_000)
    })
    expect(screen.getByTestId('journey-panel-community')).toBeInTheDocument()
    expect(screen.queryByTestId('journey-panel-contributions')).not.toBeInTheDocument()
  })

  it('operates the journey tabs and previous/next controls from the keyboard', async () => {
    const user = userEvent.setup({ delay: null })
    renderLanding()

    screen.getByTestId('journey-step-tab-loans').focus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('journey-panel-loans')).toBeInTheDocument()
    expect(screen.getByTestId('journey-step-tab-loans')).toHaveAttribute('aria-selected', 'true')

    screen.getByTestId('journey-next').focus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('journey-panel-investments')).toBeInTheDocument()

    screen.getByTestId('journey-prev').focus()
    await user.keyboard(' ')
    expect(screen.getByTestId('journey-panel-loans')).toBeInTheDocument()
    expect(screen.getByRole('tablist')).toBeInTheDocument()
  })
})

describe('LandingPage copy has no decorative dash separators', () => {
  afterEach(async () => {
    await act(async () => {
      await i18n.changeLanguage('en')
    })
  })

  it('renders landing copy without em/en dash continuation punctuation (English)', () => {
    renderLanding()
    const text = screen.getByTestId('landing-page').textContent ?? ''
    expect(text).not.toMatch(DASHES)
    expect(text).toMatch(/stay clear with reports in one place\./)
    expect(text).toMatch(/See how a Saving Scheme grows and how OuWealth keeps every step organized\./)
    expect(text).toMatch(/in the scheme without replacing human judgment on loans or investments\./)
  })

  it('renders landing copy without em/en dash continuation punctuation (Kinyarwanda)', async () => {
    await act(async () => {
      await i18n.changeLanguage('rw')
    })
    renderLanding()
    const text = screen.getByTestId('landing-page').textContent ?? ''
    expect(text).not.toMatch(DASHES)
  })

  it('keeps the shared public strings (landing and footer) dash-free in both languages', () => {
    for (const locale of [en, rw]) {
      for (const section of [locale.public.landing, locale.public.footer]) {
        const offenders = collectStrings(section).filter((value) => DASHES.test(value))
        expect(offenders).toEqual([])
      }
    }
  })
})
