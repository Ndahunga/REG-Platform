import { apiClient } from './client'

/** Exactly what the backend accepts. There is no recipient field: the server decides where the message goes. */
export interface ContactPayload {
  firstName: string
  lastName: string
  email: string
  countryCode: string
  phoneNumber?: string
  message: string
}

/** POST /public/contact: emails the message to OuWealth support. Public, no login. */
export async function sendContactMessage(payload: ContactPayload): Promise<void> {
  await apiClient.post('/public/contact', payload)
}
