import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import en from '@/i18n/locales/en.json'
import { fetchMemberFinancialSummary } from '@/shared/api/members'
import { ROLE_MEMBER } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { MyMemberStatusSection } from './MyMemberStatusSection'

vi.mock('@/shared/api/members', () => ({ fetchMemberFinancialSummary: vi.fn() }))

const TITLE = en.dashboard.member.myStatusTitle
const CARD = en.dashboard.member.totalContributions

/** matchMedia where only the listed queries match. */
function stubMedia(matching: (query: string) => boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: matching(query),
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
const desktopWithHover = () =>
  stubMedia((q) => q.includes('hover: hover') && q.includes('pointer: fine'))
const touchOnly = () => stubMedia(() => false)

function renderSection(props: Partial<React.ComponentProps<typeof MyMemberStatusSection>> = {}) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'jane',
          email: 'jane@example.com',
          firstName: 'Jane',
          lastName: 'Doe',
          fullName: 'Jane Doe',
          roles: [ROLE_MEMBER],
          permissions: [],
          cooperativeIds: ['coop-1'],
        },
        accessToken: 't',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <MyMemberStatusSection cooperativeId="coop-1" {...props} />
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

const header = () => screen.getByRole('button', { name: TITLE })
const cards = () => screen.queryByText(CARD)
const expectHidden = () => waitFor(() => expect(cards()).not.toBeInTheDocument())
const expectShown = () => waitFor(() => expect(cards()).toBeInTheDocument())

describe('MyMemberStatusSection (collapsible)', () => {
  let consoleError: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.clearAllMocks()
    touchOnly()
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(fetchMemberFinancialSummary).mockResolvedValue({
      currency: 'RWF',
      actualContributions: 120000,
      sharesHeld: 4,
    } as never)
  })

  afterEach(() => {
    const logged = consoleError.mock.calls.map((call: unknown[]) => call.map(String).join(' ')).join('\\n')
    expect(logged).not.toMatch(/Rendered (more|fewer) hooks|order of Hooks|Invalid hook call|Cannot update a component/i)
    consoleError.mockRestore()
    touchOnly()
  })

  it('keeps the title visible and the cards hidden by default', () => {
    renderSection()
    expect(screen.getByRole('heading', { name: TITLE })).toBeInTheDocument()
    expect(header()).toHaveAttribute('aria-expanded', 'false')
    expect(cards()).not.toBeInTheDocument()
    expect(screen.getByText(en.dashboard.member.myStatusHint)).toBeInTheDocument()
  })

  it('can start expanded when asked, and nothing is persisted', async () => {
    renderSection({ initialExpanded: true })
    expect(await screen.findByText(CARD)).toBeInTheDocument()
    expect(header()).toHaveAttribute('aria-expanded', 'true')
  })

  it('clicking the header shows the cards and clicking again hides them', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(header())
    expect(header()).toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByText(CARD)).toBeInTheDocument()
    expect(await screen.findByText(/120/)).toBeInTheDocument() // the existing value is still rendered

    await user.click(header())
    expect(header()).toHaveAttribute('aria-expanded', 'false')
    await expectHidden()
  })

  it('shows every financial card when expanded, once each', async () => {
    const user = userEvent.setup()
    renderSection()
    await user.click(header())
    for (const label of [
      en.dashboard.member.totalContributions,
      en.dashboard.member.outstandingLoan,
      en.dashboard.member.outstandingFines,
      en.dashboard.member.socialContributions,
      en.dashboard.member.contributionPercentage,
      en.dashboard.member.sharesHeld,
      en.dashboard.member.currentShareValue,
      en.dashboard.member.totalShareValue,
    ]) {
      expect(await screen.findAllByText(label)).toHaveLength(1)
    }
  })

  it('is a real button wired to its content, with a heading', async () => {
    const user = userEvent.setup()
    renderSection()
    const button = header()
    expect(button.tagName).toBe('BUTTON')
    expect(button.closest('h2')).not.toBeNull()
    const controlled = document.getElementById(button.getAttribute('aria-controls')!)
    expect(controlled).not.toBeNull()
    await user.click(button)
    await screen.findByText(CARD)
    expect(controlled).toContainElement(screen.getByText(CARD))
    expect(screen.getByRole('region', { name: TITLE })).toBeInTheDocument()
  })

  it('toggles with the Enter key', async () => {
    const user = userEvent.setup()
    renderSection()
    header().focus()
    await user.keyboard('{Enter}')
    expect(header()).toHaveAttribute('aria-expanded', 'true')
    await expectShown()
    await user.keyboard('{Enter}')
    expect(header()).toHaveAttribute('aria-expanded', 'false')
    await expectHidden()
  })

  it('toggles with the Space key', async () => {
    const user = userEvent.setup()
    renderSection()
    header().focus()
    await user.keyboard(' ')
    expect(header()).toHaveAttribute('aria-expanded', 'true')
    await expectShown()
    await user.keyboard(' ')
    expect(header()).toHaveAttribute('aria-expanded', 'false')
    await expectHidden()
  })

  describe('on a device that can hover', () => {
    beforeEach(() => desktopWithHover())

    it('hovering temporarily shows the cards and leaving hides them again', async () => {
      const user = userEvent.setup()
      renderSection()
      await user.hover(header())
      expect(await screen.findByText(CARD)).toBeInTheDocument()
      expect(header()).toHaveAttribute('aria-expanded', 'true')

      await user.unhover(header())
      await expectHidden()
      expect(header()).toHaveAttribute('aria-expanded', 'false')
    })

    it('moving the pointer from the header into the cards keeps them open', async () => {
      const user = userEvent.setup()
      renderSection()
      await user.hover(header())
      const card = await screen.findByText(CARD)
      await user.hover(card)
      expect(cards()).toBeInTheDocument()
      expect(header()).toHaveAttribute('aria-expanded', 'true')
    })

    it('a section opened by clicking stays open after the pointer leaves', async () => {
      const user = userEvent.setup()
      renderSection()
      await user.click(header()) // hover + click: now pinned open
      await screen.findByText(CARD)
      await user.unhover(header())
      await new Promise((resolve) => setTimeout(resolve, 300))
      expect(cards()).toBeInTheDocument()
      expect(header()).toHaveAttribute('aria-expanded', 'true')
    })

    it('clicking a hover-opened section pins it open, and the next click really closes it', async () => {
      const user = userEvent.setup()
      renderSection()
      await user.hover(header())
      await screen.findByText(CARD)
      await user.click(header()) // pin
      await user.unhover(header())
      expect(cards()).toBeInTheDocument()

      await user.hover(header())
      await user.click(header()) // close while the pointer is still over it
      expect(header()).toHaveAttribute('aria-expanded', 'false')
      await expectHidden()
    })
  })

  describe('on a touch-only device', () => {
    it('does not expand on hover, only on tap', async () => {
      const user = userEvent.setup()
      touchOnly()
      renderSection()
      await user.hover(header())
      expect(header()).toHaveAttribute('aria-expanded', 'false')
      expect(cards()).not.toBeInTheDocument()

      await user.click(header())
      expect(header()).toHaveAttribute('aria-expanded', 'true')
      await expectShown()

      await user.unhover(header())
      expect(cards()).toBeInTheDocument()
    })
  })
})
