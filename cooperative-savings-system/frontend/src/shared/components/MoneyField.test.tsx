import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describe, expect, it, vi } from 'vitest'
import { caretAfterGrouping, groupedMoney, plainMoney } from '@/shared/utils/moneyInput'
import { MoneyField } from './MoneyField'

describe('money input helpers', () => {
  it('groups thousands and strips them back', () => {
    expect(groupedMoney('530000')).toBe('530,000')
    expect(groupedMoney('1234567.50')).toBe('1,234,567.50')
    expect(groupedMoney('')).toBe('')
    expect(plainMoney('530,000.5')).toBe('530000.5')
    expect(plainMoney('1.2.3')).toBe('1.23')
    expect(caretAfterGrouping('5300', 4, '5,300')).toBe(5)
  })
})

function FormHarness({ onSubmit, initial = '' }: { onSubmit: (v: { amount: string }) => void; initial?: string }) {
  const { register, handleSubmit } = useForm<{ amount: string }>({ defaultValues: { amount: initial } })
  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <MoneyField label="Amount" {...register('amount')} />
      <button type="submit">Save</button>
    </form>
  )
}

function ControlledHarness({ onValue }: { onValue: (v: string) => void }) {
  const [v, setV] = useState('')
  return (
    <MoneyField
      label="Amount"
      value={v}
      onChange={(e) => {
        setV(e.target.value)
        onValue(e.target.value)
      }}
    />
  )
}

describe('MoneyField', () => {
  it('shows separators but the form receives the plain number', async () => {
    const submit = vi.fn()
    render(<FormHarness onSubmit={submit} initial="1500" />)
    const input = screen.getByLabelText('Amount') as HTMLInputElement
    expect(input.value).toBe('1,500')
    await userEvent.clear(input)
    await userEvent.type(input, '530000')
    expect(input.value).toBe('530,000')
    await userEvent.click(screen.getByText('Save'))
    expect(submit.mock.calls[0][0]).toEqual({ amount: '530000' })
  })

  it('controlled: the owner keeps the plain number', async () => {
    const seen = vi.fn()
    render(<ControlledHarness onValue={seen} />)
    const input = screen.getByLabelText('Amount') as HTMLInputElement
    await userEvent.type(input, '1234567')
    expect(input.value).toBe('1,234,567')
    expect(seen).toHaveBeenLastCalledWith('1234567')
  })
})
