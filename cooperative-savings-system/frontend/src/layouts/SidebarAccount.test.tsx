import { configureStore } from '@reduxjs/toolkit'
import { render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_MEMBER } from '@/shared/types/auth'
import { getAppSidebarSections } from './navItems'
import { SidebarAccount } from './SidebarAccount'

vi.mock('@/shared/api/auth', () => ({ logout: vi.fn().mockResolvedValue(undefined) }))

function renderAccount(user: Record<string, unknown> | null) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: user as never,
        accessToken: user ? 'token' : null,
        selectedCooperativeId: null,
        status: (user ? 'authenticated' : 'unauthenticated') as 'authenticated' | 'unauthenticated',
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const, sidebarPinned: true },
    },
  })
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <SidebarAccount />
      </MemoryRouter>
    </Provider>,
  )
}

describe('SidebarAccount', () => {
  it('shows the name and role from auth state', () => {
    renderAccount({ id: 'u1', username: 'pat', fullName: 'Pat Doe', roles: [ROLE_MEMBER], permissions: [] })
    expect(screen.getByText('Pat Doe')).toBeInTheDocument()
    expect(screen.getByTestId('sidebar-account').textContent).not.toMatch(/undefined|null/i)
  })

  it('falls back to the username when the full name is blank', () => {
    renderAccount({ id: 'u1', username: 'pat', fullName: '   ', roles: [ROLE_MEMBER], permissions: [] })
    expect(screen.getByText('pat')).toBeInTheDocument()
  })

  it('never renders undefined placeholders when user data is unavailable, and keeps Sign out', () => {
    renderAccount(null)
    const account = screen.getByTestId('sidebar-account')
    expect(account.textContent).not.toMatch(/undefined|null/i)
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
  })
})

describe('getAppSidebarSections', () => {
  it('only ever produces the four known sections, in order, with no duplicate paths', () => {
    const { sections } = getAppSidebarSections(['PRESIDENT'])
    expect(sections.map((s) => s.id)).toEqual(['operations', 'finance', 'administration', 'profile'])
    const paths = sections.flatMap((s) => s.items.map((i) => i.path))
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('applies the existing role filtering', () => {
    const labels = (roles: string[]) =>
      getAppSidebarSections(roles).sections.flatMap((s) => s.items.map((i) => i.labelKey))
    expect(labels(['MEMBER'])).not.toContain('nav.billing')
    expect(labels(['MEMBER'])).not.toContain('nav.settings')
    expect(labels(['SECRETARY'])).not.toContain('nav.billing')
    expect(labels(['PRESIDENT'])).toContain('nav.billing')
  })
})
