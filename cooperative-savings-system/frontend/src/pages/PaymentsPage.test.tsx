import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMyContributions } from '@/shared/api/contributions'
import { fetchMyLoans } from '@/shared/api/loans'
import { fetchCredits, fetchMyPayments, fetchPaymentAccounts, fetchPaymentReviewQueue, fetchPayments } from '@/shared/api/memberPayments'
import {
  fetchAttention,
  fetchReconciliationCandidates,
  fetchReconciliations,
  fetchTrustedTransactions,
} from '@/shared/api/paymentVerification'
import { ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import { pageOf, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { PaymentsPage, paymentTabsFor } from './PaymentsPage'

vi.mock('@/shared/api/contributions', () => ({ fetchMyContributions: vi.fn() }))
vi.mock('@/shared/api/loans', () => ({ fetchMyLoans: vi.fn() }))
vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))
vi.mock('@/shared/api/paymentVerification', () => ({
  fetchVerificationCapabilities: vi.fn().mockResolvedValue(null),
  fetchAttention: vi.fn(),
  fetchTrustedTransactions: vi.fn(),
  importTrustedTransactions: vi.fn(),
  fetchReconciliationCandidates: vi.fn(),
  fetchReconciliations: vi.fn(),
  requestReconciliation: vi.fn(),
  approveReconciliation: vi.fn(),
  declineReconciliation: vi.fn(),
  withdrawReconciliation: vi.fn(),
}))
vi.mock('@/shared/api/memberPayments', () => ({
  submitPayment: vi.fn(),
  analyzeReceipt: vi.fn(),
  fetchPaymentAccounts: vi.fn(),
  fetchCredits: vi.fn(),
  applyCredit: vi.fn(),
  addPaymentAccount: vi.fn(),
  deactivatePaymentAccount: vi.fn(),
  fetchMyPayments: vi.fn(),
  fetchPaymentReviewQueue: vi.fn(),
  fetchPayments: vi.fn(),
}))

const MEMBER: TestUser = { id: 'm', roles: ['MEMBER'], permissions: ['CONTRIBUTION_READ'] }
const OFFICER: TestUser = { id: 'o', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_READ', 'CONTRIBUTION_WRITE', 'LOAN_WRITE'] }
const SUPER: TestUser = { id: 's', roles: [ROLE_SUPER_ADMIN], permissions: [] }

describe('PaymentsPage', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMyContributions).mockResolvedValue(pageOf([]) as never)
    vi.mocked(fetchMyLoans).mockResolvedValue(pageOf([]) as never)
    vi.mocked(fetchMyPayments).mockResolvedValue(pageOf([]) as never)
    vi.mocked(fetchPaymentReviewQueue).mockResolvedValue(pageOf([]) as never)
    vi.mocked(fetchPayments).mockResolvedValue(pageOf([]) as never)
    vi.mocked(fetchPaymentAccounts).mockResolvedValue([])
    vi.mocked(fetchCredits).mockResolvedValue({ balances: [], totalHeld: '0' })
    vi.mocked(fetchAttention).mockResolvedValue([])
    vi.mocked(fetchTrustedTransactions).mockResolvedValue([])
    vi.mocked(fetchReconciliationCandidates).mockResolvedValue([])
    vi.mocked(fetchReconciliations).mockResolvedValue([])
  })

  it('computes the tabs each kind of user may see', () => {
    expect(paymentTabsFor({ canSubmit: true, isStaff: false })).toEqual(['submit', 'mine'])
    expect(paymentTabsFor({ canSubmit: true, isStaff: true })).toEqual(['submit', 'mine', 'review', 'verified', 'posted', 'all', 'reconciliation', 'summary', 'report'])
    expect(paymentTabsFor({ canSubmit: false, isStaff: true })).toEqual(['review', 'verified', 'posted', 'all', 'reconciliation', 'summary', 'report'])
    expect(paymentTabsFor({ canSubmit: false, isStaff: false })).toEqual([])
  })

  it('gives a member only the submit and own-payments tabs, and never calls officer endpoints', async () => {
    renderWithApp(<PaymentsPage />, { user: MEMBER })
    expect(await screen.findByRole('tab', { name: 'Submit payment' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'My payments' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Awaiting review' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Verified, not yet posted' })).not.toBeInTheDocument()
    expect(fetchPaymentReviewQueue).not.toHaveBeenCalled()
    expect(fetchPayments).not.toHaveBeenCalled()
  })

  it('gives an officer the review, verified, posted and all tabs as well', async () => {
    renderWithApp(<PaymentsPage />, { user: OFFICER })
    for (const name of ['Submit payment', 'My payments', 'Awaiting review', 'Verified, not yet posted', 'Posted', 'All payments', 'Reconciliation', 'Financial summary']) {
      expect(await screen.findByRole('tab', { name })).toBeInTheDocument()
    }
  })

  it('opens an officer tab from the URL and falls back safely for a tab the user may not see', async () => {
    renderWithApp(<PaymentsPage />, { user: OFFICER, route: '/payments?tab=verified' })
    expect(await screen.findByTestId('payment-list-verified')).toBeInTheDocument()
  })

  it('opens the reconciliation tab for officers and keeps it from members', async () => {
    const view = renderWithApp(<PaymentsPage />, { user: OFFICER, route: '/payments?tab=reconciliation' })
    expect(await screen.findByTestId('trusted-transactions-panel')).toBeInTheDocument()
    expect(screen.getByTestId('legacy-reconciliation-panel')).toBeInTheDocument()
    expect(fetchAttention).toHaveBeenCalledWith('c1')
    expect(fetchReconciliationCandidates).toHaveBeenCalledWith('c1')
    view.unmount()
    vi.mocked(fetchAttention).mockClear()

    renderWithApp(<PaymentsPage />, { user: MEMBER, route: '/payments?tab=reconciliation' })
    expect(await screen.findByTestId('payment-submit-panel')).toBeInTheDocument()
    expect(screen.queryByTestId('trusted-transactions-panel')).not.toBeInTheDocument()
    expect(fetchAttention).not.toHaveBeenCalled()
  })

  it('ignores an officer tab requested by a member', async () => {
    renderWithApp(<PaymentsPage />, { user: MEMBER, route: '/payments?tab=review' })
    expect(await screen.findByTestId('payment-submit-panel')).toBeInTheDocument()
    expect(fetchPaymentReviewQueue).not.toHaveBeenCalled()
  })

  it('gives Super Admin the officer views only: not a member, so no submission', async () => {
    renderWithApp(<PaymentsPage />, { user: SUPER })
    expect(await screen.findByRole('tab', { name: 'Awaiting review' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Submit payment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'My payments' })).not.toBeInTheDocument()
  })

  it('asks for a Saving Scheme and does not request anything without one', async () => {
    renderWithApp(<PaymentsPage />, { user: OFFICER, cooperativeId: null })
    expect(await screen.findByText('Choose a Saving Scheme')).toBeInTheDocument()
    expect(fetchMyPayments).not.toHaveBeenCalled()
    expect(fetchPaymentReviewQueue).not.toHaveBeenCalled()
  })

  it('scopes every request to the selected scheme', async () => {
    renderWithApp(<PaymentsPage />, { user: OFFICER, cooperativeId: 'scheme-9', route: '/payments?tab=review' })
    await screen.findByTestId('payment-list-review')
    expect(fetchPaymentReviewQueue).toHaveBeenCalledWith('scheme-9', expect.anything())
  })
})
