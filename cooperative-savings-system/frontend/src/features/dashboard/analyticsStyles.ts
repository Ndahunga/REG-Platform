/** Breakpoints are measured on the grid's own width (so a pinned sidebar is accounted for), not the screen. */
export const ANALYTICS_TWO_COLUMN_MIN = 560
export const ANALYTICS_FOUR_COLUMN_MIN = 1040

/** Shared surface for the compact analytics cards. */
export const analyticsCardSx = {
  minWidth: 0,
  p: { xs: 2, md: 2.25 },
  border: '1px solid',
  borderColor: 'divider',
  borderRadius: 2,
  display: 'flex',
  flexDirection: 'column',
  // content driven: no fixed heights, the grid row only stretches siblings to the tallest card
  height: '100%',
} as const
