import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deactivateMember, deleteMember, fetchMemberRemovalEligibility, reactivateMember } from '@/shared/api/members'
import type { MemberRemovalEligibility } from '@/shared/types/member'
import { backendError, renderWithApp } from '@/test/paymentTestHarness'
import { MemberRemovalDialog } from './MemberRemovalDialog'

vi.mock('@/shared/api/members', () => ({
  fetchMemberRemovalEligibility: vi.fn(),
  deactivateMember: vi.fn(),
  reactivateMember: vi.fn(),
  deleteMember: vi.fn(),
}))

const PRESIDENT = { id: 'pres', roles: ['PRESIDENT'], permissions: ['MEMBERSHIP_MANAGE'] }

const eligibility = (extra: Partial<MemberRemovalEligibility> = {}): MemberRemovalEligibility => ({
  memberUserId: 'm1',
  membershipStatus: 'ACTIVE',
  canDelete: false,
  deletionBlockedReason: 'MEMBER_HAS_FINANCIAL_HISTORY',
  historyBlockers: [{ source: 'contributions', count: 3 }],
  canDeactivate: true,
  deactivationBlockedReason: null,
  canReactivate: false,
  impact: { outstandingLoanPrincipal: '100000', openLoans: 1, unpaidContributions: '60000', heldCredit: '5000', upcomingRecipientPeriods: 2, openPayouts: 0 },
  ...extra,
})

function open() {
  return renderWithApp(<MemberRemovalDialog cooperativeId="c1" userId="m1" memberName="Ange Member" open onClose={vi.fn()} />, { user: PRESIDENT })
}

describe('removing a member', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(deleteMember).mockResolvedValue()
    vi.mocked(deactivateMember).mockResolvedValue({} as never)
    vi.mocked(reactivateMember).mockResolvedValue({} as never)
  })

  it('says Delete, and only Delete, for a member the backend says has no history at all', async () => {
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue(
      eligibility({ canDelete: true, deletionBlockedReason: null, historyBlockers: [] }),
    )
    open()
    expect(await screen.findByText('Delete member?')).toBeInTheDocument()
    expect(screen.getByText('This member has no financial activity and will be permanently deleted.')).toBeInTheDocument()
    expect(deleteMember).not.toHaveBeenCalled()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Delete Member' }))
    await waitFor(() => expect(deleteMember).toHaveBeenCalledWith('c1', 'm1'))
  })

  it('offers to deactivate instead when a member with no history could also be deactivated', async () => {
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue(eligibility({ canDelete: true, deletionBlockedReason: null, historyBlockers: [] }))
    open()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Deactivate instead' }))
    expect(await screen.findByText('Deactivate member?')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete Member' })).not.toBeInTheDocument()
  })

  it('says Deactivate, never Delete, for a member with history, lists what stays on the books and needs a reason', async () => {
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue(eligibility())
    open()
    expect(await screen.findByText('Deactivate member?')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete Member' })).not.toBeInTheDocument()
    expect(screen.getByText(/cannot be permanently deleted. They will be removed from active membership while their financial history remains available/)).toBeInTheDocument()
    const impact = screen.getByTestId('member-removal-impact')
    expect(within(impact).getByText(/Loans still owed: 1 \(.*100,000/)).toBeInTheDocument()
    expect(within(impact).getByText(/Contributions still unpaid: .*60,000/)).toBeInTheDocument()
    expect(within(impact).getByText(/Credit held for the member: .*5,000/)).toBeInTheDocument()
    expect(within(impact).getByText(/Rotation periods still ahead as recipient: 2/)).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Deactivate Member' }))
    expect(await screen.findByText(/Explain why the member is being deactivated/)).toBeInTheDocument()
    expect(deactivateMember).not.toHaveBeenCalled()
    await user.type(screen.getByRole('textbox', { name: /Reason/ }), 'Left the group')
    await user.click(screen.getByRole('button', { name: 'Deactivate Member' }))
    await waitFor(() => expect(deactivateMember).toHaveBeenCalledWith('c1', 'm1', 'Left the group'))
  })

  it('explains in the user’s words why a member cannot be removed now', async () => {
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue(
      eligibility({ canDeactivate: false, deactivationBlockedReason: 'MEMBER_HAS_OPEN_PAYOUT' }),
    )
    open()
    expect(await screen.findByTestId('member-removal-blocked')).toHaveTextContent(/payout for this member is waiting for approval or disbursement/)
    expect(screen.queryByRole('button', { name: /Deactivate Member|Delete Member/ })).not.toBeInTheDocument()
  })

  it('reactivates an inactive member', async () => {
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue(
      eligibility({ membershipStatus: 'INACTIVE', canDeactivate: false, deactivationBlockedReason: 'MEMBER_ALREADY_INACTIVE', canReactivate: true }),
    )
    open()
    expect(await screen.findByText('Reactivate member?')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Reactivate Member' }))
    await waitFor(() => expect(reactivateMember).toHaveBeenCalledWith('c1', 'm1'))
  })

  it('shows the backend’s refusal in plain words, not the English diagnostic', async () => {
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue(eligibility({ canDelete: true, deletionBlockedReason: null, historyBlockers: [] }))
    vi.mocked(deleteMember).mockRejectedValue(backendError(409, 'MEMBER_HAS_FINANCIAL_HISTORY', 'English diagnostic text'))
    open()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Delete Member' }))
    expect(await screen.findByText(/financial history and cannot be permanently deleted. Deactivate them instead/)).toBeInTheDocument()
    expect(screen.queryByText(/English diagnostic text/)).not.toBeInTheDocument()
  })
})
