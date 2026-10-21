import { Box, Paper, Skeleton, Typography } from '@mui/material'
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import { analyticsCardSx } from './analyticsStyles'
import { ChartTooltip } from './ChartTooltip'
import { dashboardChartTitleSx } from './dashboardTypography'

export interface DonutSlice {
  key: string
  label: string
  value: number
  color: string
}

interface DonutCardProps {
  testId: string
  title: string
  slices: DonutSlice[]
  /** Big number in the donut hole, e.g. the total. */
  centerValue: string
  centerLabel: string
  formatValue: (value: number) => string
  emptyMessage: string
  loading?: boolean
}

const DONUT_SIZE = 128
/** The hole is 64% of the ring; text inside stays within ~58% of the donut so it never touches the ring. */
const CENTER_MAX_WIDTH = Math.round(DONUT_SIZE * 0.58)

/** Long values (currency amounts) step down in size so they fit the hole instead of overflowing it. */
function centerValueFontSize(value: string): string {
  if (value.length <= 5) return '1.25rem'
  if (value.length <= 8) return '1rem'
  if (value.length <= 11) return '0.82rem'
  return '0.7rem'
}

/**
 * Compact donut for genuine parts of one whole: title, centered donut, then a legend BELOW it in aligned columns
 * (dot, label, value, share). The legend carries the exact values as text, so the chart is never the only place a
 * number appears. A zero total shows a short empty state, not a blank ring.
 */
export function DonutCard({
  testId,
  title,
  slices,
  centerValue,
  centerLabel,
  formatValue,
  emptyMessage,
  loading,
}: DonutCardProps) {
  const safeSlices = slices.map((slice) => ({
    ...slice,
    value: Number.isFinite(slice.value) && slice.value > 0 ? slice.value : 0,
  }))
  const total = safeSlices.reduce((sum, slice) => sum + slice.value, 0)

  return (
    <Paper elevation={0} data-testid={testId} sx={analyticsCardSx}>
      <Typography variant="subtitle1" sx={[dashboardChartTitleSx, { mb: 1.25 }]}>
        {title}
      </Typography>

      {loading ? (
        <Skeleton variant="rounded" height={DONUT_SIZE} />
      ) : total <= 0 ? (
        <Box data-testid={`${testId}-empty`} sx={{ py: 2, display: 'flex', justifyContent: 'center' }}>
          <Typography variant="body2" color="text.secondary" align="center">
            {emptyMessage}
          </Typography>
        </Box>
      ) : (
        <>
          {/* chart and centre text share one grid cell, so the text can never leave the donut box */}
          <Box
            aria-hidden="true"
            data-testid={`${testId}-donut`}
            sx={{
              display: 'grid',
              placeItems: 'center',
              width: DONUT_SIZE,
              height: DONUT_SIZE,
              maxWidth: '100%',
              mx: 'auto',
              flexShrink: 0,
            }}
          >
            <Box sx={{ gridArea: '1 / 1', width: '100%', height: '100%' }}>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={safeSlices}
                    dataKey="value"
                    nameKey="label"
                    innerRadius="64%"
                    outerRadius="94%"
                    startAngle={90}
                    endAngle={-270}
                    paddingAngle={safeSlices.filter((s) => s.value > 0).length > 1 ? 2 : 0}
                    stroke="none"
                    isAnimationActive={false}
                  >
                    {safeSlices.map((slice) => (
                      <Cell key={slice.key} fill={slice.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    content={({ active, payload }) => {
                      const item = active ? payload?.[0]?.payload : undefined
                      if (!item) return null
                      return (
                        <ChartTooltip
                          title={item.label}
                          lines={[
                            { label: formatValue(item.value), value: `${((item.value / total) * 100).toFixed(1)}%` },
                          ]}
                        />
                      )
                    }}
                  />
                </PieChart>
              </ResponsiveContainer>
            </Box>
            <Box
              data-testid={`${testId}-center`}
              sx={{
                gridArea: '1 / 1',
                maxWidth: CENTER_MAX_WIDTH,
                textAlign: 'center',
                pointerEvents: 'none',
              }}
            >
              <Typography
                component="p"
                sx={{
                  m: 0,
                  fontSize: centerValueFontSize(centerValue),
                  fontWeight: 600,
                  lineHeight: 1.15,
                  overflowWrap: 'anywhere',
                }}
              >
                {centerValue}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', lineHeight: 1.2 }}>
                {centerLabel}
              </Typography>
            </Box>
          </Box>

          {/* legend below the donut: one grid so the columns line up across rows */}
          <Box
            component="ul"
            data-testid={`${testId}-legend`}
            sx={{
              listStyle: 'none',
              m: 0,
              mt: 1.5,
              p: 0,
              minWidth: 0,
              display: 'grid',
              gridTemplateColumns: '10px minmax(0, 1fr) auto auto',
              columnGap: 1,
              rowGap: 0.5,
              alignItems: 'baseline',
            }}
          >
            {safeSlices.map((slice) => (
              <Box
                key={slice.key}
                component="li"
                sx={{
                  gridColumn: '1 / -1',
                  display: 'grid',
                  gridTemplateColumns: 'inherit',
                  columnGap: 'inherit',
                  alignItems: 'baseline',
                  '@supports (grid-template-columns: subgrid)': { gridTemplateColumns: 'subgrid' },
                }}
              >
                <Box
                  aria-hidden="true"
                  sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: slice.color, alignSelf: 'center' }}
                />
                <Typography variant="body2" sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                  {slice.label}
                </Typography>
                <Typography
                  variant="body2"
                  sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums', textAlign: 'right', whiteSpace: 'nowrap' }}
                >
                  {formatValue(slice.value)}
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ minWidth: '3.4em', textAlign: 'right', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}
                >
                  {`${((slice.value / total) * 100).toFixed(1)}%`}
                </Typography>
              </Box>
            ))}
          </Box>
        </>
      )}
    </Paper>
  )
}
