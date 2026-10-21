import { act, render, screen, within } from '@testing-library/react'
import i18n from 'i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { AppProviders } from '@/app/providers/AppProviders'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import { LEGAL_SECTIONS } from '@/features/legal/legalSections'
import { PublicLayout } from '@/layouts/PublicLayout'
import { publicFooterLinks, publicPrimaryLinks } from '@/layouts/publicNav'
import { TermsPage } from './TermsPage'

function renderTerms(path = '/terms') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppProviders>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path="/terms" element={<TermsPage />} />
          </Route>
        </Routes>
      </AppProviders>
    </MemoryRouter>,
  )
}

const PLACEHOLDER = /being finalized|to be published|will be published here|placeholder|coming soon|lorem/i
const HEADINGS = [
  'Acceptance of these terms',
  'The service',
  'Accounts and responsibilities',
  'Scheme data',
  'Privacy and personal data',
  'Subscription',
  'Acceptable use',
  'Availability and liability',
  'Suspension',
  'Changes and governing law',
  'Contact us',
]

afterEach(async () => {
  await act(async () => {
    await i18n.changeLanguage('en')
  })
})

describe('Terms & Conditions page', () => {
  it('shows the title and the 3 October 2026 effective date', () => {
    renderTerms()
    expect(screen.getByRole('heading', { level: 1, name: 'Terms & Conditions' })).toBeInTheDocument()
    expect(screen.getByTestId('terms-effective')).toHaveTextContent('Effective date: 3 October 2026')
  })

  it('has every major section as a titled h2, in order, with Privacy and personal data inside the Terms', () => {
    renderTerms()
    const found = within(screen.getByTestId('terms-page'))
      .getAllByRole('heading', { level: 2 })
      .map((h) => h.textContent)
    expect(found).toEqual(HEADINGS)
    expect(LEGAL_SECTIONS.terms).toHaveLength(HEADINGS.length)
  })

  it('has no placeholder text or placeholder alert', () => {
    renderTerms()
    const page = screen.getByTestId('terms-page')
    expect(page.textContent ?? '').not.toMatch(PLACEHOLDER)
    expect(screen.queryByTestId('terms-placeholder')).not.toBeInTheDocument()
    expect(within(page).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps every section short and readable', () => {
    renderTerms()
    for (const id of LEGAL_SECTIONS.terms) {
      const section = screen.getByTestId(`terms-section-${id}`)
      expect((section.textContent ?? '').length, id).toBeGreaterThan(40)
      for (const paragraph of section.querySelectorAll('p')) {
        expect((paragraph.textContent ?? '').length, id).toBeLessThan(520)
      }
    }
    // the whole page stays compact even with the privacy section
    expect(screen.getByTestId('terms-page').textContent?.length ?? 0).toBeLessThan(5200)
  })

  it('keeps the Rwanda governing law and the subscription / read-only wording', () => {
    renderTerms()
    expect(screen.getByTestId('terms-section-changes')).toHaveTextContent(
      'These terms are governed by the laws of the Republic of Rwanda.',
    )
    expect(screen.getByTestId('terms-section-subscription')).toHaveTextContent(
      'When a subscription ends, actions that record or change financial data may be limited until it is renewed. Reading records and downloading reports stays available.',
    )
  })

  it('offers clickable support email, phone and WhatsApp from the shared constants', () => {
    renderTerms()
    const contact = screen.getByTestId('terms-section-contact')
    const email = within(contact).getByRole('link', { name: 'support@ozufy.com' })
    const call = within(contact).getByRole('link', { name: '0782102154' })
    const whatsapp = within(contact).getByRole('link', { name: '0793634217' })
    expect(email).toHaveAttribute('href', 'mailto:support@ozufy.com')
    expect(call).toHaveAttribute('href', 'tel:+250782102154')
    expect(whatsapp).toHaveAttribute('href', 'https://wa.me/250793634217')
    expect(whatsapp).toHaveAttribute('target', '_blank')
    expect(whatsapp.getAttribute('rel')).toMatch(/noopener/)
  })

  it('keeps the public layout, compact footer and support dock', () => {
    renderTerms()
    expect(screen.getByTestId('public-layout')).toBeInTheDocument()
    expect(screen.getByTestId('compact-footer')).toBeInTheDocument()
    expect(screen.queryByTestId('public-footer')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('support-dock')).toHaveLength(1)
  })

  it('has no link to a privacy page anywhere in the public chrome', () => {
    renderTerms()
    for (const link of screen.getAllByRole('link')) {
      expect(link.getAttribute('href') ?? '').not.toMatch(/privacy/i)
    }
    expect(screen.queryByRole('link', { name: /privacy policy/i })).not.toBeInTheDocument()
    for (const item of [...publicPrimaryLinks, ...publicFooterLinks]) {
      expect(item.path).not.toBe('/privacy')
      expect(item.labelKey).not.toMatch(/privacy/)
    }
  })

  it('is fully translated into Kinyarwanda with the same structure and date', async () => {
    renderTerms()
    await act(async () => {
      await i18n.changeLanguage('rw')
    })
    const page = screen.getByTestId('terms-page')
    expect(screen.getByTestId('terms-effective')).toHaveTextContent('Itariki itangiriraho: 3 Ukwakira 2026')
    const rwHeadings = within(page).getAllByRole('heading', { level: 2 })
    expect(rwHeadings).toHaveLength(HEADINGS.length)
    for (const heading of rwHeadings) {
      expect(HEADINGS).not.toContain(heading.textContent)
      expect(heading.textContent).not.toMatch(/^public\./)
    }
    expect(page.textContent ?? '').not.toMatch(PLACEHOLDER)
    expect(
      within(screen.getByTestId('terms-section-contact')).getByRole('link', { name: 'support@ozufy.com' }),
    ).toHaveAttribute('href', 'mailto:support@ozufy.com')
  })
})

describe('Privacy and personal data section (inside the Terms)', () => {
  const section = () => screen.getByTestId('terms-section-privacy')

  it('has its own anchor so /terms#privacy lands on it', () => {
    renderTerms('/terms#privacy')
    expect(section()).toHaveAttribute('id', 'privacy')
  })

  it('lists the general categories of data processed', () => {
    renderTerms()
    const items = within(section())
      .getAllByRole('listitem')
      .map((li) => li.textContent)
    expect(items).toHaveLength(4)
    expect(items[0]).toMatch(/Account details/)
    expect(items[1]).toMatch(/Member records/)
    expect(items[2]).toMatch(/Financial records/)
    expect(items[3]).toMatch(/Files you upload/)
  })

  it('says why data is processed, who is authorized to see it, and the basic rights', () => {
    renderTerms()
    expect(section()).toHaveTextContent(/We process personal information only to run OuWealth/)
    expect(section()).toHaveTextContent(/provide and secure the service/)
    expect(section()).toHaveTextContent(
      'each member sees what their role allows, and authorized officers see the records of their own Saving Scheme only',
    )
    expect(section()).toHaveTextContent('We do not sell personal information.')
    expect(section()).toHaveTextContent(
      'You may request access, correction, restriction or deletion of your personal information, object to certain processing, and exercise other rights available under applicable law.',
    )
  })

  it('names support@ozufy.com as the privacy contact, as a clickable link', () => {
    renderTerms()
    const contact = within(section()).getByTestId('privacy-contact')
    expect(contact).toHaveTextContent('Privacy contact: support@ozufy.com')
    expect(within(contact).getByRole('link', { name: 'support@ozufy.com' })).toHaveAttribute(
      'href',
      'mailto:support@ozufy.com',
    )
  })

  it('has natural Kinyarwanda for the same section', async () => {
    renderTerms()
    await act(async () => {
      await i18n.changeLanguage('rw')
    })
    expect(within(section()).getByRole('heading', { level: 2 })).toHaveTextContent('Ibanga n’amakuru bwite')
    expect(section()).toHaveTextContent(/Dutunganya amakuru bwite gusa kugira ngo dukoreshe OuWealth/)
    expect(section()).toHaveTextContent(
      /gusaba kubona, gukosora, kugabanya imikoreshereze cyangwa gusiba amakuru yawe bwite/,
    )
    expect(within(section()).getAllByRole('listitem')).toHaveLength(4)
    expect(within(section()).getByRole('link', { name: 'support@ozufy.com' })).toHaveAttribute(
      'href',
      'mailto:support@ozufy.com',
    )
  })

  it('does not point to a separate Privacy Policy any more', () => {
    for (const locale of [en, rw]) {
      expect(JSON.stringify(locale.public.terms)).not.toMatch(/Privacy Policy|Politiki yacu y'Ibanga/)
      expect((locale.public as Record<string, unknown>).privacy).toBeUndefined()
      expect((locale.public.nav as Record<string, unknown>).privacy).toBeUndefined()
    }
  })

  it('adds no unconfirmed facts: no vendors, retention durations, cookies or advertising', () => {
    for (const locale of [en, rw]) {
      const text = JSON.stringify(locale.public.terms.sections.privacy)
      expect(text).not.toMatch(
        /\b(Render|Vercel|Neon|Postgres|Twilio|Meta|Google|Amazon|AWS|Azure|Firebase|MTN|Airtel|Stripe)\b/i,
      )
      expect(text).not.toMatch(/\b\d+\s*(days?|weeks?|months?|years?|iminsi|amezi|imyaka)\b/i)
      expect(text).not.toMatch(/cookie|advertis|analytics|tracking|amatangazo/i)
      expect(text).not.toMatch(/—|–/)
    }
  })

  it('locale files have a title and body for every Terms section in both languages', () => {
    for (const locale of [en, rw] as const) {
      const sections = locale.public.terms.sections as Record<string, { title: string; body: string[] }>
      for (const id of LEGAL_SECTIONS.terms) {
        expect(sections[id]?.title, id).toBeTruthy()
        expect(sections[id]?.body?.length, id).toBeGreaterThan(0)
      }
    }
    expect(en.public.terms.effectiveDate).toBe('3 October 2026')
    expect(rw.public.terms.effectiveDate).toBe('3 Ukwakira 2026')
  })
})
