/**
 * Single source of truth for OuWealth support contact details. Every call, WhatsApp and email link in the
 * app (support dock, contact page, footer) must read from here.
 */
export const SUPPORT_CONTACTS = {
  phoneDisplay: '0782102154',
  /** The same number written in full international form (used where space allows, e.g. the landing footer). */
  phoneInternational: '+250782102154',
  phoneHref: 'tel:+250782102154',
  whatsappDisplay: '0793634217',
  whatsappHref: 'https://wa.me/250793634217',
  email: 'support@ozufy.com',
  emailHref: 'mailto:support@ozufy.com',
} as const

/**
 * mailto: link to support with a pre-filled subject and body, properly percent-encoded. Opens the visitor's email app.
 * This is the floating email button's action; it never calls the contact API.
 */
export function supportMailtoHref(subject: string, body = ''): string {
  const params = [`subject=${encodeURIComponent(subject)}`]
  if (body) params.push(`body=${encodeURIComponent(body)}`)
  return `${SUPPORT_CONTACTS.emailHref}?${params.join('&')}`
}

/** WhatsApp chat link with optional pre-filled text. */
export function whatsappHrefWithText(text?: string): string {
  const trimmed = text?.trim()
  return trimmed
    ? `${SUPPORT_CONTACTS.whatsappHref}?text=${encodeURIComponent(trimmed)}`
    : SUPPORT_CONTACTS.whatsappHref
}
