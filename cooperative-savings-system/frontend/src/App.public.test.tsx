import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setThemePreference } from '@/app/store/uiSlice'
import { clearAuth } from '@/app/store/authSlice'
import { store } from '@/app/store/store'
import { BRAND_LOGO_SRC } from '@/shared/components/BrandLogo'
import { ROUTES } from '@/shared/constants/routes'
import App from './App'

vi.mock('@/shared/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/auth')>()
  return {
    ...actual,
    refresh: vi.fn().mockRejectedValue(new Error('no session')),
  }
})

function go(path: string) {
  window.history.pushState({}, '', path)
}

describe('App public site', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store.dispatch(clearAuth())
    store.dispatch(setThemePreference('light'))
    go('/')
  })

  it('renders LandingPage at / instead of login', async () => {
    render(<App />)
    expect(await screen.findByTestId('landing-page', {}, { timeout: 15_000 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: /Manage your Saving Scheme with confidence/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Sign in/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/^Password$/i)).not.toBeInTheDocument()
  }, 20_000)

  it('keeps login available at /login with OuWealth branding', async () => {
    go('/login')
    render(<App />)
    expect(await screen.findByRole('heading', { name: /Sign in/i })).toBeInTheDocument()
    expect((await screen.findAllByRole('img', { name: 'OuWealth Community' })).length).toBeGreaterThan(0)
    expect(await screen.findByText('Accumulate your wealth in an instant.')).toBeInTheDocument()
  })

  it('keeps signup onboarding at /signup', async () => {
    go('/signup')
    render(<App />)
    expect(await screen.findByRole('heading', { name: /Create your Saving Scheme/i })).toBeInTheDocument()
  })

  it('renders about, contact, privacy, and terms public pages', async () => {
    go('/about')
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('about-page')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: /About Us/i })).toBeInTheDocument()
    unmount()

    go('/contact')
    const contact = render(<App />)
    expect(await screen.findByTestId('contact-page')).toBeInTheDocument()
    expect(screen.getByTestId('contact-form')).toBeInTheDocument()
    expect(screen.queryByTestId('support-methods')).not.toBeInTheDocument()
    contact.unmount()

    go('/terms')
    render(<App />)
    expect(await screen.findByTestId('terms-page')).toBeInTheDocument()
    expect(screen.getByTestId('terms-effective')).toHaveTextContent('3 October 2026')
    expect(screen.getByRole('heading', { level: 2, name: 'Acceptable use' })).toBeInTheDocument()
  })

  it('sends old /privacy links to the privacy section of the Terms', async () => {
    go('/privacy')
    render(<App />)
    expect(await screen.findByTestId('terms-page')).toBeInTheDocument()
    expect(screen.queryByTestId('privacy-page')).not.toBeInTheDocument()
    expect(window.location.pathname).toBe('/terms')
    expect(window.location.hash).toBe('#privacy')
    expect(screen.getByRole('heading', { level: 2, name: 'Privacy and personal data' })).toBeInTheDocument()
  })

  it('routes landing CTAs to login and signup', async () => {
    const user = userEvent.setup({ delay: null })
    render(<App />)
    await screen.findByTestId('landing-page')
    expect(screen.getByTestId('landing-cta-login')).toHaveAttribute('href', ROUTES.login)
    expect(screen.getByTestId('landing-cta-signup')).toHaveAttribute('href', ROUTES.signup)
    expect(screen.getByTestId('pricing-cta-signup')).toHaveAttribute('href', ROUTES.signup)
    expect(screen.getByRole('link', { name: 'Pricing' })).toHaveAttribute('href', `${ROUTES.home}#pricing`)
    await user.click(screen.getByTestId('landing-cta-login'))
    expect(await screen.findByRole('heading', { name: /Sign in/i })).toBeInTheDocument()
  })

  it('exposes public footer links without authenticated app routes', async () => {
    render(<App />)
    const footer = await screen.findByTestId('public-footer')
    expect(within(footer).getByRole('link', { name: 'About' })).toHaveAttribute('href', ROUTES.about)
    // there is no standalone privacy page any more; privacy is a section of the Terms
    expect(within(footer).queryByRole('link', { name: /privacy/i })).not.toBeInTheDocument()
    expect(within(footer).getByRole('link', { name: 'Terms & Conditions' })).toHaveAttribute(
      'href',
      ROUTES.terms,
    )
    expect(within(footer).getByRole('link', { name: 'Contact' })).toHaveAttribute('href', ROUTES.contact)
    expect(within(footer).getByRole('link', { name: 'Login' })).toHaveAttribute('href', ROUTES.login)
    expect(within(footer).getByRole('link', { name: 'Create Saving Scheme' })).toHaveAttribute(
      'href',
      ROUTES.signup,
    )
    expect(within(footer).queryByRole('link', { name: /Dashboard/i })).not.toBeInTheDocument()
    expect(within(footer).queryByRole('link', { name: /Billing/i })).not.toBeInTheDocument()
  })

  it('opens mobile public navigation', async () => {
    const user = userEvent.setup({ delay: null })
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: query.includes('max-width') || query.includes('(max-width'),
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }),
    })
    // Force xs layout: MUI useMediaQuery up('md') should be false
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    render(<App />)
    await screen.findByTestId('landing-page')
    await user.click(screen.getByRole('button', { name: /Open menu/i }))
    const drawer = await screen.findByTestId('public-mobile-nav')
    expect(within(drawer).getByRole('link', { name: 'Home' })).toHaveAttribute('href', ROUTES.home)
    expect(within(drawer).getByRole('link', { name: 'Pricing' })).toHaveAttribute('href', `${ROUTES.home}#pricing`)
    expect(within(drawer).getByRole('link', { name: 'About' })).toHaveAttribute('href', ROUTES.about)
    expect(within(drawer).getByRole('link', { name: 'Contact' })).toHaveAttribute('href', ROUTES.contact)
    expect(within(drawer).getByRole('link', { name: 'Login' })).toHaveAttribute('href', ROUTES.login)
    expect(within(drawer).getByRole('link', { name: 'Create Saving Scheme' })).toHaveAttribute(
      'href',
      ROUTES.signup,
    )
  })

  it('does not expose cooperative or member financial data on the landing page', async () => {
    render(<App />)
    const landing = await screen.findByTestId('landing-page')
    const text = landing.textContent ?? ''
    // Public subscription pricing may show RWF amounts; block operational/member data.
    expect(text).not.toMatch(/outstanding loan/i)
    expect(text).not.toMatch(/memberUserId/i)
    expect(text).not.toMatch(/happy customers/i)
    expect(screen.queryByTestId('current-subscription-card')).not.toBeInTheDocument()
    expect(screen.queryByTestId('payment-history')).not.toBeInTheDocument()
    expect(screen.getByTestId('landing-pricing')).toBeInTheDocument()
  })

  it('does not render the authenticated app sidebar on public marketing pages', async () => {
    render(<App />)
    await screen.findByTestId('landing-page')
    expect(screen.queryByTestId('app-sidebar')).not.toBeInTheDocument()
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(screen.queryByTestId('app-top-bar')).not.toBeInTheDocument()
    expect(screen.queryByTestId('member-insights')).not.toBeInTheDocument()
  })

  it('does not render the authenticated app sidebar on login or signup', async () => {
    go('/login')
    const { unmount } = render(<App />)
    expect(await screen.findByRole('heading', { name: /Sign in/i })).toBeInTheDocument()
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(screen.queryByTestId('app-top-bar')).not.toBeInTheDocument()
    unmount()

    go('/signup')
    render(<App />)
    expect(await screen.findByRole('heading', { name: /Create/i })).toBeInTheDocument()
    expect(screen.queryByTestId('app-sidebar-desktop')).not.toBeInTheDocument()
    expect(screen.queryByTestId('app-top-bar')).not.toBeInTheDocument()
  })

  it('keeps dashboard behind authentication', async () => {
    go('/dashboard')
    render(<App />)
    expect(await screen.findByRole('heading', { name: /Sign in/i })).toBeInTheDocument()
    expect(screen.queryByTestId('landing-page')).not.toBeInTheDocument()
  })
})
