import { Box, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { BrandHomeLink } from '@/shared/components/BrandHomeLink'
import { BrandLogo } from '@/shared/components/BrandLogo'
import { AUTH_CARD_MAX_WIDTH, AUTH_FORM_MAX_WIDTH } from './authFormStyles'

interface AuthSplitShellProps {
  /** Supporting copy under the tagline, already translated. */
  supporting: string
  children: ReactNode
}

/**
 * One compact, centered auth card made of two EQUAL halves (brand panel and form) from the md breakpoint up. The card
 * grows with its content, so no wizard step is ever clipped. On phones the halves stack and the brand panel shrinks to
 * a compact header strip above the form.
 */
export function AuthSplitShell({ supporting, children }: AuthSplitShellProps) {
  const { t } = useTranslation()

  return (
    <Box
      data-testid="auth-split-shell"
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '1fr 1fr' },
        width: '100%',
        maxWidth: AUTH_CARD_MAX_WIDTH,
        mx: 'auto',
        borderRadius: 3,
        overflow: 'hidden',
        border: '1px solid',
        borderColor: 'divider',
        boxShadow: '0 18px 48px rgba(15, 23, 42, 0.14)',
        bgcolor: 'background.paper',
      }}
    >
      <Box
        component="section"
        data-testid="auth-brand-panel"
        sx={{
          minWidth: 0,
          color: '#FFFFFF',
          px: { xs: 2.5, md: 4.5 },
          py: { xs: 2, md: 5 },
          display: 'flex',
          flexDirection: 'column',
          justifyContent: { md: 'center' },
          gap: { xs: 1, md: 3 },
          background:
            'radial-gradient(ellipse at 100% 100%, rgba(255,122,0,0.20) 0%, transparent 55%), linear-gradient(160deg, #0A0A0A 0%, #0E2748 58%, #1B4D8C 140%)',
        }}
      >
        <BrandHomeLink>
          <BrandLogo variant="lockup" size={40} onDark />
        </BrandHomeLink>
        <Box>
          <Typography
            component="p"
            sx={{
              m: 0,
              fontWeight: 700,
              lineHeight: 1.25,
              fontSize: { xs: '1.05rem', md: '1.6rem' },
              maxWidth: 360,
            }}
          >
            {t('app.tagline')}
          </Typography>
          <Box
            sx={{ width: 40, height: 3, borderRadius: 2, bgcolor: 'secondary.main', mt: { xs: 1, md: 2 } }}
          />
        </Box>
        <Typography
          component="p"
          variant="body2"
          sx={{ display: { xs: 'none', md: 'block' }, color: 'rgba(255,255,255,0.78)', maxWidth: 340 }}
        >
          {supporting}
        </Typography>
      </Box>

      <Box
        data-testid="auth-form-panel"
        sx={{
          minWidth: 0,
          p: { xs: 2.5, sm: 4, md: 5 },
          bgcolor: 'background.paper',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
        }}
      >
        {/* only the form content is constrained: the pane keeps its space, the fields never stretch across it */}
        <Box
          data-testid="auth-form-content"
          sx={{ width: '100%', maxWidth: AUTH_FORM_MAX_WIDTH, mx: 'auto', minWidth: 0 }}
        >
          {children}
        </Box>
      </Box>
    </Box>
  )
}
