import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchContributionPeriodPreview,
  submitRegularContribution,
} from '@/shared/api/contributions'
import { kigaliDay } from '@/test/schemaHelpers'
import { MemberContributionSubmitPanel } from './MemberContributionSubmitPanel'

vi.mock('@/shared/api/contributions', () => ({
  fetchContributionPeriodPreview: vi.fn(),
  submitRegularContribution: vi.fn(),
}))
vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <SnackbarProvider>
        <MemberContributionSubmitPanel cooperativeId="coop-1" />
      </SnackbarProvider>
    </QueryClientProvider>,
  )
}

const amount = () => screen.getByLabelText('Amount paid now')
const date = () => screen.getByLabelText('Payment date')
const submit = () => screen.getByRole('button', { name: 'Submit Regular Contribution' })

describe('MemberContributionSubmitPanel validation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchContributionPeriodPreview).mockResolvedValue({
      year: 2026,
      month: 5,
      shareCount: 3,
      requiredAmount: '3000',
      paidAmount: '0',
      remainingAmount: '3000',
      status: 'PENDING',
      canSubmit: true,
    } as never)
    vi.mocked(submitRegularContribution).mockResolvedValue(undefined as never)
  })

  async function ready() {
    renderPanel()
    // wait until the preview (remaining amount) has loaded: the required amount is shown
    await screen.findByText(/Required amount/)
  }

  it.each([
    ['0', 'Enter a valid amount'],
    ['0.001', 'Enter a valid amount'],
    ['-5', 'Enter a valid amount'],
    ['abc', 'Enter a valid amount'],
    ['1e3', 'Enter a valid amount'],
    ['1.23456', 'Enter a valid amount'],
  ])('rejects the amount "%s" and makes no API call', async (value, message) => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amount(), { target: { value } })
    await user.click(submit())
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(submitRegularContribution).not.toHaveBeenCalled()
  })

  it('rejects a missing amount', async () => {
    const user = userEvent.setup()
    await ready()
    await user.click(submit())
    expect(await screen.findByText('Amount is required')).toBeInTheDocument()
    expect(submitRegularContribution).not.toHaveBeenCalled()
  })

  it('rejects an amount above the remaining amount, as the backend does', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amount(), { target: { value: '3500' } })
    await user.click(submit())
    expect(await screen.findByText(/cannot exceed the remaining amount/i)).toBeInTheDocument()
    expect(submitRegularContribution).not.toHaveBeenCalled()
  })

  it('rejects a future payment date and a cleared date', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amount(), { target: { value: '1000' } })
    fireEvent.change(date(), { target: { value: kigaliDay(3) } })
    await user.click(submit())
    expect(await screen.findByText('Payment date cannot be in the future.')).toBeInTheDocument()

    fireEvent.change(date(), { target: { value: '' } })
    await user.click(submit())
    expect(await screen.findByText('Date is required')).toBeInTheDocument()
    expect(submitRegularContribution).not.toHaveBeenCalled()
  })

  it('shows over-long reference and notes', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amount(), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText('Reference'), { target: { value: 'r'.repeat(129) } })
    fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'n'.repeat(2001) } })
    await user.click(submit())
    expect(await screen.findByText('Reference must be 128 characters or fewer.')).toBeInTheDocument()
    expect(screen.getByText('Notes must be 2000 characters or fewer.')).toBeInTheDocument()
    expect(submitRegularContribution).not.toHaveBeenCalled()
  })

  it('submits exactly once for a valid contribution up to the remaining amount', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amount(), { target: { value: '3000' } })
    await user.click(submit())
    await waitFor(() => expect(submitRegularContribution).toHaveBeenCalledTimes(1))
    expect(vi.mocked(submitRegularContribution).mock.calls[0][1]).toMatchObject({
      amount: '3000',
      paymentDate: kigaliDay(0),
    })
  })

  it('does not allow a second submit while the first is pending', async () => {
    const user = userEvent.setup()
    vi.mocked(submitRegularContribution).mockReturnValue(new Promise(() => {}))
    await ready()
    fireEvent.change(amount(), { target: { value: '1000' } })
    await user.click(submit())
    await waitFor(() => expect(submit()).toBeDisabled())
    fireEvent.submit(submit().closest('form')!)
    expect(submitRegularContribution).toHaveBeenCalledTimes(1)
  })
})
