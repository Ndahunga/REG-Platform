import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import {
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
} from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { ContributionsPage } from './ContributionsPage'

vi.mock('@/features/contributions', () => ({
  ContributionApprovalsPanel: () => <div data-testid="contribution-approvals-panel" />,
  HistoryPanel: () => <div data-testid="history-panel" />,
  MemberContributionSubmitPanel: () => <div data-testid="submit-panel" />,
  MonthlyEntryPanel: () => <div data-testid="monthly-panel" />,
  SpecialCampaignsPanel: () => <div data-testid="special-panel" />,
}))

vi.mock('@/features/shares', () => ({
  SharePurchaseApprovalsPanel: () => <div data-testid="share-approvals-panel" />,
}))

// The subscription-aware button needs the subscription query; a plain button keeps these tests focused.
vi.mock('@/shared/components/SubscriptionAwareButton', () => ({
  SubscriptionAwareButton: ({ children, onClick }: { children: React.ReactNode; onClick?: () => void }) => (
    <button type="button" onClick={onClick}>
      {children}
    </button>
  ),
}))

function Harness() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <div data-testid="location">{`${location.pathname}${location.search}`}</div>
      <button type="button" onClick={() => navigate('/contributions?tab=share-approvals')}>
        sidebar: share approvals
      </button>
      <button type="button" onClick={() => navigate('/contributions?tab=approvals')}>
        sidebar: contribution approvals
      </button>
      <Routes>
        <Route path="/contributions" element={<ContributionsPage />} />
      </Routes>
    </>
  )
}

function renderPage(
  url: string,
  { roles, permissions }: { roles: string[]; permissions: string[] },
) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'tester',
          email: 't@example.com',
          firstName: 'Test',
          lastName: 'User',
          fullName: 'Test User',
          roles,
          permissions,
          cooperativeIds: ['coop-1'],
        },
        accessToken: 'tok',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  return render(
    <Provider store={store}>
      <ThemeProvider theme={lightTheme}>
        <MemoryRouter initialEntries={[url]}>
          <Harness />
        </MemoryRouter>
      </ThemeProvider>
    </Provider>,
  )
}

const president = { roles: [ROLE_PRESIDENT], permissions: ['CONTRIBUTION_WRITE'] }
const member = { roles: [ROLE_MEMBER], permissions: ['CONTRIBUTION_READ'] }

const tabNames = () => screen.getAllByRole('tab').map((tab) => tab.textContent)

describe('ContributionsPage tabs', () => {
  it('?tab=approvals renders only the contribution approvals panel', () => {
    renderPage('/contributions?tab=approvals', president)
    expect(screen.getByTestId('contribution-approvals-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('share-approvals-panel')).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Regular Contribution Approvals', selected: true })).toBeInTheDocument()
  })

  it('?tab=share-approvals renders only the share purchase approvals panel', () => {
    renderPage('/contributions?tab=share-approvals', president)
    expect(screen.getByTestId('share-approvals-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('contribution-approvals-panel')).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Share Purchase Approvals', selected: true })).toBeInTheDocument()
  })

  it('offers the share approvals tab right after contribution approvals to authorized reviewers', () => {
    for (const reviewer of [
      president,
      { roles: [ROLE_ACCOUNTANT], permissions: ['CONTRIBUTION_WRITE'] },
      { roles: [ROLE_SUPER_ADMIN], permissions: [] },
    ]) {
      const view = renderPage('/contributions', reviewer)
      const names = tabNames()
      expect(names).toContain('Share Purchase Approvals')
      expect(names.indexOf('Share Purchase Approvals')).toBe(names.indexOf('Regular Contribution Approvals') + 1)
      view.unmount()
    }
  })

  it('does not give the share approvals tab to users who cannot review share purchases', () => {
    const unauthorized = [
      member,
      { roles: [ROLE_SECRETARY], permissions: [] },
      // reviewer role but without the contribution-write permission
      { roles: [ROLE_ACCOUNTANT], permissions: ['CONTRIBUTION_READ'] },
      // contribution-write permission but not a share-reviewer role
      { roles: [ROLE_LOAN_OFFICER], permissions: ['CONTRIBUTION_WRITE'] },
    ]
    for (const user of unauthorized) {
      const view = renderPage('/contributions', user)
      expect(tabNames()).not.toContain('Share Purchase Approvals')
      expect(screen.queryByRole('button', { name: 'Share Purchase Approvals' })).not.toBeInTheDocument()
      view.unmount()
    }
  })

  it('falls back to an allowed tab for an unauthorized ?tab=share-approvals', () => {
    const memberView = renderPage('/contributions?tab=share-approvals', member)
    expect(screen.queryByTestId('share-approvals-panel')).not.toBeInTheDocument()
    expect(screen.getByTestId('submit-panel')).toBeInTheDocument() // the member's first tab
    memberView.unmount()

    renderPage('/contributions?tab=share-approvals', { roles: [ROLE_LOAN_OFFICER], permissions: ['CONTRIBUTION_WRITE'] })
    expect(screen.queryByTestId('share-approvals-panel')).not.toBeInTheDocument()
    expect(screen.getByTestId('monthly-panel')).toBeInTheDocument()
  })

  it('falls back to the first allowed tab for an invalid ?tab= value', () => {
    renderPage('/contributions?tab=does-not-exist', president)
    expect(screen.getByTestId('monthly-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('share-approvals-panel')).not.toBeInTheDocument()
  })

  it('still maps the legacy ?tab=mine alias to history', () => {
    renderPage('/contributions?tab=mine', member)
    expect(screen.getByTestId('history-panel')).toBeInTheDocument()
  })
})

describe('ContributionsPage follows the URL', () => {
  it('switches views when only ?tab= changes (sidebar / notification links on the same page)', async () => {
    const user = userEvent.setup()
    renderPage('/contributions?tab=approvals', president)
    expect(screen.getByTestId('contribution-approvals-panel')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'sidebar: share approvals' }))
    expect(screen.getByTestId('share-approvals-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('contribution-approvals-panel')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'sidebar: contribution approvals' }))
    expect(screen.getByTestId('contribution-approvals-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('share-approvals-panel')).not.toBeInTheDocument()
  })

  it('writes the selected tab to the URL, and clears it for the first tab', async () => {
    const user = userEvent.setup()
    renderPage('/contributions', president)
    expect(screen.getByTestId('location')).toHaveTextContent('/contributions')

    await user.click(screen.getByRole('tab', { name: 'Share Purchase Approvals' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/contributions?tab=share-approvals')
    expect(screen.getByTestId('share-approvals-panel')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Monthly entry' }))
    expect(screen.getByTestId('location').textContent).toBe('/contributions')
    expect(screen.getByTestId('monthly-panel')).toBeInTheDocument()
  })

  it('header buttons open the matching approval view', async () => {
    const user = userEvent.setup()
    renderPage('/contributions', president)

    await user.click(screen.getByRole('button', { name: 'Share Purchase Approvals' }))
    expect(within(document.body).getByTestId('share-approvals-panel')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('?tab=share-approvals')

    await user.click(screen.getByRole('button', { name: 'Regular Contribution Approvals' }))
    expect(screen.getByTestId('contribution-approvals-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('share-approvals-panel')).not.toBeInTheDocument()
  })
})
