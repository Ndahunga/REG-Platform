import { isValidCooperativeEmail } from '@/shared/utils/rwandaCooperative'

/**
 * Shared checks for the "assign a President" inputs, used by both the cooperative create dialog and the
 * Assign President dialog. They mirror the backend AssignAdministratorRequest limits (username 64,
 * names 128, email 255, phone 32, temporary password 8 to 128, userId a UUID).
 */

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

export interface PresidentInput {
  mode: 'new' | 'existing'
  userId: string
  username: string
  email: string
  firstName: string
  lastName: string
  phone: string
  temporaryPassword: string
}

export interface PresidentProblem {
  field: 'userId' | 'username' | 'email' | 'firstName' | 'lastName' | 'phone' | 'temporaryPassword'
  message: string
}

/** Returns the first problem found (in field order), or null when the inputs are acceptable. */
export function findPresidentProblem(input: PresidentInput): PresidentProblem | null {
  if (input.mode === 'existing') {
    const userId = input.userId.trim()
    if (!userId) return { field: 'userId', message: 'Enter the existing user ID' }
    if (!UUID.test(userId)) return { field: 'userId', message: 'The user ID must be a valid UUID' }
    return null
  }
  const username = input.username.trim()
  if (!username) return { field: 'username', message: 'Username is required' }
  if (username.length > 64) return { field: 'username', message: 'Username must be 64 characters or fewer' }
  const email = input.email.trim()
  if (!email || !isValidCooperativeEmail(email)) {
    return { field: 'email', message: 'Enter a valid email address' }
  }
  if (email.length > 255) return { field: 'email', message: 'Email must be 255 characters or fewer' }
  const firstName = input.firstName.trim()
  if (!firstName) return { field: 'firstName', message: 'First name is required' }
  if (firstName.length > 128) return { field: 'firstName', message: 'First name must be 128 characters or fewer' }
  const lastName = input.lastName.trim()
  if (!lastName) return { field: 'lastName', message: 'Last name is required' }
  if (lastName.length > 128) return { field: 'lastName', message: 'Last name must be 128 characters or fewer' }
  if (input.phone.trim().length > 32) {
    return { field: 'phone', message: 'Phone must be 32 characters or fewer' }
  }
  const password = input.temporaryPassword
  if (password && password.length < 8) {
    return { field: 'temporaryPassword', message: 'Temporary password must be at least 8 characters' }
  }
  if (password && password.length > 128) {
    return { field: 'temporaryPassword', message: 'Temporary password must be 128 characters or fewer' }
  }
  return null
}
