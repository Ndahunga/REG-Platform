import { Link as MuiLink, ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ForgotPasswordPage } from '@/pages/ForgotPasswordPage'
import { LoginPage } from '@/pages/LoginPage'
import { SignupPage } from '@/pages/SignupPage'
import { ROLE_PRESIDENT } from '@/shared/types/auth'
import { cssFor } from '@/test/cssHelpers'
import { lightTheme } from '@/theme/theme'
import { AppLayout } from './AppLayout'
import { AuthLayout } from './AuthLayout'
import { PublicLayout } from './PublicLayout'

vi.mock('@/shared/api/notifications', () => ({
  fetchUnreadCount: vi.fn().mockResolvedValue(0),
  fetchNotifications: vi.fn().mockResolvedValue([]),
  fetchPendingApprovals: vi.fn().mockResolvedValue([]),
  markNotificationRead: vi.fn(),
}))
vi.mock('@/shared/api/cooperatives', () => ({ fetchMyCooperatives: vi.fn().mockResolvedValue([]) }))
vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn().mockResolvedValue({
    id: 'sub-1',
    cooperativeId: 'coop-1',
    status: 'TRIAL',
    effectiveStatus: 'TRIAL',
    writeAllowed: true,
    usable: true,
  }),
}))
vi.mock('@/shared/api/onboarding', () => ({ onboardCooperative: vi.fn() }))

function stubMedia(reducedMotion = false) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: reducedMotion && query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>
}

function renderAt(path: string, { signedIn = false }: { signedIn?: boolean } = {}) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: signedIn
        ? {
            user: {
              id: 'u1',
              username: 'pat',
              email: 'pat@example.com',
              firstName: 'Pat',
              lastName: 'Leader',
              fullName: 'Pat Leader',
              roles: [ROLE_PRESIDENT],
              permissions: [],
              cooperativeIds: ['coop-1'],
            },
            accessToken: 't',
            selectedCooperativeId: 'coop-1',
            status: 'authenticated' as const,
          }
        : { user: null, accessToken: null, selectedCooperativeId: null, status: 'anonymous' as const },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter initialEntries={[path]}>
            <Where />
            <Routes>
              <Route element={<PublicLayout />}>
                <Route path="/" element={<div data-testid="landing-stub" />} />
                <Route path="/about" element={<div data-testid="about-stub" />} />
              </Route>
              <Route element={<AuthLayout />}>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/signup" element={<SignupPage />} />
                <Route path="/forgot-password" element={<ForgotPasswordPage />} />
              </Route>
              <Route element={<AppLayout />}>
                <Route path="/dashboard" element={<div data-testid="dashboard-stub" />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

const homeLinks = () => screen.getAllByRole('link', { name: 'Go to OuWealth home' })

beforeEach(() => {
  vi.clearAllMocks()
  stubMedia()
})
afterEach(() => stubMedia())

describe('auth layout', () => {
  it('login: the OuWealth logo links to the landing page', () => {
    renderAt('/login')
    const brandPanel = screen.getByTestId('auth-brand-panel')
    const link = within(brandPanel).getByRole('link', { name: 'Go to OuWealth home' })
    expect(link).toHaveAttribute('href', '/')
    expect(within(link).getByRole('img', { name: 'OuWealth Community' })).toBeInTheDocument()
  })

  it('signup: the OuWealth logo links to the landing page', () => {
    renderAt('/signup')
    const link = within(screen.getByTestId('auth-brand-panel')).getByRole('link', { name: 'Go to OuWealth home' })
    expect(link).toHaveAttribute('href', '/')
  })

  it('forgot password: the (raster) auth logo links to the landing page too', () => {
    renderAt('/forgot-password')
    expect(homeLinks().every((link) => link.getAttribute('href') === '/')).toBe(true)
    expect(homeLinks().length).toBeGreaterThanOrEqual(1)
  })

  it('the logo link is a real, keyboard-focusable link and navigates home', async () => {
    const user = userEvent.setup()
    renderAt('/login')
    const link = within(screen.getByTestId('auth-brand-panel')).getByRole('link', { name: 'Go to OuWealth home' })
    await user.tab()
    // the brand panel logo is the first focusable element after the language/theme switchers
    link.focus()
    expect(link).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('where')).toHaveTextContent('/')
    expect(await screen.findByTestId('landing-stub')).toBeInTheDocument()
  })

  it('login renders a split shell with a brand panel and the form panel', () => {
    renderAt('/login')
    const shell = screen.getByTestId('auth-split-shell')
    const brand = within(shell).getByTestId('auth-brand-panel')
    const form = within(shell).getByTestId('auth-form-panel')
    expect(within(brand).getByText('Accumulate your wealth in an instant.')).toBeInTheDocument()
    expect(within(form).getByRole('heading', { name: /Sign in/i })).toBeInTheDocument()
    expect(within(form).getByLabelText(/username/i)).toBeInTheDocument()
    expect(screen.getByTestId('auth-canvas-split')).toBeInTheDocument()
    // no invented statistics in the brand panel
    expect(within(brand).queryByText(/\d{2,}/)).not.toBeInTheDocument()
  })

  it('signup renders the wider split shell around the wizard', () => {
    renderAt('/signup')
    const shell = screen.getByTestId('auth-split-shell')
    expect(within(shell).getByTestId('auth-brand-panel')).toBeInTheDocument()
    const form = within(shell).getByTestId('auth-form-panel')
    expect(within(form).getByRole('heading', { name: /Create your Saving Scheme/i })).toBeInTheDocument()
    expect(within(form).getByText('Saving Scheme Details')).toBeInTheDocument() // the stepper
    expect(within(form).getByRole('button', { name: 'Next' })).toBeInTheDocument()
  })

  it('signup uses the brand tagline, not the trial offer, and keeps the registration number removed', () => {
    renderAt('/signup')
    const brand = screen.getByTestId('auth-brand-panel')
    expect(within(brand).getByText('Accumulate your wealth in an instant.')).toBeInTheDocument()
    expect(screen.queryByText(/Start with 4 months free/i)).not.toBeInTheDocument()
    expect(screen.queryByTestId('trial-pricing-note')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/registration number/i)).not.toBeInTheDocument()
    // wizard buttons stay medium sized (no "large" variant)
    expect(screen.getByRole('button', { name: 'Next' }).className).not.toMatch(/sizeLarge/)
  })

  it('signup validation still blocks Next on empty required fields', async () => {
    const user = userEvent.setup()
    renderAt('/signup')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findAllByText(/required/i)).not.toHaveLength(0)
    expect(screen.getByText('Saving Scheme Details')).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Username/i)).not.toBeInTheDocument() // still on step 1
  })
})

describe('support dock', () => {
  const dock = () => screen.getByTestId('support-dock')

  it.each([
    ['public layout', '/about', false],
    ['auth layout', '/login', false],
    ['app layout', '/dashboard', true],
  ])('appears once in the %s', (_label, path, signedIn) => {
    renderAt(path, { signedIn })
    expect(screen.getAllByTestId('support-dock')).toHaveLength(1)
  })

  it('has the correct, accessible email and WhatsApp links', () => {
    renderAt('/about')
    const email = within(dock()).getByRole('link', { name: 'Email support' })
    const whatsapp = within(dock()).getByRole('link', { name: 'Chat on WhatsApp' })
    const emailHref = email.getAttribute('href') ?? ''
    expect(emailHref.startsWith('mailto:support@ozufy.com?subject=')).toBe(true)
    const params = new URLSearchParams(emailHref.split('?')[1])
    expect(params.get('subject')).toBe('OuWealth support')
    expect(params.get('body')).toBe('Hello OuWealth support team,\n\n')
    expect(email).not.toHaveAttribute('target', '_blank')
    expect(whatsapp).toHaveAttribute('href', 'https://wa.me/250793634217')
    expect(whatsapp).toHaveAttribute('target', '_blank')
    expect(whatsapp.getAttribute('rel')).toMatch(/noopener/)
    expect(whatsapp.getAttribute('rel')).toMatch(/noreferrer/)
    expect(dock()).toHaveAttribute('aria-label', 'Get help')
  })

  it('does not print raw phone numbers in the dock', () => {
    renderAt('/login')
    expect(dock()).not.toHaveTextContent(/0782102154|0793634217/)
  })
})

describe('footers', () => {
  it('the landing page keeps the full footer with the new tagline', () => {
    renderAt('/')
    const footer = screen.getByTestId('public-footer')
    expect(screen.queryByTestId('compact-footer')).not.toBeInTheDocument()
    expect(within(footer).getByText('Accumulate your wealth in an instant.')).toBeInTheDocument()
    expect(footer).not.toHaveTextContent(/Software for Saving Schemes/)
    expect(footer).not.toHaveTextContent(/—|–/)
    expect(within(footer).getByRole('navigation', { name: 'Footer links' })).toBeInTheDocument()
  })

  it('the landing footer has a compact Contact us section with phone, email and a link to the contact form', () => {
    renderAt('/')
    const contact = within(screen.getByTestId('public-footer')).getByTestId('footer-contact')
    expect(within(contact).getByRole('heading', { name: 'Contact us' })).toBeInTheDocument()
    expect(within(contact).getByRole('link', { name: '+250782102154' })).toHaveAttribute('href', 'tel:+250782102154')
    expect(within(contact).getByRole('link', { name: 'support@ozufy.com' })).toHaveAttribute(
      'href',
      'mailto:support@ozufy.com',
    )
    expect(within(contact).getByRole('link', { name: 'Send us a message' })).toHaveAttribute('href', '/contact')
  })

  it('"Send us a message" in the landing footer navigates to the contact page', async () => {
    const user = userEvent.setup()
    renderAt('/')
    await user.click(screen.getByTestId('footer-contact-form'))
    expect(screen.getByTestId('where')).toHaveTextContent('/contact')
  })

  it('no non-landing footer gains the contact section: auth, public and signed-in pages keep the plain compact footer', () => {
    for (const [path, signedIn] of [
      ['/about', false],
      ['/login', false],
      ['/signup', false],
      ['/dashboard', true],
    ] as const) {
      const view = renderAt(path, { signedIn })
      const footer = screen.getByTestId('compact-footer')
      expect(screen.queryByTestId('footer-contact')).not.toBeInTheDocument()
      expect(within(footer).queryByRole('link', { name: /\+250|support@ozufy|Send us a message/ })).not.toBeInTheDocument()
      expect(footer).not.toHaveTextContent(/Contact us|\+250|support@ozufy/)
      // still exactly: the home logo link and the copyright
      expect(within(footer).getAllByRole('link')).toHaveLength(1)
      expect(footer).toHaveTextContent(/© \d{4} OuWealth Community/)
      view.unmount()
    }
  })

  it('other public pages use the compact footer', () => {
    renderAt('/about')
    const footer = screen.getByTestId('compact-footer')
    expect(screen.queryByTestId('public-footer')).not.toBeInTheDocument()
    expect(within(footer).queryByRole('navigation')).not.toBeInTheDocument()
    expect(footer).toHaveTextContent(/© \d{4} OuWealth Community/)
  })

  it('auth pages use the compact footer', () => {
    renderAt('/login')
    expect(screen.getByTestId('compact-footer')).toBeInTheDocument()
    expect(screen.queryByTestId('public-footer')).not.toBeInTheDocument()
  })

  it('the authenticated app uses the compact footer, without marketing links', () => {
    renderAt('/dashboard', { signedIn: true })
    const footer = screen.getByTestId('compact-footer')
    expect(screen.queryByTestId('public-footer')).not.toBeInTheDocument()
    expect(within(footer).queryByRole('link', { name: /pricing|privacy|terms/i })).not.toBeInTheDocument()
  })

  it('the compact footer logo links to the landing page, also for a signed-in user, without signing out', async () => {
    const user = userEvent.setup()
    renderAt('/dashboard', { signedIn: true })
    const link = within(screen.getByTestId('compact-footer')).getByRole('link', { name: 'Go to OuWealth home' })
    expect(link).toHaveAttribute('href', '/')
    await user.click(link)
    expect(screen.getByTestId('where')).toHaveTextContent('/')
    expect(await screen.findByTestId('landing-stub')).toBeInTheDocument()
  })

  it('landing footer logo smoothly scrolls to the top instead of navigating', async () => {
    const user = userEvent.setup()
    renderAt('/')
    vi.mocked(window.scrollTo).mockClear()
    await user.click(screen.getByRole('link', { name: 'OuWealth Community, back to top' }))
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'smooth' })
    expect(screen.getByTestId('where')).toHaveTextContent('/')
  })

  it('landing footer logo jumps to the top when the visitor prefers reduced motion', async () => {
    stubMedia(true)
    const user = userEvent.setup()
    renderAt('/')
    vi.mocked(window.scrollTo).mockClear()
    await user.click(screen.getByRole('link', { name: 'OuWealth Community, back to top' }))
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'auto' })
  })

  it('the footer logo is keyboard accessible', async () => {
    const user = userEvent.setup()
    renderAt('/')
    vi.mocked(window.scrollTo).mockClear()
    screen.getByRole('link', { name: 'OuWealth Community, back to top' }).focus()
    await user.keyboard('{Enter}')
    expect(window.scrollTo).toHaveBeenCalled()
  })

  it('from another public page the logo navigates to the landing page, which starts at the top', async () => {
    const user = userEvent.setup()
    renderAt('/about')
    vi.mocked(window.scrollTo).mockClear()
    await user.click(
      within(screen.getByTestId('compact-footer')).getByRole('link', { name: 'Go to OuWealth home' }),
    )
    expect(await screen.findByTestId('landing-stub')).toBeInTheDocument()
    expect(screen.getByTestId('public-footer')).toBeInTheDocument()
    expect(window.scrollTo).toHaveBeenCalledWith(0, 0)
  })
})

describe('auth form sizing', () => {
  it('constrains only the form content, centered in its pane, to a comfortable width', () => {
    renderAt('/login')
    const panel = screen.getByTestId('auth-form-panel')
    const content = within(panel).getByTestId('auth-form-content')
    const css = cssFor(content)
    expect(css).toMatch(/max-width: 360px/)
    expect(css).toMatch(/width: 100%/)
    expect(css).toMatch(/margin-(left|inline)[^;]*auto|margin: [^;]*auto/)
    // the pane itself keeps its space and centers the content; it is not narrowed
    expect(cssFor(panel)).not.toMatch(/max-width/)
    expect(cssFor(panel)).toMatch(/justify-content: center/)
    expect(content).toContainElement(screen.getByLabelText(/username/i))
  })

  it('desktop auth card is two equal halves with a compact overall width, on login and signup', () => {
    for (const path of ['/login', '/signup']) {
      const view = renderAt(path)
      const shell = screen.getByTestId('auth-split-shell')
      const css = cssFor(shell)
      expect(css).toMatch(/display: grid/)
      // brand side and form side share the width equally from the desktop breakpoint up
      expect(css).toMatch(/grid-template-columns: 1fr 1fr/)
      // phones stack them
      expect(css).toMatch(/grid-template-columns: minmax\(0, 1fr\)/)
      expect(css).toMatch(/max-width: 900px/)
      // neither half has its own width (no flex-basis percentages): the grid alone decides
      expect(cssFor(screen.getByTestId('auth-brand-panel'))).not.toMatch(/flex: (0 0 )?\d+%/)
      expect(cssFor(screen.getByTestId('auth-form-panel'))).not.toMatch(/flex: (0 0 )?\d+%/)
      // never a fixed height that could clip the signup wizard
      expect(css).not.toMatch(/(^|[^-])height: \d/)
      view.unmount()
    }
  })

  it('signup keeps compact stacked fields on every step inside the narrower form half', async () => {
    renderAt('/signup')
    expect(screen.getByTestId('auth-form-content')).toContainElement(screen.getByRole('textbox', { name: /^Name/ }))
    // step 1 rows are single column now, so no pair of fields is squeezed side by side
    const actions = screen.getByTestId('signup-actions')
    for (const stack of screen.getByTestId('auth-form-content').querySelectorAll('.MuiStack-root')) {
      if (stack === actions) continue // the Back/Next row is meant to be a row
      expect(cssFor(stack)).not.toMatch(/flex-direction: row/)
    }
  })

  it('login: the Sign in button is content-sized and right-aligned, not form-wide', () => {
    renderAt('/login')
    const button = screen.getByTestId('login-submit')
    expect(button).toHaveTextContent('Sign in')
    expect(button).not.toHaveClass('MuiButton-fullWidth')
    expect(button).not.toHaveClass('MuiButton-sizeLarge')
    const css = cssFor(button)
    expect(css).toMatch(/width: auto/)
    expect(css).toMatch(/min-width: 120px/)
    expect(css).toMatch(/align-self: flex-end/)
  })

  it('signup: every wizard step lives in the same constrained content column', async () => {
    renderAt('/signup')
    const content = () => screen.getByTestId('auth-form-content')
    expect(cssFor(content())).toMatch(/max-width: 360px/)
    expect(content()).toContainElement(screen.getByRole('button', { name: 'Next' }))
    expect(content()).toContainElement(screen.getByText('Saving Scheme Details'))
  })

  it('signup: Back, Next and Create are content-sized, with Next alone on the right of step 1', { timeout: 15_000 }, async () => {
    renderAt('/signup')
    const actions = () => screen.getByTestId('signup-actions')
    const next = () => screen.getByRole('button', { name: 'Next' })

    expect(cssFor(actions())).toMatch(/justify-content: flex-end/)
    expect(next()).not.toHaveClass('MuiButton-fullWidth')
    expect(cssFor(next())).toMatch(/width: auto/)
    expect(cssFor(next())).toMatch(/min-width: 120px/)

    // go to step 2: Back sits on the left and Next on the right, on one row
    fireEvent.change(screen.getByRole('textbox', { name: /^Name/ }), { target: { value: 'Public Scheme' } })
    fireEvent.change(screen.getByLabelText(/Contact email/i), { target: { value: 'scheme@example.com' } })
    fireEvent.change(screen.getByLabelText(/Contact phone/i), { target: { value: '0781234567' } })
    fireEvent.change(screen.getByLabelText(/Registration date/i), { target: { value: '2024-01-15' } })
    fireEvent.change(screen.getByLabelText(/Monthly contribution/i), { target: { value: '5000' } })
    fireEvent.click(screen.getByRole('radio', { name: /Accumulating Savings/ }))
    await act(async () => {
      fireEvent.click(next())
    })
    expect(await screen.findByLabelText(/^First name/i)).toBeInTheDocument()
    expect(cssFor(actions())).toMatch(/justify-content: space-between/)
    expect(cssFor(actions())).toMatch(/flex-direction: row/)
    for (const name of ['Back', 'Next']) {
      const button = screen.getByRole('button', { name })
      expect(button).not.toHaveClass('MuiButton-fullWidth')
      expect(cssFor(button)).toMatch(/width: auto/)
    }
    expect(screen.getByTestId('auth-form-content')).toContainElement(actions())
  })
})

/** The requested links have no underline in any state, a subtle hover, and a visible keyboard focus. */
function expectPlainLink(link: Element) {
  const css = cssFor(link)
  expect(css).toMatch(/text-decoration: none/)
  expect(css).not.toMatch(/text-decoration: underline/)
  // hover keeps it underline-free and changes only the opacity
  expect(css).toMatch(/:hover[^}]*text-decoration: none/)
  expect(css).toMatch(/:hover[^}]*opacity: 0\.78/)
  // keyboard focus is clearly visible
  expect(css).toMatch(/:focus-visible[^}]*outline: 2px solid currentcolor/i)
  expect(css).toMatch(/cursor: pointer/)
}

describe('plain text links', () => {
  it('login: "Don\'t have an account? Register" links to signup, with no underline', async () => {
    const user = userEvent.setup()
    renderAt('/login')
    const register = screen.getByRole('link', { name: 'Register' })
    expect(register).toHaveAttribute('href', '/signup')
    expect(register.parentElement).toHaveTextContent("Don't have an account? Register")
    expect(screen.queryByText(/Create (an )?account/i)).not.toBeInTheDocument()
    expectPlainLink(register)

    await user.click(register)
    expect(screen.getByTestId('where')).toHaveTextContent('/signup')
  })

  it('login: Forgot password stays a clickable link to the reset page, with no underline', async () => {
    const user = userEvent.setup()
    renderAt('/login')
    const forgot = screen.getByRole('link', { name: 'Forgot password?' })
    expect(forgot).toHaveAttribute('href', '/forgot-password')
    expectPlainLink(forgot)
    await user.click(forgot)
    expect(screen.getByTestId('where')).toHaveTextContent('/forgot-password')
  })

  it('signup: "Already have an account? Login" links to login, styled like Register', async () => {
    const user = userEvent.setup()
    renderAt('/signup')
    const login = screen.getByRole('link', { name: 'Login' })
    expect(login).toHaveAttribute('href', '/login')
    expect(login.parentElement).toHaveTextContent('Already have an account? Login')
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument()
    expectPlainLink(login)
    // the same shared style as the Register link on the login page
    const signupLoginCss = cssFor(login)
    await user.click(login)
    expect(screen.getByTestId('where')).toHaveTextContent('/login')
    expect(cssFor(screen.getByRole('link', { name: 'Register' }))).toContain(
      signupLoginCss.match(/text-decoration: none/)?.[0] ?? 'missing',
    )
  })

  it('the landing footer contact links keep their destinations and have no underline', () => {
    renderAt('/')
    const contact = within(screen.getByTestId('public-footer')).getByTestId('footer-contact')
    const phone = within(contact).getByRole('link', { name: '+250782102154' })
    const email = within(contact).getByRole('link', { name: 'support@ozufy.com' })
    const message = within(contact).getByRole('link', { name: 'Send us a message' })
    expect(phone).toHaveAttribute('href', 'tel:+250782102154')
    expect(email).toHaveAttribute('href', 'mailto:support@ozufy.com')
    expect(message).toHaveAttribute('href', '/contact')
    for (const link of [phone, email, message]) expectPlainLink(link)
  })

  it('does not strip underlines from other links in the app', () => {
    render(
      <ThemeProvider theme={lightTheme}>
        <MuiLink href="/somewhere" data-testid="ordinary">
          ordinary link
        </MuiLink>
      </ThemeProvider>,
    )
    expect(cssFor(screen.getByTestId('ordinary'))).toMatch(/text-decoration: underline/)
  })
})

describe('global scrollbar hiding', () => {
  const css = readFileSync('src/index.css', 'utf8')

  it('hides scrollbars in Firefox, legacy Edge and WebKit/Chromium', () => {
    expect(css).toMatch(/\*\s*\{[^}]*scrollbar-width:\s*none/)
    expect(css).toMatch(/\*\s*\{[^}]*-ms-overflow-style:\s*none/)
    expect(css).toMatch(/\*::-webkit-scrollbar\s*\{[^}]*display:\s*none/)
  })

  it('only hides the bars: it never sets overflow, so scrolling is unchanged', () => {
    const rules = css.match(/\*\s*\{[^}]*scrollbar-width[^}]*\}|\*::-webkit-scrollbar\s*\{[^}]*\}/g) ?? []
    expect(rules).toHaveLength(2)
    for (const rule of rules) expect(rule).not.toMatch(/overflow(-x|-y)?\s*:/)
    // and nowhere in the global stylesheet is overflow hidden forced onto the page
    expect(css).not.toMatch(/overflow(-x|-y)?\s*:\s*hidden/)
  })

  it('is applied once, globally, instead of per component', () => {
    for (const file of ['src/layouts/AppLayout.tsx', 'src/layouts/AppSidebar.tsx', 'src/layouts/PublicLayout.tsx']) {
      expect(readFileSync(file, 'utf8')).not.toMatch(/scrollbar-width|::-webkit-scrollbar/)
    }
  })
})
