import { ThemeProvider } from '@mui/material'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { lightTheme } from '@/theme/theme'
import { CooperativeFormDialog } from './CooperativeFormDialog'

function renderCreate(onSubmit = vi.fn()) {
  return {
    onSubmit,
    ...render(
      <ThemeProvider theme={lightTheme}>
        <CooperativeFormDialog open mode="create" onClose={vi.fn()} onSubmit={onSubmit} />
      </ThemeProvider>,
    ),
  }
}

function fillRequiredFields() {
  fillTextFields()
  fireEvent.click(screen.getByRole('radio', { name: /Accumulating Savings/ }))
}

function fillTextFields() {
  fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Ubumwe Savings' } })
  fireEvent.change(screen.getByLabelText(/^Registration number/), {
    target: { value: 'RCA/2024/0123' },
  })
  fireEvent.change(screen.getByLabelText(/^Contact email/), { target: { value: 'info@ubumwe.rw' } })
  fireEvent.change(screen.getByLabelText(/^Contact phone/), { target: { value: '0781234567' } })
  fireEvent.change(screen.getByLabelText(/^Registration date/), { target: { value: '2024-01-15' } })
}

describe('CooperativeFormDialog onboarding', () => {
  it('defaults Start 4-month free trial to checked and shows the assign-later warning', () => {
    renderCreate()
    expect(screen.getByRole('checkbox', { name: /Start 4-month free trial/i })).toBeChecked()
    expect(
      screen.getByText(
        'This Saving Scheme will remain in incomplete onboarding until a President is assigned.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/Existing user ID/i)).not.toBeInTheDocument()
  })

  it('shows president fields when Assign President now is selected', async () => {
    const user = userEvent.setup()
    renderCreate()
    await user.click(screen.getByRole('radio', { name: /Assign President now/i }))
    expect(
      screen.queryByText(
        'This Saving Scheme will remain in incomplete onboarding until a President is assigned.',
      ),
    ).not.toBeInTheDocument()
    expect(screen.getByLabelText(/President source/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Username/i)).toBeInTheDocument()
  })

  it('submits START_TRIAL without president by default', async () => {
    const user = userEvent.setup({ delay: null })
    const { onSubmit } = renderCreate()
    fillRequiredFields()
    await user.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      name: 'Ubumwe Savings',
      subscriptionInitialization: 'START_TRIAL',
    })
    expect(onSubmit.mock.calls[0][0].president).toBeUndefined()
  })

  it('maps an unchecked trial to NONE', async () => {
    const user = userEvent.setup({ delay: null })
    const { onSubmit } = renderCreate()
    fillRequiredFields()
    await user.click(screen.getByRole('checkbox', { name: /Start 4-month free trial/i }))
    expect(screen.getByRole('checkbox', { name: /Start 4-month free trial/i })).not.toBeChecked()
    await user.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0].subscriptionInitialization).toBe('NONE')
  })

  it('offers the three operating models and selects none by default', () => {
    renderCreate()
    const radios = screen.getAllByRole('radio', { name: /Savings/ })
    expect(radios).toHaveLength(3)
    expect(screen.getByRole('radio', { name: /Accumulating Savings/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Rotating Savings/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Hybrid Savings/ })).not.toBeChecked()
    expect(screen.getByText('Saving Scheme Operating Model')).toBeInTheDocument()
  })

  it('requires a model: submitting without one shows the validation and sends nothing', async () => {
    const user = userEvent.setup({ delay: null })
    const { onSubmit } = renderCreate()
    fillTextFields()
    await user.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() =>
      expect(screen.getAllByText('Select a Saving Scheme operating model').length).toBeGreaterThan(0),
    )
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits the selected model', async () => {
    const user = userEvent.setup({ delay: null })
    const { onSubmit } = renderCreate()
    fillRequiredFields()
    fireEvent.click(screen.getByRole('radio', { name: /Rotating Savings/ }))
    await user.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0].operatingModel).toBe('ROTATING')
  })

  it('does not show the selector when editing an existing scheme', () => {
    render(
      <ThemeProvider theme={lightTheme}>
        <CooperativeFormDialog
          open
          mode="edit"
          initial={{
            id: 'c1',
            name: 'Legacy',
            currency: 'RWF',
            status: 'ACTIVE',
            financialYearStartMonth: 1,
            monthlyContributionAmount: '0',
            contributionDueDay: 1,
            operatingModel: null,
          }}
          onClose={vi.fn()}
          onSubmit={vi.fn()}
        />
      </ThemeProvider>,
    )
    expect(screen.queryByRole('radio', { name: /Savings/ })).not.toBeInTheDocument()
  })
})
