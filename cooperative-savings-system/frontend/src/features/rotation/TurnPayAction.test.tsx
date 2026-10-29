import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { requestTurnPayout } from '@/shared/api/rotation'
import { approveRotationPayout } from '@/shared/api/rotationPayouts'
import type { RotationTurn, TurnPayoutEligibility } from '@/shared/types/rotation'
import type { RotationPayout } from '@/shared/types/rotationPayout'
import { renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { TurnPayAction } from './TurnPayAction'

vi.mock('@/shared/api/rotation', () => ({ requestTurnPayout: vi.fn().mockResolvedValue({}) }))
vi.mock('@/shared/api/rotationPayouts', () => ({
  approveRotationPayout: vi.fn().mockResolvedValue({}),
  confirmRotationDisbursement: vi.fn(),
  fetchRotationPayouts: vi.fn(),
  fetchMyRotationPayouts: vi.fn(),
  fetchRotationPayoutEvents: vi.fn(),
  rejectRotationPayout: vi.fn(),
  cancelRotationPayout: vi.fn(),
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const officer: TestUser = { id: 'acc', roles: ['ACCOUNTANT'], permissions: ['PAYOUT_READ', 'PAYOUT_WRITE'] }
const turn: RotationTurn = {
  id: 't1', periodId: 'p1', year: 2026, month: 9, memberUserId: 'm1', memberName: 'Gratien Habimana', shares: 1,
  payoutAmount: 600000, openingReceived: 0, paidOut: 0, committed: 0, outstanding: 600000, state: 'DUE',
}
const eligibility: TurnPayoutEligibility = {
  turnId: 't1', periodId: 'p1', periodYear: 2026, periodMonth: 9, memberUserId: 'm1', memberName: 'Gratien Habimana',
  shares: 1, payoutAmount: 600000, outstanding: 600000, arrears: 70000, canRequestPayout: true, maxRequestable: 600000,
}

describe('TurnPayAction', () => {
  it('pays a due turn from its row: shows what is kept back and requests the payout', async () => {
    const user = userEvent.setup()
    renderWithApp(<TurnPayAction cooperativeId="c1" turn={turn} eligibility={eligibility} />, { user: officer })
    await user.click(screen.getByRole('button', { name: 'Pay' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Pay Gratien Habimana for 2026-09')
    expect(dialog).toHaveTextContent(/530,000/)
    await user.click(within(dialog).getByRole('button', { name: /Request payout/ }))
    await waitFor(() => expect(requestTurnPayout).toHaveBeenCalledWith('c1', 't1', undefined, expect.any(String)))
  })

  it('shows the next step of a payout already requested', async () => {
    const user = userEvent.setup()
    const payout = {
      id: 'po1', status: 'REQUESTED', amount: 600000, rotationTurnId: 't1',
      actions: { canApprove: true, canConfirmDisbursement: false, canCancel: false },
    } as unknown as RotationPayout
    renderWithApp(<TurnPayAction cooperativeId="c1" turn={turn} eligibility={eligibility} openPayout={payout} />, { user: officer })
    await user.click(screen.getByRole('button', { name: /Approve/ }))
    await waitFor(() => expect(approveRotationPayout).toHaveBeenCalledWith('c1', 'po1'))
  })
})
