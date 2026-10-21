import { describe, expect, it } from 'vitest'
import { SUPPORT_CONTACTS, supportMailtoHref, whatsappHrefWithText } from './supportContacts'

describe('SUPPORT_CONTACTS', () => {
  it('holds exactly the OuWealth support details and their normalized links', () => {
    expect(SUPPORT_CONTACTS).toEqual({
      phoneDisplay: '0782102154',
      phoneInternational: '+250782102154',
      phoneHref: 'tel:+250782102154',
      whatsappDisplay: '0793634217',
      whatsappHref: 'https://wa.me/250793634217',
      email: 'support@ozufy.com',
      emailHref: 'mailto:support@ozufy.com',
    })
  })

  it('builds a WhatsApp link with an encoded optional greeting', () => {
    expect(whatsappHrefWithText()).toBe('https://wa.me/250793634217')
    expect(whatsappHrefWithText('   ')).toBe('https://wa.me/250793634217')
    expect(whatsappHrefWithText('Hello & help?')).toBe('https://wa.me/250793634217?text=Hello%20%26%20help%3F')
  })

  it('builds a percent-encoded mailto: link to support with a subject and optional body', () => {
    expect(supportMailtoHref('OuWealth support')).toBe('mailto:support@ozufy.com?subject=OuWealth%20support')
    const href = supportMailtoHref('OuWealth support', 'Hello OuWealth support team,\n\n')
    expect(href).toBe(
      'mailto:support@ozufy.com?subject=OuWealth%20support&body=Hello%20OuWealth%20support%20team%2C%0A%0A',
    )
    expect(href.startsWith('mailto:support@ozufy.com?')).toBe(true)
  })
})
