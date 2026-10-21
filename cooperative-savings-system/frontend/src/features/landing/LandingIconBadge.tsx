import { Box } from '@mui/material'
import { alpha } from '@mui/material/styles'
import type { ReactNode } from 'react'
import type { LandingAccent } from './landingStyles'

/** Rounded tinted container for a card icon (decorative). Brightens slightly when its card is hovered. */
export function LandingIconBadge({ children, accent = 'primary' }: { children: ReactNode; accent?: LandingAccent }) {
  return (
    <Box
      className="landing-card-icon"
      aria-hidden
      sx={(theme) => ({
        width: 40,
        height: 40,
        borderRadius: 1.5,
        display: 'grid',
        placeItems: 'center',
        mb: 1.5,
        color: `${accent}.main`,
        bgcolor: alpha(theme.palette[accent].main, theme.palette.mode === 'dark' ? 0.2 : 0.1),
        transition: 'background-color 200ms ease',
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
      })}
    >
      {children}
    </Box>
  )
}
