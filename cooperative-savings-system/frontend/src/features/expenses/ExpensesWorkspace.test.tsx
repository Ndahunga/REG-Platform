import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { approveExpense, fetchBankChargeLines, fetchExpenses, recordBankCharges, recordExpense } from '@/shared/api/expenses'
import { renderWithApp, type TestUser } from '@/test/paymentTestHarness'
import { ExpensesWorkspace } from './ExpensesWorkspace'

vi.mock('@/shared/api/expenses', async (orig) => ({
  ...(await orig<typeof import('@/shared/api/expenses')>()),
  fetchExpenses: vi.fn(),
  fetchBankChargeLines: vi.fn(),
  recordExpense: vi.fn(),
  recordBankCharges: vi.fn(),
  approveExpense: vi.fn().mockResolvedValue(undefined),
  rejectExpense: vi.fn(),
}))

const accountant: TestUser = { id: 'acc', roles: ['ACCOUNTANT'], permissions: ['INCOME_EXPENSE_READ', 'INCOME_EXPENSE_WRITE'] }

describe('ExpensesWorkspace', () => {
  it('asks for an explanation when the document shows another amount, records bank charges and approves', async () => {
    const user = userEvent.setup()
    vi.mocked(fetchExpenses).mockResolvedValue({
      canRecord: true,
      canApprove: true,
      pendingCount: 1,
      pendingAmount: 1500,
      approvedThisMonth: 0,
      byTypeThisYear: {},
      bankChargeLinesToRecord: 1,
      expenses: [
        { id: 'e1', expenseType: 'MEETING', amount: 1500, date: '2026-10-20', description: 'Committee meeting', status: 'PENDING',
          evidenceCheck: 'MATCH', evidenceKey: 'k', evidenceFileName: 'bill.pdf', mine: false },
      ],
    })
    vi.mocked(fetchBankChargeLines).mockResolvedValue([
      { lineId: 'l1', statementId: 's1', date: '2026-10-07', narration: 'PAYMENT CHARGE INCOME ENTRY', amount: 500, lineStatus: 'UNMATCHED' },
    ])
    vi.mocked(recordBankCharges).mockResolvedValue([])
    vi.mocked(recordExpense).mockRejectedValueOnce(
      new AxiosError('x', '422', undefined, undefined, {
        status: 422, statusText: '', headers: {}, config: { headers: new AxiosHeaders() },
        data: { code: 'EVIDENCE_AMOUNT_MISMATCH', message: 'The document shows 1,500, not 1,800.' },
      }),
    )
    renderWithApp(<ExpensesWorkspace cooperativeId="c1" />, { user: accountant })

    expect(await screen.findByText('Committee meeting')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Amount (RWF)'), { target: { value: '1800' } })
    fireEvent.change(screen.getByLabelText('What it was for'), { target: { value: 'Snacks for the meeting' } })
    const file = new File(['%PDF'], 'bill.pdf', { type: 'application/pdf' })
    fireEvent.change(document.querySelector('input[type="file"]') as HTMLInputElement, { target: { files: [file] } })
    await user.click(screen.getByRole('button', { name: 'Record expense' }))
    expect(await screen.findByTestId('expense-mismatch')).toHaveTextContent('The document shows 1,500, not 1,800.')

    await user.click(await screen.findByText('PAYMENT CHARGE INCOME ENTRY'))
    await user.click(screen.getByRole('button', { name: /Record 1 charge/ }))
    await waitFor(() => expect(recordBankCharges).toHaveBeenCalledWith('c1', ['l1']))

    await user.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approveExpense).toHaveBeenCalledWith('c1', 'e1'))
  })
})
