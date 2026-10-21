import { Box, Paper, Skeleton, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import type { MomChangeState } from '@/shared/types/dashboard'
import { MonthOverMonthDelta } from './MonthOverMonthDelta'
import { dashboardKpiLabelSx, dashboardKpiValueSx } from './dashboardTypography'

interface InsightKpiCardProps {
  label: string
  value: string
  hint?: string
  icon?: ReactNode
  loading?: boolean
  changePercent?: string | number | null
  changeState?: MomChangeState
  momSemantics?: 'neutral' | 'favorable-up' | 'favorable-down'
  showMom?: boolean
  'data-testid'?: string
}

export function InsightKpiCard({
  label,
  value,
  hint,
  icon,
  loading,
  changePercent,
  changeState,
  momSemantics = 'neutral',
  showMom = true,
  'data-testid': testId,
}: InsightKpiCardProps) {
  return (
    <Paper
      elevation={0}
      data-testid={testId}
      sx={{
        p: { xs: 2, sm: 2.5 },
        height: '100%',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
        bgcolor: 'background.paper',
        textAlign: 'center',
      }}
    >
      <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 0.75, mb: 1 }}>
        {icon ? <Box sx={{ color: 'primary.main', display: 'inline-flex' }}>{icon}</Box> : null}
        <Typography
          variant="caption"
          sx={{
            ...dashboardKpiLabelSx,
            letterSpacing: 0.5,
            textTransform: 'uppercase',
            color: 'text.secondary',
          }}
        >
          {label}
        </Typography>
      </Box>

      {loading ? (
        <Skeleton variant="text" width="60%" sx={{ mx: 'auto', fontSize: '1.35rem' }} />
      ) : (
        <Typography
          variant="h5"
          component="p"
          sx={{ ...dashboardKpiValueSx, fontVariantNumeric: 'tabular-nums', wordBreak: 'break-word' }}
        >
          {value}
        </Typography>
      )}

      {hint ? (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          {hint}
        </Typography>
      ) : null}

      {showMom && !loading ? (
        <Box sx={{ mt: 1 }}>
          <MonthOverMonthDelta
            changePercent={changePercent}
            changeState={changeState}
            semantics={momSemantics}
          />
        </Box>
      ) : null}
    </Paper>
  )
}
