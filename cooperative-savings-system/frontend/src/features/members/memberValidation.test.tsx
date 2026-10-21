import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ROLE_PRESIDENT } from '@/shared/types/auth'
import type { Member } from '@/shared/types/member'
import { firstMessage, kigaliDay } from '@/test/schemaHelpers'
import { lightTheme } from '@/theme/theme'
import { MemberFormDialog } from './MemberFormDialog'
import {
  memberCreateSchema,
  memberFormDefaults,
  memberUpdateSchema,
  toMemberUpdatePayload,
} from './memberFormSchema'

describe('member schemas', () => {
  const create = {
    ...memberFormDefaults,
    firstName: 'Alice',
    lastName: 'Uwase',
    username: 'alice.u',
    email: 'alice@example.com',
  }

  it('accepts a valid new member', async () => {
    expect(await firstMessage(memberCreateSchema, create)).toBeNull()
  })

  it('limits email to 255 characters in both create and update', async () => {
    const email = `${'a'.repeat(250)}@example.com`
    expect(await firstMessage(memberCreateSchema, { ...create, email })).toBe('Email must be 255 characters or fewer')
    expect(await firstMessage(memberUpdateSchema, { ...create, email })).toBe('Email must be 255 characters or fewer')
  })

  it('shows readable messages for phone, address and temporary password limits', async () => {
    expect(await firstMessage(memberCreateSchema, { ...create, phone: '1'.repeat(33) })).toBe(
      'Phone must be 32 characters or fewer',
    )
    expect(await firstMessage(memberCreateSchema, { ...create, address: 'a'.repeat(513) })).toBe(
      'Address must be 512 characters or fewer',
    )
    expect(await firstMessage(memberCreateSchema, { ...create, temporaryPassword: 'short' })).toBe(
      'At least 8 characters if provided',
    )
    expect(await firstMessage(memberCreateSchema, { ...create, temporaryPassword: 'x'.repeat(129) })).toBe(
      'At most 128 characters',
    )
  })

  it('rejects a future membership date measured in Kigali time', async () => {
    expect(await firstMessage(memberCreateSchema, { ...create, membershipDate: kigaliDay(1) })).toBe(
      'Membership date cannot be in the future',
    )
    expect(await firstMessage(memberCreateSchema, { ...create, membershipDate: kigaliDay(0) })).toBeNull()
  })

  it('does not apply the create-only share count rule when editing', async () => {
    const edit = { ...create, shareCount: '1500' }
    expect(await firstMessage(memberCreateSchema, edit)).toBe('Share count must be between 0 and 1000')
    expect(await firstMessage(memberUpdateSchema, edit)).toBeNull()
  })

  it('omits an unchanged role from the update payload and sends a changed one', () => {
    const values = { ...create, roleInCooperative: 'MEMBER' as const }
    expect(toMemberUpdatePayload(values, 'MEMBER').roleInCooperative).toBeUndefined()
    expect(toMemberUpdatePayload(values, 'SECRETARY').roleInCooperative).toBe('MEMBER')
    expect(toMemberUpdatePayload(values).roleInCooperative).toBe('MEMBER')
  })
})

const existing = {
  userId: 'u-1',
  firstName: 'Alice',
  lastName: 'Uwase',
  username: 'alice.u',
  email: 'alice@example.com',
  phone: '0781234567',
  roleInCooperative: 'MEMBER',
  shareCount: 1500,
} as unknown as Member

function renderEdit() {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'p1',
          username: 'pat',
          email: 'pat@test.local',
          firstName: 'Pat',
          lastName: 'President',
          fullName: 'Pat President',
          roles: [ROLE_PRESIDENT],
          permissions: ['MEMBERSHIP_MANAGE'],
          cooperativeIds: ['coop-1'],
        },
        accessToken: 't',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const onUpdate = vi.fn()
  render(
    <Provider store={store}>
      <ThemeProvider theme={lightTheme}>
        <MemberFormDialog open mode="edit" initial={existing} onClose={() => {}} onUpdate={onUpdate} />
      </ThemeProvider>
    </Provider>,
  )
  return { onUpdate }
}

const save = () => screen.getByRole('button', { name: /save|update/i })

describe('MemberFormDialog (edit)', () => {
  it('can save a member who holds more than 1000 shares (share count is read-only), without resending the role', async () => {
    const user = userEvent.setup()
    const { onUpdate } = renderEdit()
    const phone = await screen.findByLabelText(/phone/i)
    fireEvent.change(phone, { target: { value: '0789999999' } })
    await user.click(save())
    await waitFor(() => expect(onUpdate).toHaveBeenCalledTimes(1))
    const payload = onUpdate.mock.calls[0][0]
    expect(payload.phone).toBe('0789999999')
    expect(payload.roleInCooperative).toBeUndefined()
  })

  it('shows an over-long phone number instead of silently blocking the save', async () => {
    const user = userEvent.setup()
    const { onUpdate } = renderEdit()
    fireEvent.change(await screen.findByLabelText(/phone/i), { target: { value: '1'.repeat(33) } })
    await user.click(save())
    expect(await screen.findByText('Phone must be 32 characters or fewer')).toBeInTheDocument()
    expect(onUpdate).not.toHaveBeenCalled()
  })

  it('requires the mandatory fields and keeps what was typed', async () => {
    const user = userEvent.setup()
    const { onUpdate } = renderEdit()
    const first = await screen.findByLabelText(/first name/i)
    fireEvent.change(first, { target: { value: '' } })
    await user.click(save())
    expect(await screen.findByText('First name is required')).toBeInTheDocument()
    expect(onUpdate).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/last name/i)).toHaveValue('Uwase')
  })
})
