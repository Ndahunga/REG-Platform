import { Box, Container, Link, Stack, Typography } from '@mui/material'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { SUPPORT_CONTACTS } from '@/shared/constants/supportContacts'
import { LEGAL_SECTIONS, type LegalDocumentKey } from './legalSections'

interface LegalDocumentProps {
  docKey: LegalDocumentKey
}

/** A short legal page: title, effective date and compact titled sections. Contact details come from supportContacts. */
export function LegalDocument({ docKey }: LegalDocumentProps) {
  const { t } = useTranslation()
  const { hash } = useLocation()
  const base = `public.${docKey}`
  const list = (key: string) => {
    const value = t(key, { returnObjects: true })
    return Array.isArray(value) ? (value as string[]) : []
  }

  // In-page links such as /terms#privacy land on their section (the browser only does this on a full page load).
  useEffect(() => {
    if (!hash) return
    document.getElementById(hash.slice(1))?.scrollIntoView?.()
  }, [hash])

  const paragraph = (text: string) => (
    <Typography key={text} component="p" sx={{ fontSize: '0.95rem', lineHeight: 1.65, m: 0, mb: 1 }}>
      {text}
    </Typography>
  )

  return (
    <Box data-testid={`${docKey}-page`} sx={{ py: { xs: 4, md: 7 } }}>
      <Container maxWidth="md">
        <Typography
          variant="h2"
          component="h1"
          gutterBottom
          sx={{ fontSize: { xs: '1.75rem', md: '2.1rem' }, fontWeight: 600, lineHeight: 1.2 }}
        >
          {t(`${base}.title`)}
        </Typography>
        <Typography variant="body2" color="text.secondary" data-testid={`${docKey}-effective`} sx={{ mb: 3 }}>
          {t(`${base}.effective`, { date: t(`${base}.effectiveDate`) })}
        </Typography>

        <Stack spacing={3}>
          {LEGAL_SECTIONS[docKey].map((id) => {
            const headingId = `${docKey}-${id}-heading`
            const items = list(`${base}.sections.${id}.items`)
            return (
              <Box
                key={id}
                id={id}
                component="section"
                aria-labelledby={headingId}
                data-testid={`${docKey}-section-${id}`}
                sx={{ scrollMarginTop: 88 }}
              >
                <Typography
                  id={headingId}
                  variant="h5"
                  component="h2"
                  sx={{ fontSize: '1.15rem', fontWeight: 600, lineHeight: 1.3, mb: 0.75 }}
                >
                  {t(`${base}.sections.${id}.title`)}
                </Typography>
                {list(`${base}.sections.${id}.body`).map(paragraph)}
                {items.length > 0 ? (
                  <Stack component="ul" spacing={0.5} sx={{ pl: 2.5, m: 0, mb: 1 }}>
                    {items.map((item) => (
                      <Typography key={item} component="li" sx={{ fontSize: '0.95rem', lineHeight: 1.6 }}>
                        {item}
                      </Typography>
                    ))}
                  </Stack>
                ) : null}
                {list(`${base}.sections.${id}.after`).map(paragraph)}
                {id === 'privacy' ? <PrivacyContact /> : null}
                {id === 'contact' ? <SupportLinks /> : null}
              </Box>
            )
          })}
        </Stack>
      </Container>
    </Box>
  )
}

/** The privacy contact point is the support email. */
function PrivacyContact() {
  const { t } = useTranslation()
  return (
    <Typography component="p" sx={{ fontSize: '0.95rem', lineHeight: 1.65, m: 0 }} data-testid="privacy-contact">
      {t('public.terms.sections.privacy.contact')}:{' '}
      <Link href={SUPPORT_CONTACTS.emailHref} data-testid="privacy-contact-email">
        {SUPPORT_CONTACTS.email}
      </Link>
    </Typography>
  )
}

function SupportLinks() {
  const { t } = useTranslation()
  return (
    <Stack component="ul" spacing={0.5} data-testid="legal-support-links" sx={{ pl: 0, m: 0, listStyle: 'none' }}>
      <Typography component="li" sx={{ fontSize: '0.95rem' }}>
        {t('support.email')}:{' '}
        <Link href={SUPPORT_CONTACTS.emailHref} data-testid="legal-support-email">
          {SUPPORT_CONTACTS.email}
        </Link>
      </Typography>
      <Typography component="li" sx={{ fontSize: '0.95rem' }}>
        {t('support.callUs')}:{' '}
        <Link href={SUPPORT_CONTACTS.phoneHref} data-testid="legal-support-call">
          {SUPPORT_CONTACTS.phoneDisplay}
        </Link>
      </Typography>
      <Typography component="li" sx={{ fontSize: '0.95rem' }}>
        {t('support.whatsapp')}:{' '}
        <Link
          href={SUPPORT_CONTACTS.whatsappHref}
          target="_blank"
          rel="noopener noreferrer"
          data-testid="legal-support-whatsapp"
        >
          {SUPPORT_CONTACTS.whatsappDisplay}
        </Link>
      </Typography>
    </Stack>
  )
}
