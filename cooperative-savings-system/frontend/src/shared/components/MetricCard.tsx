import { Box, Paper, Skeleton, Typography } from '@mui/material'
import type { ReactNode } from 'react'

export type MetricAccent =
  | 'blue'
  | 'green'
  | 'purple'
  | 'teal'
  | 'gold'
  | 'red'
  | 'orange'
  | 'neutral'

const ACCENT_COLORS: Record<MetricAccent, string> = {
  blue: '#1B4D8C',
  green: '#16A34A',
  purple: '#7C3AED',
  teal: '#0D9488',
  gold: '#FF7A00',
  red: '#C62828',
  orange: '#FF5C00',
  neutral: '#64748B',
}

interface MetricCardProps {
  label: string
  value?: string
  hint?: string
  icon?: ReactNode
  loading?: boolean
  accent?: MetricAccent
  /** Reference style: large centered amount above small label */
  centered?: boolean
  /** `dashboard` is the medium scale used on dashboards; `default` keeps the original size elsewhere. */
  scale?: 'default' | 'dashboard'
}

export function MetricCard({
  label,
  value,
  hint,
  icon,
  loading,
  accent = 'teal',
  centered = true,
  scale = 'default',
}: MetricCardProps) {
  const color = ACCENT_COLORS[accent]
  const compact = scale === 'dashboard'
  const valueSize = compact ? { xs: '1.15rem', sm: '1.35rem' } : { xs: '1.35rem', sm: '1.6rem' }
  const valueWeight = compact ? 600 : 700
  const labelWeight = compact ? 600 : 700
  const labelSize = compact ? '0.72rem' : undefined

  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 2, sm: 2.5 },
        height: '100%',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
        bgcolor: 'background.paper',
        boxShadow: '0 1px 3px rgba(15, 23, 42, 0.06)',
        borderTop: `3px solid ${color}`,
        textAlign: centered ? 'center' : 'left',
      }}
    >
      {!centered ? (
        <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1, mb: 1 }}>
          <Typography
            variant="caption"
            sx={{
              fontWeight: labelWeight,
              ...(labelSize ? { fontSize: labelSize } : {}),
              letterSpacing: 0.6,
              textTransform: 'uppercase',
              color: 'text.secondary',
            }}
          >
            {label}
          </Typography>
          {icon ? <Box sx={{ color }}>{icon}</Box> : null}
        </Box>
      ) : null}

      {loading ? (
        <>
          <Skeleton width="70%" height={40} sx={{ mx: centered ? 'auto' : 0 }} />
          <Skeleton width="50%" sx={{ mx: centered ? 'auto' : 0 }} />
        </>
      ) : (
        <>
          {centered && icon ? (
            <Box sx={{ color, mb: 1, display: 'flex', justifyContent: 'center' }}>{icon}</Box>
          ) : null}
          <Typography
            component="p"
            sx={{
              fontSize: valueSize,
              fontWeight: valueWeight,
              lineHeight: 1.2,
              color: 'text.primary',
              fontVariantNumeric: 'tabular-nums',
              wordBreak: 'break-word',
            }}
          >
            {value ?? '—'}
          </Typography>
          {centered ? (
            <Typography
              variant="caption"
              sx={{
                display: 'block',
                mt: 1,
                fontWeight: labelWeight,
                ...(labelSize ? { fontSize: labelSize } : {}),
                letterSpacing: 0.7,
                textTransform: 'uppercase',
                color: 'text.secondary',
              }}
            >
              {label}
            </Typography>
          ) : null}
          {hint ? (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
              {hint}
            </Typography>
          ) : null}
        </>
      )}
    </Paper>
  )
}
