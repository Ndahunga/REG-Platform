import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveRotationPayout,
  cancelRotationPayout,
  confirmRotationDisbursement,
  fetchEntitlements,
  fetchMyEntitlements,
  fetchMyRotationPayouts,
  fetchPayoutEligibility,
  fetchRecoveries,
  fetchRotationPayoutEvents,
  fetchRotationPayouts,
  rejectRotationPayout,
  requestRotationPayout,
} from '@/shared/api/rotationPayouts'
import type { PayoutActions, PeriodEntitlement, PeriodPayoutEligibility, RotationPayout } from '@/shared/types/rotationPayout'
import { backendError, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { RotationEntitlementsPanel } from './RotationEntitlementsPanel'
import { RotationPayoutsPanel } from './RotationPayoutsPanel'

vi.mock('@/shared/api/rotation', () => ({
  fetchTurnEligibility: vi.fn().mockResolvedValue([]),
  fetchSharePool: vi.fn(),
  requestTurnPayout: vi.fn(),
}))
vi.mock('@/shared/api/rotationPayouts', () => ({
  fetchEntitlements: vi.fn(),
  fetchMyEntitlements: vi.fn(),
  fetchPayoutEligibility: vi.fn(),
  fetchRecoveries: vi.fn(),
  requestRotationPayout: vi.fn(),
  fetchRotationPayouts: vi.fn(),
  fetchMyRotationPayouts: vi.fn(),
  fetchRotationPayoutEvents: vi.fn(),
  approveRotationPayout: vi.fn(),
  rejectRotationPayout: vi.fn(),
  cancelRotationPayout: vi.fn(),
  confirmRotationDisbursement: vi.fn(),
  fetchModelSummary: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const entitlementsMock = vi.mocked(fetchEntitlements)
const myEntitlementsMock = vi.mocked(fetchMyEntitlements)
const eligibilityMock = vi.mocked(fetchPayoutEligibility)
const recoveriesMock = vi.mocked(fetchRecoveries)
const requestMock = vi.mocked(requestRotationPayout)
const payoutsMock = vi.mocked(fetchRotationPayouts)
const myPayoutsMock = vi.mocked(fetchMyRotationPayouts)
const eventsMock = vi.mocked(fetchRotationPayoutEvents)
const approveMock = vi.mocked(approveRotationPayout)
const rejectMock = vi.mocked(rejectRotationPayout)
const cancelMock = vi.mocked(cancelRotationPayout)
const confirmMock = vi.mocked(confirmRotationDisbursement)

const accountant: TestUser = { id: 'acc', roles: ['ACCOUNTANT'], permissions: ['PAYOUT_READ', 'PAYOUT_WRITE'] }
const president: TestUser = { id: 'pres', roles: ['PRESIDENT'], permissions: ['PAYOUT_READ', 'PAYOUT_WRITE', 'FUND_AUTHORIZE'] }
const member: TestUser = { id: 'r1', roles: ['MEMBER'], permissions: ['PAYOUT_READ'] }

const entitlement = (extra: Partial<PeriodEntitlement> = {}): PeriodEntitlement => ({
  periodId: 'per1',
  cycleId: 'cy1',
  year: 2026,
  month: 11,
  recipientUserId: 'r1',
  recipientName: 'Ange Recipient',
  periodStatus: 'SCHEDULED',
  expectedRotating: '1000',
  collectedRotating: '750',
  unreconciledRotating: '0',
  rotatingArrears: '250',
  distributed: '0',
  committed: '0',
  outstandingEntitlement: '750',
  payableEntitlement: '750',
  ...extra,
})

const eligible = (extra: Partial<PeriodPayoutEligibility> = {}): PeriodPayoutEligibility => ({
  periodId: 'per1',
  periodYear: 2026,
  periodMonth: 11,
  recipientUserId: 'r1',
  canRequestPayout: true,
  payoutBlockedReason: null,
  maxRequestable: '750',
  ...extra,
})

/** What the backend says the signed-in user may do; nothing unless a test says so. */
const noActions: PayoutActions = {
  canApprove: false,
  approveBlockedReason: 'NOT_AUTHORIZED',
  canConfirmDisbursement: false,
  confirmBlockedReason: 'NOT_AUTHORIZED',
  canCancel: false,
  cancelBlockedReason: 'NOT_AUTHORIZED',
}
const can = (extra: Partial<PayoutActions>): PayoutActions => ({ ...noActions, ...extra })

const payout = (extra: Partial<RotationPayout> = {}): RotationPayout => ({
  actions: noActions,
  id: 'po1',
  rotationPeriodId: 'per1',
  periodYear: 2026,
  periodMonth: 11,
  recipientUserId: 'r1',
  recipientName: 'Ange Recipient',
  amount: '750',
  entitlementAtRequest: '750',
  availableAtRequest: '1500',
  partial: false,
  remainingAfterPayout: '0',
  status: 'REQUESTED',
  requestedBy: 'acc',
  requestedAt: '2026-10-04T08:00:00Z',
  ...extra,
})

describe('rotation entitlements', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    entitlementsMock.mockResolvedValue([entitlement()])
    myEntitlementsMock.mockResolvedValue([entitlement()])
    eligibilityMock.mockResolvedValue([eligible()])
    recoveriesMock.mockResolvedValue([])
  })

  it('shows expected contributions apart from collected money, the shortfall and what the recipient is still owed', async () => {
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    expect(await screen.findByText('Ange Recipient')).toBeInTheDocument()
    expect(screen.getByText(/Expected contributions are not cash/)).toBeInTheDocument()
    expect(screen.getByTestId('entitlement-outstanding')).toHaveTextContent(/750/)
    expect(entitlementsMock).toHaveBeenCalledWith('c1')
    expect(myEntitlementsMock).not.toHaveBeenCalled()
  })

  it('flags rotating money that needs reconciliation as not owed to anyone yet', async () => {
    entitlementsMock.mockResolvedValue([entitlement({ unreconciledRotating: '500', collectedRotating: '0', outstandingEntitlement: '0', payableEntitlement: '0' })])
    eligibilityMock.mockResolvedValue([eligible({ canRequestPayout: false, payoutBlockedReason: 'NO_COLLECTED_FUNDS', maxRequestable: '0' })])
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    expect(await screen.findByTestId('rotation-unreconciled-note')).toHaveTextContent(/held for its recipient, but it is not eligible for payout until it is confirmed/)
    expect(screen.queryByRole('button', { name: 'Request payout' })).not.toBeInTheDocument()
  })

  it('does not show the reconciliation warning when everything is verified', async () => {
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    await screen.findByText('Ange Recipient')
    expect(screen.queryByTestId('rotation-unreconciled-note')).not.toBeInTheDocument()
  })

  it('shows a member only their own periods and never calls the officer overview', async () => {
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff={false} />, { user: member })
    expect(await screen.findByText('Ange Recipient')).toBeInTheDocument()
    expect(myEntitlementsMock).toHaveBeenCalledWith('c1')
    expect(entitlementsMock).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Request payout' })).not.toBeInTheDocument()
  })

  it('offers a payout request exactly where the backend says one can be requested', async () => {
    entitlementsMock.mockResolvedValue([
      entitlement(),
      entitlement({ periodId: 'per2', month: 12, collectedRotating: '0', outstandingEntitlement: '0', payableEntitlement: '0' }),
      entitlement({ periodId: 'per3', month: 1, year: 2027, periodStatus: 'CANCELLED' }),
    ])
    eligibilityMock.mockResolvedValue([
      eligible(),
      eligible({ periodId: 'per2', canRequestPayout: false, payoutBlockedReason: 'NO_COLLECTED_FUNDS', maxRequestable: '0' }),
      eligible({ periodId: 'per3', canRequestPayout: false, payoutBlockedReason: 'PERIOD_NOT_PAYABLE', maxRequestable: '0' }),
    ])
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    await screen.findAllByText('Ange Recipient')
    expect(await screen.findAllByRole('button', { name: 'Request payout' })).toHaveLength(1)
    expect(screen.getByText('Period cancelled')).toBeInTheDocument()
    expect(screen.getByTestId('payout-blocked-reason')).toHaveTextContent(/Nothing has been collected for this period and no pool reserve covers it/)
  })

  it('does not hide the request when nothing was collected but the backend allows a payout from the pool reserve', async () => {
    entitlementsMock.mockResolvedValue([
      entitlement({ collectedRotating: '0', outstandingEntitlement: '0', payableEntitlement: '0' }),
    ])
    eligibilityMock.mockResolvedValue([eligible({ maxRequestable: '30000' })])
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Request payout' }))
    const dialog = await screen.findByRole('dialog', { name: 'Request a payout' })
    expect(within(dialog).getByText(/30,000/)).toBeInTheDocument()
  })

  it('offers nothing when the backend refuses, whatever the entitlement shows, and says why', async () => {
    eligibilityMock.mockResolvedValue([eligible({ canRequestPayout: false, payoutBlockedReason: 'PAYOUT_ALREADY_COMPLETED', maxRequestable: '0' })])
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    await screen.findByText('Ange Recipient')
    await screen.findByTestId('payout-blocked-reason')
    expect(screen.queryByRole('button', { name: 'Request payout' })).not.toBeInTheDocument()
    expect(screen.getByTestId('payout-blocked-reason')).toHaveTextContent(/paid out in full/)
  })

  it('never asks the backend for officer eligibility on behalf of a member', async () => {
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff={false} />, { user: member })
    await screen.findByText('Ange Recipient')
    expect(eligibilityMock).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Request payout' })).not.toBeInTheDocument()
  })

  it('requests a payout without an amount by default and sends an idempotency key', async () => {
    requestMock.mockResolvedValue(payout())
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Request payout' }))
    const dialog = await screen.findByRole('dialog', { name: 'Request a payout' })
    expect(within(dialog).getByText(/can never exceed the rotating funds actually collected/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Request payout' }))
    await waitFor(() => expect(requestMock).toHaveBeenCalledTimes(1))
    const [cooperativeId, body, key] = requestMock.mock.calls[0]
    expect(cooperativeId).toBe('c1')
    expect(body).toEqual({ rotationPeriodId: 'per1', amount: undefined })
    expect(key.length).toBeGreaterThan(8)
  })

  it('sends an explicit partial amount and validates it', async () => {
    requestMock.mockResolvedValue(payout({ partial: true, amount: '300', remainingAfterPayout: '450' }))
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Request payout' }))
    const dialog = await screen.findByRole('dialog', { name: 'Request a payout' })
    await user.type(within(dialog).getByRole('textbox', { name: /Amount/ }), 'abc')
    await user.click(within(dialog).getByRole('button', { name: 'Request payout' }))
    expect(await within(dialog).findByText(/Enter a positive amount/)).toBeInTheDocument()
    expect(requestMock).not.toHaveBeenCalled()
    await user.clear(within(dialog).getByRole('textbox', { name: /Amount/ }))
    await user.type(within(dialog).getByRole('textbox', { name: /Amount/ }), '300')
    await user.click(within(dialog).getByRole('button', { name: 'Request payout' }))
    await waitFor(() => expect(requestMock).toHaveBeenCalledTimes(1))
    expect(requestMock.mock.calls[0][1]).toEqual({ rotationPeriodId: 'per1', amount: '300' })
  })

  it('explains an insufficient-funds refusal with the backend code', async () => {
    requestMock.mockRejectedValue(backendError(422, 'INSUFFICIENT_AVAILABLE_FUNDS'))
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Request payout' }))
    const dialog = await screen.findByRole('dialog', { name: 'Request a payout' })
    await user.click(within(dialog).getByRole('button', { name: 'Request payout' }))
    expect(await within(dialog).findByTestId('payment-error')).toHaveTextContent(/no available funds to pay out/)
  })

  it('lists the collections of a period with late ones flagged', async () => {
    recoveriesMock.mockResolvedValue([
      { postingId: 'a', paymentId: 'p1', paymentDate: '2026-11-05', amount: '1000', rotatingAmount: '500', late: false, reversed: false },
      { postingId: 'b', paymentId: 'p2', paymentDate: '2026-12-10', amount: '1000', rotatingAmount: '500', late: true, reversed: false },
    ])
    renderWithApp(<RotationEntitlementsPanel cooperativeId="c1" staff />, { user: accountant })
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Collections' }))
    const list = await screen.findByTestId('rotation-recoveries')
    expect(within(list).getAllByText('Late')).toHaveLength(1)
    expect(recoveriesMock).toHaveBeenCalledWith('c1', 'per1')
  })
})

describe('rotation payouts', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    payoutsMock.mockResolvedValue([payout()])
    myPayoutsMock.mockResolvedValue([payout({ status: 'DISBURSED', disbursementMethod: 'BANK_TRANSFER', disbursementReference: 'BK-1' })])
    eventsMock.mockResolvedValue([{ id: 'e1', toStatus: 'REQUESTED', actorRole: 'Accountant', createdAt: '2026-10-04T08:00:00Z' }])
  })

  it('lets an authorized officer approve a payout requested by someone else, and refetches', async () => {
    payoutsMock.mockResolvedValue([payout({ actions: can({ canApprove: true, approveBlockedReason: null, canCancel: true, cancelBlockedReason: null }) })])
    approveMock.mockResolvedValue(payout({ status: 'APPROVED' }))
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: president })
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('c1', 'po1'))
    await waitFor(() => expect(payoutsMock.mock.calls.length).toBeGreaterThan(1))
  })

  it('hides approval from the requester and from the recipient and explains why', async () => {
    payoutsMock.mockResolvedValue([payout({ actions: can({ approveBlockedReason: 'REQUESTER_CANNOT_APPROVE' }) })])
    const { unmount } = renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, {
      user: { ...president, id: 'acc' },
    })
    expect(await screen.findByTestId('rotation-payout-independence')).toHaveTextContent(/different officer must approve/)
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    unmount()

    payoutsMock.mockResolvedValue([payout({ actions: can({ approveBlockedReason: 'RECIPIENT_CANNOT_APPROVE' }) })])
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: { ...president, id: 'r1' } })
    expect(await screen.findByTestId('rotation-payout-independence')).toHaveTextContent(/recipient cannot decide/)
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('does not offer approval without the fund authority', async () => {
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: accountant })
    await screen.findByTestId('rotation-payout')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('shows an approved payout as not disbursed until the actual disbursement is confirmed', async () => {
    payoutsMock.mockResolvedValue([payout({ status: 'APPROVED', approvedBy: 'pres' })])
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: { ...accountant, id: 'conf' } })
    expect(await screen.findByText(/no money has moved yet/)).toBeInTheDocument()
    expect(screen.getByTestId('rotation-payout-status')).toHaveTextContent('Approved, not yet disbursed')
    expect(screen.queryByText('Disbursed')).not.toBeInTheDocument()
  })

  it('requires a reference and an explicit confirmation of the actual disbursement', async () => {
    payoutsMock.mockResolvedValue([
      payout({ status: 'APPROVED', approvedBy: 'pres', actions: can({ canConfirmDisbursement: true, confirmBlockedReason: null }) }),
    ])
    confirmMock.mockResolvedValue(payout({ status: 'DISBURSED' }))
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: { ...accountant, id: 'conf' } })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Confirm disbursement' }))
    const dialog = await screen.findByRole('dialog', { name: 'Confirm the actual disbursement' })
    await user.click(within(dialog).getByRole('button', { name: 'Confirm disbursement' }))
    expect(await within(dialog).findByText('The reference of the actual disbursement is required.')).toBeInTheDocument()
    expect(within(dialog).getByText('Confirm that the money was really paid.')).toBeInTheDocument()
    expect(confirmMock).not.toHaveBeenCalled()

    await user.type(within(dialog).getByRole('textbox', { name: /Reference of the disbursement/ }), 'BK-77')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Confirm disbursement' }))
    await waitFor(() =>
      expect(confirmMock).toHaveBeenCalledWith('c1', 'po1', { method: 'BANK_TRANSFER', reference: 'BK-77', note: undefined }),
    )
  })

  it('shows the shortfall of a partial payout and keeps it separate from arrears', async () => {
    payoutsMock.mockResolvedValue([payout({ partial: true, amount: '300', remainingAfterPayout: '450' })])
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: accountant })
    expect(await screen.findByTestId('rotation-payout-shortfall')).toHaveTextContent(/450.* stays owed to the same recipient/)
    expect(screen.getByText('Partial payout')).toBeInTheDocument()
  })

  it('shows the backend reason when a payout cannot be approved', async () => {
    payoutsMock.mockResolvedValue([payout({ actions: can({ canApprove: true, approveBlockedReason: null }) })])
    approveMock.mockRejectedValue(backendError(422, 'PAYOUT_EXCEEDS_AVAILABLE'))
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: president })
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Approve' }))
    expect(await screen.findByTestId('payment-error')).toHaveTextContent(/cannot exceed the scheme’s available funds/)
  })

  it('rejects and cancels only with a reason', async () => {
    payoutsMock.mockResolvedValue([payout({ actions: can({ canApprove: true, approveBlockedReason: null, canCancel: true, cancelBlockedReason: null }) })])
    rejectMock.mockResolvedValue(payout({ status: 'REJECTED' }))
    cancelMock.mockResolvedValue(payout({ status: 'CANCELLED' }))
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: { ...president, id: 'someone' } })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject the payout' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(await within(dialog).findByText('A reason is required.')).toBeInTheDocument()
    expect(rejectMock).not.toHaveBeenCalled()
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Amount does not match')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    await waitFor(() => expect(rejectMock).toHaveBeenCalledWith('c1', 'po1', 'Amount does not match'))
  })

  it('shows a recipient only their own payouts, without officer actions', async () => {
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff={false} />, { user: member })
    expect(await screen.findByTestId('rotation-payout-status')).toHaveTextContent('Disbursed')
    expect(myPayoutsMock).toHaveBeenCalledWith('c1')
    expect(payoutsMock).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /approve|reject|cancel payout|confirm disbursement/i })).not.toBeInTheDocument()
  })

  it('shows the status history of a payout', async () => {
    renderWithApp(<RotationPayoutsPanel cooperativeId="c1" staff />, { user: accountant })
    await userEvent.setup().click(await screen.findByRole('button', { name: 'History' }))
    expect(await screen.findByTestId('rotation-payout-events')).toHaveTextContent(/Awaiting approval/)
  })
})
