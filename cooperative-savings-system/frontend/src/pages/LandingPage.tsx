import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import AssessmentIcon from '@mui/icons-material/Assessment'
import FavoriteIcon from '@mui/icons-material/Favorite'
import GavelIcon from '@mui/icons-material/Gavel'
import GroupsIcon from '@mui/icons-material/Groups'
import PaymentsIcon from '@mui/icons-material/Payments'
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong'
import SavingsIcon from '@mui/icons-material/Savings'
import ShowChartIcon from '@mui/icons-material/ShowChart'
import ReceiptIcon from '@mui/icons-material/Receipt'
import {
  Box,
  Button,
  Card,
  CardContent,
  Container,
  Stack,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import type { SvgIconComponent } from '@mui/icons-material'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { OuWealthMark } from '@/features/branding/OuWealthMark'
import { LandingJourneySection } from '@/features/landing/LandingJourneySection'
import { LandingPricingSection } from '@/features/landing/LandingPricingSection'
import {
  LANDING_HEADING_FONT,
  LANDING_SECTION_PY,
  LANDING_SECTION_PY_COMPACT,
  landingCardBodySx,
  landingCardSx,
  landingCardTitleSx,
  landingFontsSx,
  landingLabelSx,
  landingSectionSubtitleSx,
  landingSectionTitleSx,
} from '@/features/landing/landingStyles'
import { LandingIconBadge } from '@/features/landing/LandingIconBadge'
import { ROUTES } from '@/shared/constants/routes'

const HOW_STEPS = [
  'public.landing.how.step1',
  'public.landing.how.step2',
  'public.landing.how.step3',
  'public.landing.how.step4',
  'public.landing.how.step5',
  'public.landing.how.step6',
] as const

const CAPABILITIES: { labelKey: string; descriptionKey: string; icon: SvgIconComponent }[] = [
  { labelKey: 'public.landing.capabilities.members', descriptionKey: 'public.landing.capabilities.membersDesc', icon: GroupsIcon },
  { labelKey: 'public.landing.capabilities.contributions', descriptionKey: 'public.landing.capabilities.contributionsDesc', icon: SavingsIcon },
  { labelKey: 'public.landing.capabilities.loans', descriptionKey: 'public.landing.capabilities.loansDesc', icon: AccountBalanceWalletIcon },
  { labelKey: 'public.landing.capabilities.fines', descriptionKey: 'public.landing.capabilities.finesDesc', icon: GavelIcon },
  { labelKey: 'public.landing.capabilities.social', descriptionKey: 'public.landing.capabilities.socialDesc', icon: FavoriteIcon },
  { labelKey: 'public.landing.capabilities.investments', descriptionKey: 'public.landing.capabilities.investmentsDesc', icon: ShowChartIcon },
  { labelKey: 'public.landing.capabilities.payouts', descriptionKey: 'public.landing.capabilities.payoutsDesc', icon: PaymentsIcon },
  { labelKey: 'public.landing.capabilities.reports', descriptionKey: 'public.landing.capabilities.reportsDesc', icon: AssessmentIcon },
  { labelKey: 'public.landing.capabilities.ledger', descriptionKey: 'public.landing.capabilities.ledgerDesc', icon: ReceiptLongIcon },
  { labelKey: 'public.landing.capabilities.billing', descriptionKey: 'public.landing.capabilities.billingDesc', icon: ReceiptIcon },
]

function HeroVisual() {
  const { t } = useTranslation()
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'

  return (
    <Box
      aria-hidden
      data-testid="landing-hero-visual"
      sx={{
        position: 'relative',
        borderRadius: 3,
        overflow: 'hidden',
        minHeight: { xs: 260, md: 360 },
        background: dark
          ? 'linear-gradient(145deg, #143A6B 0%, #0A0A0A 55%, #1a1208 100%)'
          : 'linear-gradient(145deg, #1B4D8C 0%, #4A7AB8 42%, #FF7A00 120%)',
        boxShadow: dark ? '0 24px 48px rgba(0,0,0,0.45)' : '0 24px 48px rgba(27,77,140,0.28)',
      }}
    >
      <Box
        sx={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(circle at 20% 25%, rgba(255,255,255,0.18) 0%, transparent 40%), radial-gradient(circle at 85% 75%, rgba(255,122,0,0.35) 0%, transparent 45%)',
        }}
      />
      <Stack
        spacing={2}
        sx={{
          position: 'relative',
          height: '100%',
          minHeight: { xs: 260, md: 360 },
          px: 3,
          py: 4,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <OuWealthMark size={72} />
        <Typography
          variant="h5"
          component="p"
          sx={{ color: '#FFFFFF', textAlign: 'center', fontFamily: LANDING_HEADING_FONT, fontSize: '1.2rem', fontWeight: 500, maxWidth: 320 }}
        >
          {t('public.landing.hero.visualTitle')}
        </Typography>
        <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.82)', textAlign: 'center', maxWidth: 300 }}>
          {t('public.landing.hero.visualSubtitle')}
        </Typography>
        <Box
          component="img"
          data-marketing-image-slot="hero-community"
          alt=""
          sx={{ display: 'none' }}
        />
      </Stack>
    </Box>
  )
}

export function LandingPage() {
  const { t } = useTranslation()
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'
  const location = useLocation()
  const reduceMotion = useMediaQuery('(prefers-reduced-motion: reduce)')

  useEffect(() => {
    if (location.hash !== '#pricing') return
    const el = document.getElementById('pricing')
    if (!el) return
    el.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }, [location.hash, reduceMotion])

  return (
    <Box data-testid="landing-page" sx={landingFontsSx}>
      <Box
        component="section"
        aria-labelledby="landing-hero-heading"
        sx={{
          py: { xs: 4.5, md: 7 },
          background: dark
            ? 'radial-gradient(ellipse at 10% 0%, rgba(27,77,140,0.35) 0%, transparent 50%), linear-gradient(180deg, #0A0A0A 0%, #121212 100%)'
            : 'radial-gradient(ellipse at 12% 0%, rgba(27,77,140,0.14) 0%, transparent 50%), radial-gradient(ellipse at 90% 20%, rgba(255,122,0,0.08) 0%, transparent 40%), linear-gradient(180deg, #FFFFFF 0%, #F4F8FD 100%)',
        }}
      >
        <Container maxWidth="lg">
          <Box
            sx={{
              display: 'grid',
              gap: { xs: 4, md: 6 },
              gridTemplateColumns: { xs: '1fr', md: '1.05fr 0.95fr' },
              alignItems: 'center',
            }}
          >
            <Stack spacing={2.5}>
              <Typography variant="overline" color="primary" sx={{ letterSpacing: '0.1em', fontSize: '0.75rem', fontWeight: 500 }}>
                {t('app.name')}
              </Typography>
              <Typography
                id="landing-hero-heading"
                variant="h2"
                component="h1"
                sx={{ fontSize: { xs: '1.75rem', md: '2.2rem' }, fontWeight: 600, lineHeight: 1.2, letterSpacing: '-0.01em' }}
              >
                {t('public.landing.hero.title')}
              </Typography>
              <Typography
                variant="body1"
                component="p"
                color="text.secondary"
                sx={{ fontSize: { xs: '0.95rem', md: '1rem' }, fontWeight: 400, lineHeight: 1.6, maxWidth: 540 }}
              >
                {t('public.landing.hero.subtitle')}
              </Typography>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ pt: 1 }}>
                <Button
                  component={RouterLink}
                  to={ROUTES.signup}
                  variant="contained"
                  color="secondary"
                  size="large"
                  data-testid="landing-cta-signup"
                >
                  {t('public.nav.createScheme')}
                </Button>
                <Button
                  component={RouterLink}
                  to={ROUTES.login}
                  variant="outlined"
                  size="large"
                  data-testid="landing-cta-login"
                >
                  {t('public.nav.login')}
                </Button>
              </Stack>
            </Stack>
            <HeroVisual />
          </Box>
        </Container>
      </Box>

      <LandingJourneySection />

      <Box component="section" aria-labelledby="landing-how-heading" sx={{ py: LANDING_SECTION_PY, bgcolor: dark ? 'rgba(255,255,255,0.03)' : 'rgba(27,77,140,0.04)' }}>
        <Container maxWidth="lg">
          <Typography id="landing-how-heading" variant="h4" component="h2" sx={landingSectionTitleSx}>
            {t('public.landing.how.title')}
          </Typography>
          <Typography color="text.secondary" sx={landingSectionSubtitleSx}>
            {t('public.landing.how.subtitle')}
          </Typography>
          <Box
            sx={{
              display: 'grid',
              gap: 2,
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: 'repeat(3, 1fr)' },
            }}
          >
            {HOW_STEPS.map((key, index) => (
              <Card key={key} elevation={0} sx={landingCardSx('primary')}>
                <CardContent sx={{ p: 2.5, '&:last-child': { pb: 2.5 } }}>
                  <Typography variant="overline" color="secondary.main" sx={landingLabelSx}>
                    {t('public.landing.how.stepLabel', { step: index + 1 })}
                  </Typography>
                  <Typography variant="subtitle1" component="h3" sx={[landingCardTitleSx, { mt: 0.5 }]}>
                    {t(key)}
                  </Typography>
                </CardContent>
              </Card>
            ))}
          </Box>
        </Container>
      </Box>

      <Box component="section" aria-labelledby="landing-capabilities-heading" sx={{ py: LANDING_SECTION_PY }}>
        <Container maxWidth="lg">
          <Typography id="landing-capabilities-heading" variant="h4" component="h2" sx={landingSectionTitleSx}>
            {t('public.landing.capabilities.title')}
          </Typography>
          <Typography color="text.secondary" sx={landingSectionSubtitleSx}>
            {t('public.landing.capabilities.subtitle')}
          </Typography>
          <Box
            sx={{
              display: 'grid',
              gap: 2,
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: 'repeat(3, 1fr)', lg: 'repeat(5, 1fr)' },
            }}
          >
            {CAPABILITIES.map(({ labelKey, descriptionKey, icon: Icon }) => (
              <Card key={labelKey} elevation={0} sx={landingCardSx('primary')}>
                <CardContent sx={{ p: 2.5, '&:last-child': { pb: 2.5 } }}>
                  <LandingIconBadge>
                    <Icon fontSize="small" />
                  </LandingIconBadge>
                  <Typography variant="subtitle1" component="h3" sx={landingCardTitleSx}>
                    {t(labelKey)}
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={[landingCardBodySx, { mt: 0.75 }]}>
                    {t(descriptionKey)}
                  </Typography>
                </CardContent>
              </Card>
            ))}
          </Box>
        </Container>
      </Box>

      <LandingPricingSection />

      <Box component="section" aria-labelledby="landing-value-heading" sx={{ py: LANDING_SECTION_PY }}>
        <Container maxWidth="lg">
          <Box
            sx={{
              display: 'grid',
              gap: 4,
              gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
              alignItems: 'center',
            }}
          >
            <Box>
              <Typography id="landing-value-heading" variant="h4" component="h2" sx={landingSectionTitleSx}>
                {t('public.landing.value.title')}
              </Typography>
              <Typography
                color="text.secondary"
                component="p"
                sx={{ fontSize: '0.95rem', fontWeight: 400, lineHeight: 1.6, mb: 2 }}
              >
                {t('public.landing.value.body')}
              </Typography>
              <Stack component="ul" spacing={1} sx={{ pl: 2, m: 0 }}>
                {(['organize', 'visibility', 'obligations', 'position'] as const).map((item) => (
                  <Typography key={item} component="li" variant="body1" sx={{ fontSize: '0.95rem', fontWeight: 400, lineHeight: 1.55 }}>
                    {t(`public.landing.value.${item}`)}
                  </Typography>
                ))}
              </Stack>
            </Box>
            <Box
              aria-hidden
              sx={{
                borderRadius: 3,
                minHeight: 240,
                background: dark
                  ? 'linear-gradient(160deg, rgba(27,77,140,0.35), rgba(255,122,0,0.12))'
                  : 'linear-gradient(160deg, rgba(27,77,140,0.12), rgba(255,122,0,0.16))',
                border: '1px solid',
                borderColor: 'divider',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                px: 3,
              }}
            >
              <Typography variant="h5" component="p" sx={{ textAlign: 'center', maxWidth: 280, fontFamily: LANDING_HEADING_FONT, fontSize: '1.2rem', fontWeight: 500 }}>
                {t('public.landing.value.visual')}
              </Typography>
            </Box>
          </Box>
        </Container>
      </Box>

      <Box
        component="section"
        aria-labelledby="landing-about-heading"
        sx={{ py: LANDING_SECTION_PY_COMPACT, bgcolor: dark ? 'rgba(255,255,255,0.03)' : 'rgba(27,77,140,0.04)' }}
      >
        <Container maxWidth="md">
          <Typography id="landing-about-heading" variant="h5" component="h2" sx={[landingSectionTitleSx, { fontSize: { xs: '1.35rem', md: '1.55rem' } }]}>
            {t('public.landing.aboutTeaser.title')}
          </Typography>
          <Typography color="text.secondary" component="p" sx={{ fontSize: '0.95rem', fontWeight: 400, lineHeight: 1.6, mb: 2 }}>
            {t('public.landing.aboutTeaser.body')}
          </Typography>
          <Button component={RouterLink} to={ROUTES.about} variant="outlined" data-testid="landing-about-link">
            {t('public.landing.aboutTeaser.cta')}
          </Button>
        </Container>
      </Box>

      <Box component="section" aria-labelledby="landing-contact-heading" sx={{ py: LANDING_SECTION_PY_COMPACT }}>
        <Container maxWidth="md">
          <Typography id="landing-contact-heading" variant="h5" component="h2" sx={[landingSectionTitleSx, { fontSize: { xs: '1.35rem', md: '1.55rem' } }]}>
            {t('public.landing.contactTeaser.title')}
          </Typography>
          <Typography color="text.secondary" component="p" sx={{ fontSize: '0.95rem', fontWeight: 400, lineHeight: 1.6, mb: 2 }}>
            {t('public.landing.contactTeaser.body')}
          </Typography>
          <Button component={RouterLink} to={ROUTES.contact} variant="contained" data-testid="landing-contact-link">
            {t('public.landing.contactTeaser.cta')}
          </Button>
        </Container>
      </Box>
    </Box>
  )
}
