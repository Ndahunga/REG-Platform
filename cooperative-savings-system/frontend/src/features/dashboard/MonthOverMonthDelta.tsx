import NorthIcon from '@mui/icons-material/North'
import SouthIcon from '@mui/icons-material/South'
import { Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { MomChangeState } from '@/shared/types/dashboard'
import { formatMomPercent, resolveMomDisplay } from './momDisplay'

interface MonthOverMonthDeltaProps {
  changePercent: string | number | null | undefined
  changeState: MomChangeState | undefined
  /**
   * Semantic coloring: `neutral` never paints growth green (use for fines).
   * `favorable-up` paints increases positively (contributions, repayments).
   * `favorable-down` paints decreases positively (rarely used).
   */
  semantics?: 'neutral' | 'favorable-up' | 'favorable-down'
  'data-testid'?: string
}

export function MonthOverMonthDelta({
  changePercent,
  changeState,
  semantics = 'neutral',
  'data-testid': testId,
}: MonthOverMonthDeltaProps) {
  const { t } = useTranslation()
  const mom = resolveMomDisplay(changePercent, changeState)

  if (mom.state === 'NO_BASELINE') {
    return (
      <Typography variant="caption" color="text.secondary" data-testid={testId}>
        {t('dashboard.insights.noBaseline')}
      </Typography>
    )
  }

  if (mom.state === 'FLAT') {
    return (
      <Typography variant="caption" color="text.secondary" data-testid={testId}>
        {t('dashboard.insights.noChange')}
      </Typography>
    )
  }

  const color =
    semantics === 'neutral'
      ? 'text.secondary'
      : semantics === 'favorable-up'
        ? mom.state === 'UP'
          ? 'success.main'
          : 'warning.main'
        : mom.state === 'DOWN'
          ? 'success.main'
          : 'warning.main'

  return (
    <Stack
      direction="row"
      spacing={0.5}
      data-testid={testId}
      sx={{ color, alignItems: 'center', justifyContent: 'center' }}
    >
      {mom.arrow === 'up' ? <NorthIcon sx={{ fontSize: 14 }} /> : <SouthIcon sx={{ fontSize: 14 }} />}
      <Typography variant="caption" sx={{ fontWeight: 600, color: 'inherit' }}>
        {t('dashboard.insights.vsLastMonth', { value: formatMomPercent(mom.percent) })}
      </Typography>
    </Stack>
  )
}
