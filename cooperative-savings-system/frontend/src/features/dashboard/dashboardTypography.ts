/**
 * Medium type scale for dashboard pages only. Font families are not set here: the dashboard keeps the project
 * theme's families, and only sizes and weights are tuned (semantic heading elements stay as they are).
 */

/** Page titles: "Dashboard", the member welcome heading. */
export const dashboardPageTitleSx = {
  fontSize: { xs: '1.4rem', md: '1.65rem' },
  fontWeight: 600,
  lineHeight: 1.25,
} as const

/** Member welcome heading: a touch calmer than the officer dashboard title. */
export const dashboardWelcomeTitleSx = {
  fontSize: { xs: '1.35rem', md: '1.6rem' },
  fontWeight: 600,
  lineHeight: 1.25,
} as const

/** Section headings such as "Saving Scheme at a glance", "This month", "Member insights". */
export const dashboardSectionTitleSx = {
  fontSize: { xs: '1.05rem', md: '1.15rem' },
  fontWeight: 600,
  lineHeight: 1.3,
} as const

/** Chart and insight card titles. */
export const dashboardChartTitleSx = {
  fontSize: '1rem',
  fontWeight: 600,
  lineHeight: 1.35,
} as const

/** Supporting text under dashboard headings (kept at or above 0.875rem for readability). */
export const dashboardHintSx = {
  fontSize: '0.875rem',
  fontWeight: 400,
  lineHeight: 1.5,
} as const

/** KPI values on insight cards (same scale as the dashboard MetricCard). */
export const dashboardKpiValueSx = {
  fontSize: { xs: '1.15rem', sm: '1.35rem' },
  fontWeight: 600,
  lineHeight: 1.2,
} as const

/** Small caps labels above or below KPI values. */
export const dashboardKpiLabelSx = {
  fontSize: '0.72rem',
  fontWeight: 600,
} as const

/** The Available Group Funds figure: still the strongest number on the dashboard, but not oversized. */
export const dashboardHeroAmountSx = {
  fontSize: { xs: '1.55rem', md: '1.9rem' },
  fontWeight: 600,
  lineHeight: 1.2,
} as const
