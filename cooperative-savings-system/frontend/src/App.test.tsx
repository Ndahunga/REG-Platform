import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setThemePreference } from '@/app/store/uiSlice'
import { store } from '@/app/store/store'
import { BRAND_LOGO_ON_DARK_SRC, BRAND_LOGO_SRC } from '@/shared/components/BrandLogo'
import App from './App'

vi.mock('@/shared/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/auth')>()
  return {
    ...actual,
    refresh: vi.fn().mockRejectedValue(new Error('no session')),
  }
})

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.pushState({}, '', '/login')
  })

  it('renders the OuWealth Community brand on the login route', async () => {
    store.dispatch(setThemePreference('light'))
    render(<App />)
    expect((await screen.findAllByRole('img', { name: 'OuWealth Community' })).length).toBeGreaterThan(0)
    expect(screen.queryByText('OuWealth Community')).not.toBeInTheDocument()
    expect(await screen.findByText('Accumulate your wealth in an instant.')).toBeInTheDocument()
    expect(screen.queryByText('Foundation status')).not.toBeInTheDocument()
  })

  it('keeps the brand panel and a home link on the Dark Mode login canvas', async () => {
    store.dispatch(setThemePreference('dark'))
    render(<App />)
    expect(await screen.findByTestId('auth-brand-panel')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Go to OuWealth home' })[0]).toHaveAttribute('href', '/')
    store.dispatch(setThemePreference('light'))
  })

  it('renders the public landing page at /', async () => {
    window.history.pushState({}, '', '/')
    store.dispatch(setThemePreference('light'))
    render(<App />)
    expect(await screen.findByTestId('landing-page')).toBeInTheDocument()
  })
})
