import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchModelReport } from '@/shared/api/historicalTransactions'
import type { ModelReport } from '@/shared/types/historicalTransactions'
import { renderWithApp } from '@/test/paymentTestHarness'
import { ModelReportPanel } from './ModelReportPanel'

vi.mock('@/shared/api/historicalTransactions', () => ({ fetchModelReport: vi.fn() }))

const reportMock = vi.mocked(fetchModelReport)
const officer = { id: 'o', roles: ['ACCOUNTANT'], permissions: ['REPORT_READ'] }

const base = (extra: Partial<ModelReport> = {}): ModelReport => ({
  cooperativeId: 'c1',
  currency: 'RWF',
  operatingModel: 'ACCUMULATING',
  from: '2000-01-01',
  to: '2026-10-17',
  generatedAt: '2026-10-17T10:00:00Z',
  position: {
    expectedContributions: '5000',
    postedCollections: '3000',
    contributionArrears: '200',
    outstandingContributions: '2000',
    retainedCollections: '3000',
    unclassifiedCollections: '0',
    unresolvedRotatingCollections: '0',
    rotatingCollected: '0',
    rotatingRequiringReconciliation: '0',
    rotatingExpected: '0',
    rotatingArrears: '0',
    recipientEntitlementsOwed: '0',
    totalRotatingLiabilities: '0',
    payoutsDisbursed: '0',
    payoutsOpen: '0',
    availableFunds: '3300',
    availableAfterEntitlements: '3300',
  },
  movements: { contributionsCollected: '3000', loansDisbursed: '1000', principalRepaid: '400', memberPayouts: '0' },
  loans: { outstandingPrincipal: '600', unpaidInterest: '50' },
  shares: { totalShares: 12, members: 6, holders: [] },
  earnings: {
    interestIncome: '300',
    fineIncome: '100',
    investmentProfit: '0',
    otherIncome: '50',
    totalIncome: '450',
    operatingExpenses: '120',
    interestExpense: '0',
    totalExpenses: '120',
    netEarnings: '330',
    distributionStatus: 'UNAVAILABLE_PENDING_POLICY',
  },
  rotation: null,
  hybrid: null,
  notes: ['Earnings are income less expenses of the period.'],
  ...extra,
})

const rotationBlock = {
  periods: [
    {
      periodId: 'p1',
      year: 2026,
      month: 11,
      recipientName: 'Rita Recipient',
      expectedRotating: '1000',
      collectedRotating: '500',
      unreconciledRotating: '100',
      rotatingArrears: '400',
      distributed: '200',
      outstandingEntitlement: '300',
    },
  ],
  verifiedCollected: '500',
  unreconciled: '100',
  unresolvedRotating: '0',
  unpaidEntitlements: '300',
  paidOut: '200',
}

describe('ModelReportPanel', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reports an accumulating scheme in its own terms and always marks distribution unavailable', async () => {
    reportMock.mockResolvedValue(base())
    renderWithApp(<ModelReportPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('model-report')).toBeInTheDocument()
    expect(reportMock).toHaveBeenCalledWith('c1', undefined, undefined)
    expect(screen.getByTestId('mr-model')).toHaveTextContent('Accumulating Savings')
    for (const id of ['mr-expected', 'mr-collected', 'mr-retained', 'mr-shares', 'mr-disbursed', 'mr-outstanding', 'mr-income', 'mr-net']) {
      expect(screen.getByTestId(id)).toBeInTheDocument()
    }
    expect(screen.getByTestId('mr-net')).toHaveTextContent(/330/)
    expect(screen.getByTestId('mr-shares').textContent).toBe('12') // a count of shares, not an amount of money
    expect(screen.getByTestId('mr-distribution')).toHaveTextContent(/unavailable until the scheme approves a distribution policy/)
    expect(screen.queryByTestId('mr-rotation')).not.toBeInTheDocument()
    expect(screen.queryByTestId('mr-hybrid')).not.toBeInTheDocument()
    expect(screen.queryByTestId('mr-unresolved')).not.toBeInTheDocument()
  })

  it('reports a rotating scheme with its recipients and verified, unreconciled and unresolved money apart', async () => {
    reportMock.mockResolvedValue(
      base({
        operatingModel: 'ROTATING',
        rotation: { ...rotationBlock, unresolvedRotating: '2000' },
        position: { ...base().position, unresolvedRotatingCollections: '2000', totalRotatingLiabilities: '2400' },
      }),
    )
    renderWithApp(<ModelReportPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('mr-rotation')).toBeInTheDocument()
    expect(screen.getByTestId('mr-rot-verified')).toHaveTextContent(/500/)
    expect(screen.getByTestId('mr-rot-unreconciled')).toHaveTextContent(/100/)
    expect(screen.getByTestId('mr-rot-unresolved')).toHaveTextContent(/2,?000/)
    expect(screen.getByTestId('mr-rot-unpaid')).toHaveTextContent(/300/)
    const recipients = screen.getByTestId('mr-recipients')
    expect(within(recipients).getByText('Rita Recipient')).toBeInTheDocument()
    expect(within(recipients).getByText('2026-11')).toBeInTheDocument()
    expect(screen.getByTestId('mr-unresolved')).toHaveTextContent(/never member capital or payable to a recipient/)
    expect(screen.queryByTestId('mr-hybrid')).not.toBeInTheDocument()
  })

  it('shows a hybrid scheme with the retained and rotating portions and only an indicative earnings attribution', async () => {
    reportMock.mockResolvedValue(
      base({
        operatingModel: 'HYBRID',
        rotation: rotationBlock,
        hybrid: {
          retainedCapital: '3000',
          rotatingHeld: '1000',
          retainedCapitalShareBps: 7500,
          earningsAttributableToRetained: '247.5',
          attributionBasis: 'PROPORTIONAL_TO_CAPITAL_INDICATIVE_ONLY',
        },
      }),
    )
    renderWithApp(<ModelReportPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('mr-hybrid')).toBeInTheDocument()
    expect(screen.getByTestId('mr-hyb-retained')).toHaveTextContent(/3,?000/)
    expect(screen.getByTestId('mr-hyb-held')).toHaveTextContent(/1,?000/)
    expect(screen.getByText(/Earnings attributable to retained capital \(indicative\)/)).toBeInTheDocument()
    expect(screen.getByText(/An indication only/)).toBeInTheDocument()
    expect(screen.getByTestId('mr-distribution')).toBeInTheDocument()
  })

  it('applies a period to the report and refuses an inverted range', async () => {
    reportMock.mockResolvedValue(base())
    renderWithApp(<ModelReportPanel cooperativeId="c1" />, { user: officer })
    await screen.findByTestId('model-report')
    await userEvent.type(screen.getByLabelText('From'), '2024-01-01')
    await userEvent.type(screen.getByLabelText('To'), '2024-12-31')
    await userEvent.click(screen.getByTestId('mr-apply'))
    await waitFor(() => expect(reportMock).toHaveBeenCalledWith('c1', '2024-01-01', '2024-12-31'))

    await userEvent.clear(screen.getByLabelText('To'))
    await userEvent.type(screen.getByLabelText('To'), '2023-01-01')
    expect(screen.getByText('The start date must not be after the end date.')).toBeInTheDocument()
    expect(screen.getByTestId('mr-apply')).toBeDisabled()
  })

  it('shows an error state with retry', async () => {
    reportMock.mockRejectedValue(new Error('boom'))
    renderWithApp(<ModelReportPanel cooperativeId="c1" />, { user: officer })
    expect(await screen.findByRole('button', { name: /retry|try again/i })).toBeInTheDocument()
  })
})
