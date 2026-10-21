import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { isValidRegistrationNumber } from '@/shared/utils/rwandaCooperative'
import { allMessages, firstMessage, kigaliDay } from '@/test/schemaHelpers'
import { AssignPresidentDialog } from './AssignPresidentDialog'
import { cooperativeFormSchema } from './cooperativeFormSchema'
import { findPresidentProblem } from './presidentValidation'

const UUID = '3f2b8c1e-5d4a-4b7e-9a10-2c6d8e4f7a91'

describe('registration number', () => {
  it('allows only "/" and "-" as separators, like the backend (not a backslash)', () => {
    expect(isValidRegistrationNumber('RCA/2020/1234')).toBe(true)
    expect(isValidRegistrationNumber('RCA-2020-1234')).toBe(true)
    expect(isValidRegistrationNumber('RCA\\2020\\1234')).toBe(false)
    expect(isValidRegistrationNumber('AB\\1234')).toBe(false)
  })
})

describe('cooperativeFormSchema', () => {
  const valid = {
    name: 'Abahuje',
    description: '',
    registrationNumber: 'RCA/2020/1234',
    contactEmail: 'info@abahuje.rw',
    contactPhone: '0781234567',
    address: '',
    currency: 'RWF',
    financialYearStartMonth: 1,
    monthlyContributionAmount: '5000',
    contributionDueDay: 5,
    registrationDate: kigaliDay(-30),
    startTrial: true,
    assignPresidentNow: false,
    presidentMode: 'new' as const,
    presidentUserId: '',
    presidentUsername: '',
    presidentEmail: '',
    presidentFirstName: '',
    presidentLastName: '',
    presidentPhone: '',
    presidentTemporaryPassword: '',
  }

  it('accepts a valid cooperative', async () => {
    expect(await firstMessage(cooperativeFormSchema, valid)).toBeNull()
  })

  it('caps the monthly contribution at 15 whole digits and 4 decimals (NUMERIC(19,4))', async () => {
    const message = 'Enter a valid amount (up to 15 digits and 4 decimals)'
    expect(await firstMessage(cooperativeFormSchema, { ...valid, monthlyContributionAmount: '1000000000000000' })).toBe(message)
    expect(await firstMessage(cooperativeFormSchema, { ...valid, monthlyContributionAmount: '1.23456' })).toBe(message)
    expect(await firstMessage(cooperativeFormSchema, { ...valid, monthlyContributionAmount: '-1' })).toBe(message)
    expect(await firstMessage(cooperativeFormSchema, { ...valid, monthlyContributionAmount: '999999999999999.9999' })).toBeNull()
    expect(await firstMessage(cooperativeFormSchema, { ...valid, monthlyContributionAmount: '0' })).toBeNull()
  })

  it('gives readable messages for over-long name, description and address', async () => {
    expect(await firstMessage(cooperativeFormSchema, { ...valid, address: 'a'.repeat(513) })).toBe(
      'Address must be 512 characters or fewer',
    )
    expect(await firstMessage(cooperativeFormSchema, { ...valid, name: 'n'.repeat(256) })).toBe(
      'Name must be 255 characters or fewer',
    )
    expect(await firstMessage(cooperativeFormSchema, { ...valid, description: 'd'.repeat(2001) })).toBe(
      'Description must be 2000 characters or fewer',
    )
  })

  it('validates the optional President only when it is being assigned', async () => {
    const bad = { ...valid, presidentUsername: 'x'.repeat(65), presidentTemporaryPassword: 'short' }
    expect(await firstMessage(cooperativeFormSchema, bad)).toBeNull()
    expect(await allMessages(cooperativeFormSchema, { ...bad, assignPresidentNow: true })).toContain(
      'Username must be 64 characters or fewer',
    )
  })

  it('rejects an existing-user President whose ID is not a UUID', async () => {
    const existing = { ...valid, assignPresidentNow: true, presidentMode: 'existing' as const }
    expect(await allMessages(cooperativeFormSchema, { ...existing, presidentUserId: '12345' })).toContain(
      'The user ID must be a valid UUID',
    )
    expect(await firstMessage(cooperativeFormSchema, { ...existing, presidentUserId: UUID })).toBeNull()
  })
})

describe('findPresidentProblem', () => {
  const person = {
    mode: 'new' as const,
    userId: '',
    username: 'pat.president',
    email: 'pat@example.com',
    firstName: 'Pat',
    lastName: 'President',
    phone: '',
    temporaryPassword: '',
  }

  it('accepts a complete new President', () => {
    expect(findPresidentProblem(person)).toBeNull()
    expect(findPresidentProblem({ ...person, temporaryPassword: 'longenough1' })).toBeNull()
  })

  it.each([
    [{ username: '' }, 'username'],
    [{ username: 'u'.repeat(65) }, 'username'],
    [{ email: 'nope' }, 'email'],
    [{ email: `${'a'.repeat(250)}@example.com` }, 'email'],
    [{ firstName: '' }, 'firstName'],
    [{ firstName: 'f'.repeat(129) }, 'firstName'],
    [{ lastName: '' }, 'lastName'],
    [{ phone: '1'.repeat(33) }, 'phone'],
    [{ temporaryPassword: 'short' }, 'temporaryPassword'],
    [{ temporaryPassword: 'x'.repeat(129) }, 'temporaryPassword'],
  ])('flags %j on %s', (overrides, field) => {
    expect(findPresidentProblem({ ...person, ...overrides })?.field).toBe(field)
  })

  it('does not alter or trim the temporary password when measuring it', () => {
    expect(findPresidentProblem({ ...person, temporaryPassword: '       ' })?.field).toBe('temporaryPassword')
  })
})

describe('AssignPresidentDialog', () => {
  const setup = () => {
    const onSubmit = vi.fn()
    render(<AssignPresidentDialog open onClose={() => {}} onSubmit={onSubmit} />)
    return { onSubmit }
  }
  const assign = () => screen.getAllByRole('button', { name: /assign/i }).at(-1)!

  it('does not submit an empty form and says what is missing', async () => {
    const user = userEvent.setup()
    const { onSubmit } = setup()
    await user.click(assign())
    expect(await screen.findByText('Username is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('shows an over-long phone and a too-short temporary password', async () => {
    const user = userEvent.setup()
    const { onSubmit } = setup()
    fireEvent.change(await screen.findByLabelText(/first name/i), { target: { value: 'Pat' } })
    fireEvent.change(screen.getByLabelText(/last name/i), { target: { value: 'President' } })
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'pat.p' } })
    fireEvent.change(screen.getByLabelText(/^email/i), { target: { value: 'pat@example.com' } })
    fireEvent.change(screen.getByLabelText(/phone/i), { target: { value: '1'.repeat(33) } })
    await user.click(assign())
    expect(await screen.findByText('Phone must be 32 characters or fewer')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/phone/i), { target: { value: '0781234567' } })
    fireEvent.change(screen.getByLabelText(/temporary password/i), { target: { value: 'short' } })
    await user.click(assign())
    expect(await screen.findByText('Temporary password must be at least 8 characters')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits exactly once for a valid President and does not trim the password', async () => {
    const user = userEvent.setup()
    const { onSubmit } = setup()
    fireEvent.change(await screen.findByLabelText(/first name/i), { target: { value: 'Pat' } })
    fireEvent.change(screen.getByLabelText(/last name/i), { target: { value: 'President' } })
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'pat.p' } })
    fireEvent.change(screen.getByLabelText(/^email/i), { target: { value: 'Pat@Example.com' } })
    fireEvent.change(screen.getByLabelText(/temporary password/i), { target: { value: ' pass word1 ' } })
    await user.click(assign())
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      username: 'pat.p',
      email: 'pat@example.com',
      temporaryPassword: ' pass word1 ',
    })
  })
})
