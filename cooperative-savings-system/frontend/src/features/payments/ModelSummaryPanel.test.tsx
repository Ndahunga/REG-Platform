import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchModelSummary } from '@/shared/api/rotationPayouts'
import type { ModelSummary } from '@/shared/types/rotationPayout'
import { renderWithApp } from '@/test/paymentTestHarness'
import { ModelSummaryPanel } from './ModelSummaryPanel'

vi.mock('@/shared/api/rotationPayouts', () => ({ fetchModelSummary: vi.fn() }))

const summaryMock = vi.mocked(fetchModelSummary)
const officer = { id: 'o', roles: ['ACCOUNTANT'], permissions: ['REPORT_READ'] }

const base: ModelSummary = {
  cooperativeId: 'c1',
  currency: 'RWF',
  operatingModel: 'HYBRID',
  expectedContributions: '2000',
  postedCollections: '1500',
  contributionArrears: '300',
  outstandingContributions: '500',
  retainedCollections: '750',
  unclassifiedCollections: '100',
  unresolvedRotatingCollections: '300',
  rotatingCollected: '750',
  rotatingRequiringReconciliation: '200',
  rotatingExpected: '1000',
  rotatingArrears: '250',
  recipientEntitlementsOwed: '450',
  totalRotatingLiabilities: '650',
  payoutsDisbursed: '300',
  payoutsOpen: '0',
  availableFunds: '1200',
  availableAfterEntitlements: '750',
}

describe('ModelSummaryPanel', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('separates expected, collected, retained, rotating, owed and paid-out money for a hybrid scheme', async () => {
    summaryMock.mockResolvedValue(base)
    renderWithApp(<ModelSummaryPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('model-summary')).toBeInTheDocument()
    expect(summaryMock).toHaveBeenCalledWith('c1')
    expect(screen.getByText('Hybrid Savings')).toBeInTheDocument()
    expect(screen.getByText('Not cash')).toBeInTheDocument()
    for (const id of [
      'summary-expected',
      'summary-collected',
      'summary-arrears',
      'summary-retained',
      'summary-rotating',
      'summary-reconciliation',
      'summary-rotating-arrears',
      'summary-owed',
      'summary-total-liabilities',
      'summary-unresolved-rotating',
      'summary-unclassified',
      'summary-paid-out',
      'summary-available',
      'summary-available-after',
    ]) {
      expect(screen.getByTestId(id)).toBeInTheDocument()
    }
    expect(screen.getByTestId('summary-owed')).toHaveTextContent(/450/)
    expect(screen.getByText('A liability, not member capital')).toBeInTheDocument()
    expect(screen.getByTestId('summary-reconciliation')).toHaveTextContent(/200/)
    expect(screen.getByText('Recorded without verification; not owed or payable yet')).toBeInTheDocument()
    // verified owed (450) and unreconciled (200) are set aside together, each rupee once
    expect(screen.getByTestId('summary-total-liabilities')).toHaveTextContent(/650/)
    expect(screen.getByText('Owed on verified money plus rotating money not yet reconciled')).toBeInTheDocument()
    // history that cannot be classified is reported openly, never folded into retained or owed money
    expect(screen.getByTestId('summary-unclassified')).toHaveTextContent(/100/)
    // known-rotating money with an unresolved period is held, never retained capital, and says so
    expect(screen.getByTestId('summary-unresolved-rotating')).toHaveTextContent(/300/)
    expect(screen.getByTestId('summary-unresolved-alert')).toHaveTextContent(/never assigned to retained capital or to a recipient/)
  })

  it('shows no rotating figures for an accumulating scheme', async () => {
    summaryMock.mockResolvedValue({
      ...base,
      operatingModel: 'ACCUMULATING',
      rotatingCollected: '0',
      rotatingRequiringReconciliation: '0',
      rotatingExpected: '0',
      rotatingArrears: '0',
      recipientEntitlementsOwed: '0',
      totalRotatingLiabilities: '0',
      unclassifiedCollections: '0',
      unresolvedRotatingCollections: '0',
      payoutsDisbursed: '0',
      retainedCollections: '1500',
    })
    renderWithApp(<ModelSummaryPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByText('Accumulating Savings')).toBeInTheDocument()
    expect(screen.getByTestId('summary-retained')).toBeInTheDocument()
    expect(screen.queryByTestId('summary-rotating')).not.toBeInTheDocument()
    expect(screen.queryByTestId('summary-reconciliation')).not.toBeInTheDocument()
    expect(screen.queryByTestId('summary-owed')).not.toBeInTheDocument()
    expect(screen.queryByTestId('summary-total-liabilities')).not.toBeInTheDocument()
    expect(screen.queryByTestId('summary-unclassified')).not.toBeInTheDocument()
    expect(screen.queryByTestId('summary-unresolved-alert')).not.toBeInTheDocument()
    expect(screen.queryByTestId('summary-available-after')).not.toBeInTheDocument()
  })

  it('shows an error state', async () => {
    summaryMock.mockRejectedValue(new Error('boom'))
    renderWithApp(<ModelSummaryPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByRole('button', { name: /retry|try again/i })).toBeInTheDocument()
  })
})
