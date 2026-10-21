import { Paper, Typography } from '@mui/material'
import type { ReactNode } from 'react'

interface ChartTooltipProps {
  title: ReactNode
  lines?: { label: string; value: string; color?: string }[]
}

/** Theme-aware tooltip body (Recharts' default tooltip ignores the MUI theme). */
export function ChartTooltip({ title, lines = [] }: ChartTooltipProps) {
  return (
    <Paper
      elevation={3}
      sx={{ px: 1.5, py: 1, border: '1px solid', borderColor: 'divider', maxWidth: 260 }}
    >
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {title}
      </Typography>
      {lines.map((line) => (
        <Typography
          key={line.label}
          variant="caption"
          color="text.secondary"
          sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, alignItems: 'center' }}
        >
          <span>
            {line.color ? (
              <span
                aria-hidden="true"
                style={{
                  display: 'inline-block',
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  background: line.color,
                  marginRight: 6,
                }}
              />
            ) : null}
            {line.label}
          </span>
          <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>{line.value}</span>
        </Typography>
      ))}
    </Paper>
  )
}
