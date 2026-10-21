import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { RepaymentDialog } from './RepaymentDialog'

function renderDialog(props: Partial<React.ComponentProps<typeof RepaymentDialog>> = {}) {
  const onSubmit = vi.fn()
  render(<RepaymentDialog open onClose={() => {}} onSubmit={onSubmit} {...props} />)
  return { onSubmit }
}

const amount = () => screen.getByLabelText(/amount/i)
const date = () => screen.getByLabelText(/payment date/i)
const submit = () => screen.getByRole('button', { name: /record|submit|save/i })

describe('RepaymentDialog validation', () => {
  it('defaults the payment date to today so a plain repayment is valid', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()
    await waitFor(() => expect(date()).toHaveValue(todayInKigaliIso()))
    await user.type(amount(), '5000')
    await user.click(submit())
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ amount: '5000', paymentDate: todayInKigaliIso() })
  })

  it('does not submit a missing amount, and shows why', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()
    await user.click(submit())
    expect(await screen.findByText('Amount is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('does not submit a cleared payment date, and shows why', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()
    await user.type(amount(), '5000')
    fireEvent.change(date(), { target: { value: '' } })
    await user.click(submit())
    expect(await screen.findByText('Payment date is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('does not submit a future payment date', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()
    await user.type(amount(), '5000')
    fireEvent.change(date(), { target: { value: '2999-01-01' } })
    await user.click(submit())
    expect(await screen.findByText('Payment date cannot be in the future')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('does not submit an invalid amount and keeps what was typed', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()
    await user.type(amount(), '0')
    await user.click(submit())
    expect(await screen.findByText('Amount must be greater than 0')).toBeInTheDocument()
    expect(amount()).toHaveValue('0')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('shows an over-long reference instead of silently blocking', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()
    await user.type(amount(), '5000')
    fireEvent.change(screen.getByLabelText(/reference/i), { target: { value: 'r'.repeat(129) } })
    await user.click(submit())
    expect(await screen.findByText(/at most 128/i)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('disables the submit button while a repayment is pending', () => {
    renderDialog({ loading: true })
    expect(submit()).toBeDisabled()
  })
})
