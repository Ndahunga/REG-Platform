import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveReconciliation,
  declineReconciliation,
  fetchAttention,
  fetchReconciliationCandidates,
  fetchReconciliations,
  fetchTrustedTransactions,
  importTrustedTransactions,
  requestReconciliation,
  withdrawReconciliation,
} from '@/shared/api/paymentVerification'
import type {
  AttentionItem,
  ReconciliationCandidate,
  ReconciliationRequest,
  TrustedTransaction,
} from '@/shared/types/paymentVerification'
import { backendError, renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { LegacyReconciliationPanel } from './LegacyReconciliationPanel'
import { TrustedTransactionsPanel } from './TrustedTransactionsPanel'

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
vi.mock('./PaymentDetailDialog', () => ({
  PaymentDetailDialog: ({ paymentId }: { paymentId: string }) => <div data-testid="payment-detail-stub">{paymentId}</div>,
}))
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({ children, onClick, disabled }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

const attentionMock = vi.mocked(fetchAttention)
const transactionsMock = vi.mocked(fetchTrustedTransactions)
const importMock = vi.mocked(importTrustedTransactions)
const candidatesMock = vi.mocked(fetchReconciliationCandidates)
const requestsMock = vi.mocked(fetchReconciliations)
const requestMock = vi.mocked(requestReconciliation)
const approveMock = vi.mocked(approveReconciliation)
const declineMock = vi.mocked(declineReconciliation)
const withdrawMock = vi.mocked(withdrawReconciliation)

const officer: TestUser = { id: 'officer', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_WRITE', 'LOAN_WRITE'] }
const authority: TestUser = { id: 'president', roles: ['PRESIDENT'], permissions: ['CONTRIBUTION_WRITE', 'FUND_AUTHORIZE'] }

const attentionOf = (extra: Partial<AttentionItem> = {}): AttentionItem => ({
  paymentId: 'p1',
  memberUserId: 'payer',
  amount: '1500',
  currency: 'RWF',
  paymentMethod: 'MTN_MOMO',
  transactionReference: 'TX1',
  paymentStatus: 'UNDER_REVIEW',
  outcome: 'CONFLICTED',
  reasons: ['AMOUNT_MISMATCH'],
  checkedAt: '2026-10-02T08:00:00Z',
  ...extra,
})

const transactionOf = (extra: Partial<TrustedTransaction> = {}): TrustedTransaction => ({
  id: 't1',
  source: 'MOBILE_MONEY_STATEMENT_IMPORT',
  channel: 'MTN_MOMO',
  reference: 'TX1',
  amount: '1500',
  currency: 'RWF',
  providerStatus: 'SUCCESS',
  transactedAt: null,
  sender: null,
  recipient: null,
  consumed: false,
  consumedByPaymentId: null,
  conflicted: false,
  conflictDetail: null,
  recordedAt: '2026-10-02T08:00:00Z',
  ...extra,
})

const candidateOf = (extra: Partial<ReconciliationCandidate> = {}): ReconciliationCandidate => ({
  contributionId: 'k1',
  memberUserId: 'payer',
  memberName: 'Pat Payer',
  year: 2026,
  month: 11,
  rotationPeriodId: 'rp1',
  originalRecipientUserId: 'rec1',
  originalRecipientName: 'Rita Recipient',
  paidAmount: '1000',
  verifiedAmount: '0',
  unreconciledAmount: '1000',
  unreconciledRotating: '500',
  openRequestId: null,
  ...extra,
})

const requestOf = (extra: Partial<ReconciliationRequest> = {}): ReconciliationRequest => ({
  id: 'r1',
  contributionId: 'k1',
  memberUserId: 'payer',
  periodYear: 2026,
  periodMonth: 11,
  rotationPeriodId: 'rp1',
  originalRecipientUserId: 'rec1',
  amount: '1000',
  status: 'REQUESTED',
  method: 'BANK_RECONCILED',
  basis: 'Statement line seen',
  reference: 'STMT-1',
  requestedBy: 'officer',
  requestedAt: '2026-10-02T08:00:00Z',
  decidedBy: null,
  decidedAt: null,
  decisionNote: null,
  withdrawnAt: null,
  ...extra,
})

describe('trusted transactions panel', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    attentionMock.mockResolvedValue([attentionOf()])
    transactionsMock.mockResolvedValue([transactionOf({ consumed: true }), transactionOf({ id: 't2', reference: 'TX2', conflicted: true })])
  })

  it('lists the payments needing attention with their outcome and reasons, and opens one', async () => {
    renderWithApp(<TrustedTransactionsPanel cooperativeId="c1" />, { user: officer })
    const row = (await screen.findByText('Conflicting evidence')).closest('tr') as HTMLElement
    expect(within(row).getByText('Amount does not match')).toBeInTheDocument()
    await userEvent.click(row)
    expect(await screen.findByTestId('payment-detail-stub')).toHaveTextContent('p1')
  })

  it('shows which recorded transactions were used or are in conflict', async () => {
    renderWithApp(<TrustedTransactionsPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByText('TX2')).toBeInTheDocument()
    expect(screen.getByText('Used')).toBeInTheDocument()
    expect(screen.getByText('Conflicted')).toBeInTheDocument()
  })

  it('validates a statement line before sending and records it with the exact values entered', async () => {
    importMock.mockResolvedValue({
      batchId: 'b1',
      results: [{ reference: 'TX9', result: 'CREATED', reason: null, transactionId: 't9' }],
    })
    renderWithApp(<TrustedTransactionsPanel cooperativeId="c1" />, { user: officer })
    const form = await screen.findByTestId('trusted-import-form')

    await userEvent.click(within(form).getByTestId('trusted-import-submit'))
    expect(importMock).not.toHaveBeenCalled()
    expect(within(form).getByText('A reference is required.')).toBeInTheDocument()
    expect(within(form).getByText('Enter a positive amount.')).toBeInTheDocument()

    await userEvent.type(within(form).getByLabelText('Reference'), ' TX9 ')
    await userEvent.type(within(form).getByLabelText('Amount'), '1500')
    await userEvent.click(within(form).getByTestId('trusted-import-submit'))
    await waitFor(() =>
      expect(importMock).toHaveBeenCalledWith('c1', [
        {
          source: 'MOBILE_MONEY_STATEMENT_IMPORT',
          channel: 'MTN_MOMO',
          reference: 'TX9',
          amount: 1500,
          currency: 'RWF',
          status: 'SUCCESS',
        },
      ]),
    )
    const result = await screen.findByTestId('trusted-import-result')
    expect(within(result).getByText('Recorded')).toBeInTheDocument()
    expect(screen.getByText(/never verifies or posts a payment/i)).toBeInTheDocument()
  })

  it('explains a refused line with the real backend code', async () => {
    importMock.mockRejectedValue(backendError(422, 'SOURCE_CHANNEL_MISMATCH'))
    renderWithApp(<TrustedTransactionsPanel cooperativeId="c1" />, { user: officer })
    const form = await screen.findByTestId('trusted-import-form')
    await userEvent.type(within(form).getByLabelText('Reference'), 'TX9')
    await userEvent.type(within(form).getByLabelText('Amount'), '10')
    await userEvent.click(within(form).getByTestId('trusted-import-submit'))
    expect(await screen.findByText('That statement type cannot record this method.')).toBeInTheDocument()
  })
})

describe('legacy rotating reconciliation panel', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    candidatesMock.mockResolvedValue([candidateOf()])
    requestsMock.mockResolvedValue([])
  })

  it('explains that approval creates no ledger entry and lists what can be reconciled', async () => {
    renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('legacy-explain')).toHaveTextContent(/no ledger entry is created and no balance changes/i)
    expect(await screen.findByText('Pat Payer')).toBeInTheDocument()
    expect(screen.getByText('Rita Recipient')).toBeInTheDocument()
  })

  it('needs a written basis and a reference before asking, then sends the exact request', async () => {
    requestMock.mockResolvedValue(requestOf())
    renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, { user: officer })
    await userEvent.click(await screen.findByRole('button', { name: 'Request reconciliation' }))
    const dialog = await screen.findByRole('dialog')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Request reconciliation' }))
    expect(requestMock).not.toHaveBeenCalled()
    expect(within(dialog).getByText('Describe what you relied on.')).toBeInTheDocument()
    expect(within(dialog).getByText('A reference is required.')).toBeInTheDocument()

    await userEvent.type(within(dialog).getByLabelText('Confirmation basis'), 'Statement line 1 seen')
    await userEvent.type(within(dialog).getByLabelText('Reference'), 'STMT-1')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Request reconciliation' }))
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith('c1', {
        contributionId: 'k1',
        amount: 1000,
        method: 'BANK_RECONCILED',
        basis: 'Statement line 1 seen',
        reference: 'STMT-1',
      }),
    )
  })

  it('does not offer a second request while one is open', async () => {
    candidatesMock.mockResolvedValue([candidateOf({ openRequestId: 'r1' })])
    renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByText('Request open')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request reconciliation' })).not.toBeInTheDocument()
  })

  it('lets an independent fund authority approve or decline, and never the requester', async () => {
    requestsMock.mockResolvedValue([requestOf()])
    approveMock.mockResolvedValue(requestOf({ status: 'APPROVED' }))
    declineMock.mockResolvedValue(requestOf({ status: 'DECLINED' }))

    // the requester, even holding fund authority, is told someone else must decide
    const own = renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, {
      user: { ...authority, id: 'officer' },
    })
    expect(await screen.findByTestId('legacy-independence')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    own.unmount()

    renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, { user: authority })
    const card = await screen.findByTestId('legacy-reconciliation-request')
    expect(within(card).getByText('Awaiting approval')).toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('c1', 'r1'))

    await userEvent.click(within(card).getByRole('button', { name: 'Decline' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Decline' }))
    expect(declineMock).not.toHaveBeenCalled()
    await userEvent.type(within(dialog).getByLabelText(/Reason for declining/), 'Statement does not show it')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Decline' }))
    await waitFor(() => expect(declineMock).toHaveBeenCalledWith('c1', 'r1', 'Statement does not show it'))
  })

  it('gives an officer without fund authority a withdraw option for their own request and an explanation otherwise', async () => {
    requestsMock.mockResolvedValue([requestOf(), requestOf({ id: 'r2', requestedBy: 'someone-else' })])
    withdrawMock.mockResolvedValue(requestOf({ status: 'WITHDRAWN' }))
    renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, { user: officer })
    const cards = await screen.findAllByTestId('legacy-reconciliation-request')
    expect(cards).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.getAllByText(/Only an officer with fund authority/).length).toBeGreaterThan(0)

    await userEvent.click(within(cards[0]).getByRole('button', { name: 'Withdraw request' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText(/Why are you withdrawing it/), 'Wrong line')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Withdraw request' }))
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledWith('c1', 'r1', 'Wrong line'))
  })

  it('shows the backend refusal of an approval in words', async () => {
    requestsMock.mockResolvedValue([requestOf()])
    approveMock.mockRejectedValue(backendError(422, 'RECONCILIATION_AMOUNT_EXCEEDS_UNVERIFIED'))
    renderWithApp(<LegacyReconciliationPanel cooperativeId="c1" />, { user: authority })
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('The amount is more than what is paid and not yet verified.')).toBeInTheDocument()
  })
})
