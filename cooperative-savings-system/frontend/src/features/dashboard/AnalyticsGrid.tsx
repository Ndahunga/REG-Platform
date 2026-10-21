import { Box } from '@mui/material'
import type { ReactNode } from 'react'
import { ANALYTICS_FOUR_COLUMN_MIN, ANALYTICS_TWO_COLUMN_MIN } from './analyticsStyles'

/**
 * Compact analytics grid: 1 card per row on phones, 2 on medium widths, 4 when the available width really allows it.
 * Cards must not force a width: they get `minWidth: 0` and wrap their own content, so nothing can overlap.
 */
export function AnalyticsGrid({ children }: { children: ReactNode }) {
  return (
    <Box data-testid="analytics-grid" sx={{ containerType: 'inline-size', width: '100%', minWidth: 0 }}>
      <Box
        sx={{
          display: 'grid',
          gap: 2,
          alignItems: 'stretch',
          gridTemplateColumns: 'minmax(0, 1fr)',
          [`@container (min-width: ${ANALYTICS_TWO_COLUMN_MIN}px)`]: {
            gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
          },
          [`@container (min-width: ${ANALYTICS_FOUR_COLUMN_MIN}px)`]: {
            gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
          },
        }}
      >
        {children}
      </Box>
    </Box>
  )
}
