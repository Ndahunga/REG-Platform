import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { addPaymentAccount, deactivatePaymentAccount, deletePaymentAccount, fetchPaymentAccounts } from '@/shared/api/memberPayments'
import { backendError } from '@/test/paymentTestHarness'
import { renderWithApp } from '@/test/paymentTestHarness'
import { PaymentAccountsPanel } from './PaymentAccountsPanel'

vi.mock('@/shared/api/memberPayments', () => ({
  fetchPaymentAccounts: vi.fn(),
  addPaymentAccount: vi.fn(),
  deactivatePaymentAccount: vi.fn(),
  deletePaymentAccount: vi.fn(),
}))

const OFFICER = { id: 'o', roles: ['PRESIDENT'], permissions: ['SETTINGS_MANAGE'] }

describe('PaymentAccountsPanel', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([])
    vi.mocked(addPaymentAccount).mockResolvedValue({} as never)
    vi.mocked(deactivatePaymentAccount).mockResolvedValue()
    vi.mocked(deletePaymentAccount).mockResolvedValue()
  })

  it('says when no account is registered and registers one with the entered details', async () => {
    renderWithApp(<PaymentAccountsPanel cooperativeId="c1" />, { user: OFFICER })
    expect(await screen.findByText('No payment account has been registered yet.')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: 'Account or phone number' }), '0788 000 111')
    await user.type(screen.getByRole('textbox', { name: 'Name (optional)' }), 'Ikimina MoMo')
    await user.click(screen.getByRole('button', { name: 'Add account' }))
    await waitFor(() => expect(addPaymentAccount).toHaveBeenCalledTimes(1))
    expect(vi.mocked(addPaymentAccount).mock.calls[0]).toEqual([
      'c1',
      { channel: 'MTN_MOMO', accountIdentifier: '0788 000 111', label: 'Ikimina MoMo' },
    ])
  })

  it('refuses an account number that is too short before asking the server', async () => {
    renderWithApp(<PaymentAccountsPanel cooperativeId="c1" />, { user: OFFICER })
    await screen.findByText('No payment account has been registered yet.')
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: 'Account or phone number' }), '123')
    await user.click(screen.getByRole('button', { name: 'Add account' }))
    expect(addPaymentAccount).not.toHaveBeenCalled()
  })

  it('lists the accounts and stops using one on request', async () => {
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([
      { id: 'p1', channel: 'MTN_MOMO', accountIdentifier: '0788000111', masked: false, label: 'Ikimina MoMo', active: true, createdAt: '2026-01-01T00:00:00Z' },
      { id: 'p2', channel: 'BANK_TRANSFER', accountIdentifier: '00112233', masked: false, label: null, active: false, createdAt: '2026-01-01T00:00:00Z' },
    ])
    renderWithApp(<PaymentAccountsPanel cooperativeId="c1" />, { user: OFFICER })
    expect(await screen.findByText('0788000111')).toBeInTheDocument()
    expect(screen.getByText('Not in use')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Deactivate account 0788000111' }))
    // it says what will happen before doing it
    expect(await screen.findByText('Deactivate payment account?')).toBeInTheDocument()
    expect(deactivatePaymentAccount).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Deactivate Account' }))
    await waitFor(() => expect(deactivatePaymentAccount).toHaveBeenCalledWith('c1', 'p1'))
  })

  it('offers Delete only for an account the backend says was never used, and confirms with plain words first', async () => {
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([
      { id: 'u1', channel: 'MTN_MOMO', accountIdentifier: '0788111222', masked: false, label: null, active: true, createdAt: '2026-01-01T00:00:00Z', canDelete: true, deletionBlockedReason: null },
      { id: 'u2', channel: 'MTN_MOMO', accountIdentifier: '0788333444', masked: false, label: null, active: true, createdAt: '2026-01-01T00:00:00Z', canDelete: false, deletionBlockedReason: 'PAYMENT_ACCOUNT_IN_USE' },
    ])
    renderWithApp(<PaymentAccountsPanel cooperativeId="c1" />, { user: OFFICER })
    await screen.findByText('0788111222')
    expect(screen.getByRole('button', { name: 'Delete account 0788111222' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete account 0788333444' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deactivate account 0788333444' })).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Delete account 0788111222' }))
    expect(await screen.findByText('Delete payment account?')).toBeInTheDocument()
    expect(screen.getByText(/never used for a payment or a receipt and will be permanently deleted/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delete Account' }))
    await waitFor(() => expect(deletePaymentAccount).toHaveBeenCalledWith('c1', 'u1'))
  })

  it('shows the backend refusal when an account turns out to have been used', async () => {
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([
      { id: 'u1', channel: 'MTN_MOMO', accountIdentifier: '0788111222', masked: false, label: null, active: true, createdAt: '2026-01-01T00:00:00Z', canDelete: true, deletionBlockedReason: null },
    ])
    vi.mocked(deletePaymentAccount).mockRejectedValue(backendError(409, 'PAYMENT_ACCOUNT_IN_USE'))
    renderWithApp(<PaymentAccountsPanel cooperativeId="c1" />, { user: OFFICER })
    await screen.findByText('0788111222')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Delete account 0788111222' }))
    await user.click(await screen.findByRole('button', { name: 'Delete Account' }))
    expect(await screen.findByTestId('payment-error')).toHaveTextContent(/cannot be deleted. Deactivate it instead/)
  })
})
