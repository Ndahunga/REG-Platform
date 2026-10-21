import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMigrationStrategy, resolveMigrationReview } from '@/shared/api/migrationStrategy'
import { backendError, renderWithApp } from '@/test/paymentTestHarness'
import { MigrationReviewNotice } from './MigrationReviewNotice'

vi.mock('@/shared/api/migrationStrategy', () => ({
  fetchMigrationStrategy: vi.fn(),
  resolveMigrationReview: vi.fn(),
  fetchMigrationReviews: vi.fn(),
}))

const AUTHORITY = { id: 'pres', roles: ['PRESIDENT'], permissions: ['FUND_AUTHORIZE'] }
const ACCOUNTANT = { id: 'acc', roles: ['ACCOUNTANT'], permissions: [] }

const state = (needsReview: boolean) => ({ strategy: 'OPENING_BALANCE' as const, cutoverDate: '2026-09-30', source: 'BACKFILL', needsReview, locked: true })

describe('migration review notice', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(resolveMigrationReview).mockResolvedValue(state(false))
  })

  it('shows nothing for a scheme that is not under review', async () => {
    vi.mocked(fetchMigrationStrategy).mockResolvedValue(state(false))
    renderWithApp(<MigrationReviewNotice cooperativeId="c1" />, { user: AUTHORITY })
    await waitFor(() => expect(fetchMigrationStrategy).toHaveBeenCalled())
    expect(screen.queryByTestId('migration-review-notice')).not.toBeInTheDocument()
  })

  it('tells an officer without authority that imports are paused, and offers no way to resolve', async () => {
    vi.mocked(fetchMigrationStrategy).mockResolvedValue(state(true))
    renderWithApp(<MigrationReviewNotice cooperativeId="c1" />, { user: ACCOUNTANT })
    expect(await screen.findByTestId('migration-review-notice')).toHaveTextContent(/Historical imports are paused until the President or Vice President resolves the review/)
    expect(screen.queryByRole('button', { name: 'Resolve review' })).not.toBeInTheDocument()
  })

  it('lets the fund authority resolve it with an explicit strategy and a real explanation, never a guess', async () => {
    vi.mocked(fetchMigrationStrategy).mockResolvedValue(state(true))
    renderWithApp(<MigrationReviewNotice cooperativeId="c1" />, { user: AUTHORITY })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Resolve review' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resolve the migration review' })
    expect(dialog).toHaveTextContent(/does not change any figures/)

    await user.click(screen.getByRole('button', { name: 'Resolve Review' }))
    expect(await screen.findByText(/Explain how the scheme was reconciled/)).toBeInTheDocument()
    expect(resolveMigrationReview).not.toHaveBeenCalled()

    const cutover = document.querySelector('input[type="date"]') as HTMLInputElement
    await user.type(cutover, '2026-09-30')
    await user.type(screen.getByRole('textbox', { name: /How was the scheme reconciled/ }), 'Reversed the duplicate import and reconciled.')
    await user.click(screen.getByRole('button', { name: 'Resolve Review' }))
    await waitFor(() => expect(resolveMigrationReview).toHaveBeenCalledTimes(1))
    expect(vi.mocked(resolveMigrationReview).mock.calls[0]).toEqual([
      'c1',
      { strategy: 'OPENING_BALANCE', cutoverDate: '2026-09-30', note: 'Reversed the duplicate import and reconciled.' },
    ])
  })

  it('shows a refusal in the user’s language', async () => {
    vi.mocked(fetchMigrationStrategy).mockResolvedValue(state(true))
    vi.mocked(resolveMigrationReview).mockRejectedValue(backendError(409, 'MIGRATION_NOT_UNDER_REVIEW', 'English diagnostic'))
    renderWithApp(<MigrationReviewNotice cooperativeId="c1" />, { user: AUTHORITY })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Resolve review' }))
    await user.type(document.querySelector('input[type="date"]') as HTMLInputElement, '2026-09-30')
    await user.type(screen.getByRole('textbox', { name: /How was the scheme reconciled/ }), 'Reversed the duplicate import and reconciled.')
    await user.click(screen.getByRole('button', { name: 'Resolve Review' }))
    expect(await screen.findByText(/Something went wrong\. Please try again\. \(MIGRATION_NOT_UNDER_REVIEW\)/)).toBeInTheDocument()
    expect(screen.queryByText(/English diagnostic/)).not.toBeInTheDocument()
  })
})
