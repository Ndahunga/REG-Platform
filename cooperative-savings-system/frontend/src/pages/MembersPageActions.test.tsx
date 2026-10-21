import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMemberRemovalEligibility, fetchMembers } from '@/shared/api/members'
import { renderWithApp } from '@/test/paymentTestHarness'
import { MembersPage } from './MembersPage'

vi.mock('@/shared/api/members', () => ({
  fetchMembers: vi.fn(),
  createMember: vi.fn(),
  fetchMemberRemovalEligibility: vi.fn(),
  deactivateMember: vi.fn(),
  reactivateMember: vi.fn(),
  deleteMember: vi.fn(),
}))

const PRESIDENT = { id: 'pres', roles: ['PRESIDENT'], permissions: ['MEMBERSHIP_MANAGE'] }
const ACCOUNTANT = { id: 'acc', roles: ['ACCOUNTANT'], permissions: [] }

const member = (userId: string, firstName: string) => ({
  userId,
  firstName,
  lastName: 'Member',
  fullName: `${firstName} Member`,
  username: firstName.toLowerCase(),
  email: `${firstName.toLowerCase()}@test.local`,
  membershipStatus: 'ACTIVE' as const,
  roleInCooperative: 'MEMBER',
  accountStatus: 'ACTIVE' as const,
})

describe('members list actions', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMembers).mockResolvedValue({
      content: [member('m1', 'Ange'), member('pres', 'Chief')],
      totalElements: 2,
      totalPages: 1,
      number: 0,
      size: 10,
    } as never)
    vi.mocked(fetchMemberRemovalEligibility).mockResolvedValue({
      memberUserId: 'm1',
      membershipStatus: 'ACTIVE',
      canDelete: true,
      deletionBlockedReason: null,
      historyBlockers: [],
      canDeactivate: true,
      deactivationBlockedReason: null,
      canReactivate: false,
      impact: { outstandingLoanPrincipal: '0', openLoans: 0, unpaidContributions: '0', heldCredit: '0', upcomingRecipientPeriods: 0, openPayouts: 0 },
    })
  })

  it('gives leadership an accessibly named actions menu per member, but none for themselves', async () => {
    renderWithApp(<MembersPage />, { user: PRESIDENT })
    expect(await screen.findByRole('button', { name: 'Actions for Ange Member' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Actions for Chief Member' })).not.toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Actions for Ange Member' }))
    expect(await screen.findByRole('menuitem', { name: 'View member' })).toBeInTheDocument()
    await user.click(screen.getByRole('menuitem', { name: 'Remove from membership' }))
    expect(await screen.findByText('Delete member?')).toBeInTheDocument()
    expect(fetchMemberRemovalEligibility).toHaveBeenCalledWith('c1', 'm1')
  })

  it('shows no actions menu to an officer who is not leadership', async () => {
    renderWithApp(<MembersPage />, { user: ACCOUNTANT })
    await screen.findByText('Ange Member')
    expect(screen.queryByRole('button', { name: /^Actions for/ })).not.toBeInTheDocument()
  })
})
