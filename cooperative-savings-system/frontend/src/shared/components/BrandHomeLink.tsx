import { Box } from '@mui/material'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { ROUTES } from '@/shared/constants/routes'

interface BrandHomeLinkProps {
  children: ReactNode
  /** Extra handler, e.g. the landing footer scrolls to the top instead of navigating. */
  onClick?: (event: React.MouseEvent<HTMLAnchorElement>) => void
  ariaLabel?: string
}

/** Wraps the OuWealth logo in a real link to the public landing page. */
export function BrandHomeLink({ children, onClick, ariaLabel }: BrandHomeLinkProps) {
  const { t } = useTranslation()
  return (
    <Box
      component={RouterLink}
      to={ROUTES.home}
      onClick={onClick}
      aria-label={ariaLabel ?? t('support.goHome')}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        color: 'inherit',
        textDecoration: 'none',
        borderRadius: 1,
        '&:focus-visible': {
          outline: '2px solid',
          outlineColor: 'primary.main',
          outlineOffset: 4,
        },
      }}
    >
      {children}
    </Box>
  )
}
