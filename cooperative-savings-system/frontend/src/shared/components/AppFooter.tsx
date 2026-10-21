import { Box, Button, Container, Divider, Link, Stack, Typography, useMediaQuery } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom'
import { publicFooterLinks, publicNavTarget } from '@/layouts/publicNav'
import { BrandHomeLink } from '@/shared/components/BrandHomeLink'
import { BrandLogo } from '@/shared/components/BrandLogo'
import { ROUTES } from '@/shared/constants/routes'
import { SUPPORT_CONTACTS } from '@/shared/constants/supportContacts'
import { plainLinkSx } from '@/shared/styles/plainLink'

const footerLinkSx = { ...plainLinkSx, color: 'rgba(255,255,255,0.88)' } as const

interface AppFooterProps {
  /** `full` is the marketing footer (landing page only); `compact` is logo + copyright. */
  variant: 'full' | 'compact'
}

export function AppFooter({ variant }: AppFooterProps) {
  return variant === 'full' ? <FullFooter /> : <CompactFooter />
}

function CompactFooter() {
  const { t } = useTranslation()
  return (
    <Box
      component="footer"
      data-testid="compact-footer"
      aria-label={t('public.footer.compactAria')}
      sx={{
        borderTop: '1px solid',
        borderColor: 'divider',
        mt: 'auto',
        pl: { xs: 2, sm: 3 },
        // clear the floating support buttons (44px + 20px edge gap) so the copyright is never covered
        pr: { xs: 2, sm: 11 },
        py: 2,
        // leave room for the floating support buttons so they never cover the last line of content
        pb: { xs: 9, sm: 2 },
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={{ xs: 1, sm: 2 }}
        sx={{
          justifyContent: 'space-between',
          alignItems: { xs: 'flex-start', sm: 'center' },
          maxWidth: 1440,
          mx: 'auto',
        }}
      >
        <BrandHomeLink>
          <BrandLogo variant="lockup" size={28} />
        </BrandHomeLink>
        <Typography variant="caption" color="text.secondary">
          {t('public.footer.copyright', { year: new Date().getFullYear() })}
        </Typography>
      </Stack>
    </Box>
  )
}

function FullFooter() {
  const { t } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')

  // On the landing page the logo scrolls to the top instead of re-navigating to where we already are.
  // From any other page it is a normal link to "/" (the layout starts new pages at the top).
  const handleLogoClick = (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (location.pathname !== ROUTES.home) return
    event.preventDefault()
    window.scrollTo({ top: 0, left: 0, behavior: reducedMotion ? 'auto' : 'smooth' })
    if (location.hash) navigate(ROUTES.home, { replace: true })
  }

  return (
    <Box
      component="footer"
      data-testid="public-footer"
      sx={{
        borderTop: '1px solid',
        borderColor: 'divider',
        bgcolor: '#0A0A0A',
        color: '#FFFFFF',
        py: { xs: 4, md: 5 },
        // room for the floating support buttons on phones
        pb: { xs: 11, md: 5 },
        mt: 'auto',
      }}
    >
      <Container maxWidth="lg">
        <Stack spacing={3}>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={3}
            sx={{
              justifyContent: 'space-between',
              alignItems: { xs: 'flex-start', md: 'center' },
            }}
          >
            <Box>
              <BrandHomeLink onClick={handleLogoClick} ariaLabel={t('public.footer.backToTop')}>
                <BrandLogo variant="lockup" size={40} onDark />
              </BrandHomeLink>
              <Typography variant="body2" sx={{ mt: 1.5, maxWidth: 420, color: 'rgba(255,255,255,0.72)' }}>
                {t('public.footer.tagline')}
              </Typography>
            </Box>
            <Stack
              component="nav"
              aria-label={t('public.footer.navAria')}
              direction="row"
              useFlexGap
              spacing={1.5}
              sx={{ maxWidth: { xs: '100%', md: 520 }, flexWrap: 'wrap' }}
            >
              {publicFooterLinks.map((item) => (
                <Button
                  key={`${item.labelKey}-${item.path}-${item.hash ?? ''}`}
                  component={RouterLink}
                  to={publicNavTarget(item)}
                  size="small"
                  sx={{ color: 'rgba(255,255,255,0.88)', minHeight: 40 }}
                >
                  {t(item.labelKey)}
                </Button>
              ))}
            </Stack>
            <Box component="section" aria-labelledby="footer-contact-heading" data-testid="footer-contact">
              <Typography
                id="footer-contact-heading"
                component="h2"
                sx={{ fontSize: '0.95rem', fontWeight: 600, mb: 1, color: '#FFFFFF' }}
              >
                {t('public.footer.contactTitle')}
              </Typography>
              <Stack component="ul" spacing={0.5} sx={{ listStyle: 'none', m: 0, p: 0 }}>
                <Typography component="li" sx={{ fontSize: '0.9rem' }}>
                  <Link href={SUPPORT_CONTACTS.phoneHref} underline="none" sx={footerLinkSx} data-testid="footer-phone">
                    {SUPPORT_CONTACTS.phoneInternational}
                  </Link>
                </Typography>
                <Typography component="li" sx={{ fontSize: '0.9rem' }}>
                  <Link href={SUPPORT_CONTACTS.emailHref} underline="none" sx={footerLinkSx} data-testid="footer-email">
                    {SUPPORT_CONTACTS.email}
                  </Link>
                </Typography>
                <Typography component="li" sx={{ fontSize: '0.9rem' }}>
                  <Link component={RouterLink} to={ROUTES.contact} underline="none" sx={footerLinkSx} data-testid="footer-contact-form">
                    {t('public.footer.sendMessage')}
                  </Link>
                </Typography>
              </Stack>
            </Box>
          </Stack>
          <Divider sx={{ borderColor: 'rgba(255,255,255,0.12)' }} />
          <Typography variant="caption" sx={{ color: 'rgba(255,255,255,0.55)' }}>
            {t('public.footer.copyright', { year: new Date().getFullYear() })}
          </Typography>
        </Stack>
      </Container>
    </Box>
  )
}
