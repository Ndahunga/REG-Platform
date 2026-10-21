import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchMemberObligations,
  fetchMyObligation,
  fetchRotationPool,
  fetchRotationPoolEntries,
  recordRotationPoolEntry,
} from '@/shared/api/rotationPayouts'
import type { MemberObligation, RotationPoolEntry, RotationPoolSummary } from '@/shared/types/rotationPayout'
import { backendError, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { RotationObligationsPanel } from './RotationObligationsPanel'
import { RotationPoolPanel } from './RotationPoolPanel'

vi.mock('@/shared/api/rotationPayouts', () => ({
  fetchRotationPool: vi.fn(),
  fetchRotationPoolEntries: vi.fn(),
  recordRotationPoolEntry: vi.fn(),
  fetchMemberObligations: vi.fn(),
  fetchMyObligation: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const poolMock = vi.mocked(fetchRotationPool)
const entriesMock = vi.mocked(fetchRotationPoolEntries)
const recordMock = vi.mocked(recordRotationPoolEntry)
const obligationsMock = vi.mocked(fetchMemberObligations)
const mineMock = vi.mocked(fetchMyObligation)

const accountant: TestUser = { id: 'acc', roles: ['ACCOUNTANT'], permissions: ['PAYOUT_READ', 'PAYOUT_WRITE'] }
const president: TestUser = { id: 'pres', roles: ['PRESIDENT'], permissions: ['PAYOUT_READ', 'PAYOUT_WRITE', 'FUND_AUTHORIZE'] }
const member: TestUser = { id: 'm1', roles: ['MEMBER'], permissions: ['PAYOUT_READ'] }

const pool = (extra: Partial<RotationPoolSummary> = {}): RotationPoolSummary => ({
  openingPool: '30000',
  otherReceipts: '0',
  expenses: '0',
  collectedPool: '270000',
  previousPayouts: '0',
  committedPayouts: '0',
  availablePool: '300000',
  unusedReserve: '30000',
  reserveInUse: '0',
  ...extra,
})

const entry = (extra: Partial<RotationPoolEntry> = {}): RotationPoolEntry => ({
  id: 'e1',
  type: 'OPENING',
  amount: '30000',
  effectiveDate: '2026-10-01',
  reason: 'Opening pool from the old books',
  recordedBy: 'pres',
  createdAt: '2026-10-01T08:00:00Z',
  ...extra,
})

const obligation = (extra: Partial<MemberObligation> = {}): MemberObligation => ({
  memberUserId: 'm1',
  memberName: 'Ange Recipient',
  recipientPeriods: [{ periodId: 'per1', year: 2026, month: 11, status: 'SCHEDULED' }],
  paidContributions: '30000',
  paidRotating: '30000',
  verifiedRotating: '30000',
  payoutReceived: '120000',
  remainingScheduled: '30000',
  overdue: '0',
  nextDeadline: '2026-12-01',
  rotationalAdvance: '90000',
  periodsWithoutObligation: 2,
  paidOutAndStillContributing: true,
  ...extra,
})

describe('rotating pool panel', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    poolMock.mockResolvedValue(pool())
    entriesMock.mockResolvedValue([entry()])
  })

  it('shows the figures exactly as the backend returned them, with the earmark explanation', async () => {
    renderWithApp(<RotationPoolPanel cooperativeId="c1" />, { user: accountant })
    expect(await screen.findByTestId('pool-collectedPool')).toHaveTextContent(/270,000/)
    expect(screen.getByTestId('pool-openingPool')).toHaveTextContent(/30,000/)
    expect(screen.getByTestId('pool-availablePool')).toHaveTextContent(/300,000/)
    expect(screen.getByTestId('pool-unusedReserve')).toHaveTextContent(/30,000/)
    expect(screen.getByText(/earmark inside the scheme funds, not extra money/)).toBeInTheDocument()
    expect(screen.getByText('Opening pool from the old books')).toBeInTheDocument()
  })

  it('offers recording only to the fund authority', async () => {
    renderWithApp(<RotationPoolPanel cooperativeId="c1" />, { user: accountant })
    await screen.findByTestId('rotation-pool')
    expect(screen.queryByRole('button', { name: 'Record pool entry' })).not.toBeInTheDocument()
  })

  it('records an entry after validating the amount and the reason, and shows a refusal from the backend', async () => {
    recordMock.mockRejectedValueOnce(backendError(422, 'POOL_EXCEEDS_AVAILABLE_FUNDS', 'The pool would be too large'))
    renderWithApp(<RotationPoolPanel cooperativeId="c1" />, { user: president })
    await userEvent.click(await screen.findByRole('button', { name: 'Record pool entry' }))

    await userEvent.click(screen.getByRole('button', { name: 'Record entry' }))
    expect(recordMock).not.toHaveBeenCalled()
    expect(await screen.findByText('Explain the entry (at least 5 characters).')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/Amount/), '30000')
    await userEvent.type(screen.getByLabelText('Reason'), 'Opening pool from the old books')
    await userEvent.click(screen.getByRole('button', { name: 'Record entry' }))
    await waitFor(() => expect(recordMock).toHaveBeenCalledTimes(1))
    expect(recordMock.mock.calls[0][1]).toEqual({ type: 'OPENING', amount: '30000', reason: 'Opening pool from the old books' })
    expect(typeof recordMock.mock.calls[0][2]).toBe('string')
    await waitFor(() => expect(document.querySelector('.MuiAlert-colorError')).not.toBeNull())
  })
})

describe('member obligations panel', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    obligationsMock.mockResolvedValue([obligation(), obligation({ memberUserId: 'm2', memberName: 'Bob Last', payoutReceived: '0', rotationalAdvance: '0', paidOutAndStillContributing: false })])
    mineMock.mockResolvedValue(obligation())
  })

  it('shows every member to an officer, with the advance kept apart and the early-paid flag', async () => {
    renderWithApp(<RotationObligationsPanel cooperativeId="c1" staff />, { user: accountant })
    expect(await screen.findByText('Ange Recipient')).toBeInTheDocument()
    expect(screen.getByText('Bob Last')).toBeInTheDocument()
    expect(screen.getAllByTestId('obligation-advance')[0]).toHaveTextContent(/90,000/)
    expect(screen.getByText('Paid out, still contributing')).toBeInTheDocument()
    expect(screen.getByText(/never added to them/)).toBeInTheDocument()
    expect(obligationsMock).toHaveBeenCalledWith('c1')
    expect(mineMock).not.toHaveBeenCalled()
  })

  it('shows a member only their own position and never calls the officer list', async () => {
    renderWithApp(<RotationObligationsPanel cooperativeId="c1" staff={false} />, { user: member })
    expect(await screen.findByTestId('rotation-obligations')).toBeInTheDocument()
    expect(mineMock).toHaveBeenCalledWith('c1')
    expect(obligationsMock).not.toHaveBeenCalled()
    expect(screen.getByTestId('obligation-advance')).toHaveTextContent(/90,000/)
    expect(screen.queryByText('Bob Last')).not.toBeInTheDocument()
  })

  it('marks an overdue amount', async () => {
    obligationsMock.mockResolvedValue([obligation({ overdue: '30000', remainingScheduled: '0', nextDeadline: null })])
    renderWithApp(<RotationObligationsPanel cooperativeId="c1" staff />, { user: accountant })
    expect(await screen.findByTestId('obligation-overdue')).toHaveTextContent(/30,000/)
  })
})
