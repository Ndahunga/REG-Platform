import { Box, Stack, Typography, useTheme } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { AccessibleDataTable, MemberNameCell } from './AccessibleDataTable'
import { ChartTooltip } from './ChartTooltip'
import { CHART_COLORS, truncateLabel } from './chartPalette'
import type { ReliabilityBarDatum } from './dashboardVisuals'

interface StackedShareBarChartProps {
  data: ReliabilityBarDatum[]
  ariaLabel: string
}

const ROW_HEIGHT = 44
const Y_AXIS_WIDTH = 124

interface TickProps {
  x?: number | string
  y?: number | string
  payload?: { index: number; value: string }
}

function formatRate(value: number | null): string {
  return value === null ? '—' : `${value.toFixed(1)}%`
}

/**
 * One 100% stacked bar per member: on-time / paid-late / past-due. The three counts are exactly the evaluated
 * installments, so each bar is a true parts-of-whole. Counts, rate and evaluated total stay available in the
 * tooltip and in the accessible table.
 */
export function StackedShareBarChart({ data, ariaLabel }: StackedShareBarChartProps) {
  const { t } = useTranslation()
  const theme = useTheme()
  const height = Math.max(120, data.length * ROW_HEIGHT + 36)

  const series = [
    { key: 'onTimePct', label: t('dashboard.advancedInsights.onTime'), color: CHART_COLORS.green, count: 'onTime' },
    { key: 'paidLatePct', label: t('dashboard.advancedInsights.paidLate'), color: CHART_COLORS.orange, count: 'paidLate' },
    { key: 'pastDuePct', label: t('dashboard.advancedInsights.pastDue'), color: CHART_COLORS.red, count: 'pastDue' },
  ] as const

  const renderTick = ({ x = 0, y = 0, payload }: TickProps) => {
    const datum = payload ? data[payload.index] : undefined
    const name = (
      <text
        x={0}
        y={0}
        dy={-2}
        textAnchor="end"
        fontSize={12}
        fill={datum?.href ? theme.palette.primary.main : theme.palette.text.primary}
      >
        {truncateLabel(String(payload?.value ?? ''), 17)}
        <title>{String(payload?.value ?? '')}</title>
      </text>
    )
    return (
      <g transform={`translate(${x},${y})`}>
        {datum?.href ? (
          <RouterLink to={datum.href} tabIndex={-1}>
            {name}
          </RouterLink>
        ) : (
          name
        )}
        <text x={0} y={0} dy={12} textAnchor="end" fontSize={11} fill={theme.palette.text.secondary}>
          {formatRate(datum?.onTimeRate ?? null)}
        </text>
      </g>
    )
  }

  return (
    <Box>
      <Stack
        direction="row"
        spacing={2}
        useFlexGap
        sx={{ flexWrap: 'wrap', mb: 1 }}
        data-testid="repayment-reliability-legend"
      >
        {series.map((s) => (
          <Stack key={s.key} direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <Box aria-hidden="true" sx={{ width: 10, height: 10, borderRadius: '2px', bgcolor: s.color }} />
            <Typography variant="caption">{s.label}</Typography>
          </Stack>
        ))}
      </Stack>

      <Box aria-hidden="true" sx={{ width: '100%', height }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            layout="vertical"
            margin={{ top: 4, right: 12, left: 0, bottom: 4 }}
            barCategoryGap="28%"
          >
            <XAxis type="number" hide domain={[0, 100]} />
            <YAxis
              type="category"
              dataKey="label"
              width={Y_AXIS_WIDTH}
              tickLine={false}
              axisLine={false}
              interval={0}
              tick={renderTick}
            />
            <Tooltip
              cursor={{ fill: theme.palette.action.hover }}
              content={({ active, payload }) => {
                const datum: ReliabilityBarDatum | undefined = active ? payload?.[0]?.payload : undefined
                if (!datum) return null
                return (
                  <ChartTooltip
                    title={datum.label}
                    lines={[
                      { label: t('dashboard.advancedInsights.onTimeRate'), value: formatRate(datum.onTimeRate) },
                      ...series.map((s) => ({
                        label: s.label,
                        value: String(datum[s.count]),
                        color: s.color,
                      })),
                      { label: t('dashboard.advancedInsights.evaluated'), value: String(datum.evaluated) },
                    ]}
                  />
                )
              }}
            />
            {series.map((s, index) => (
              <Bar
                key={s.key}
                dataKey={s.key}
                stackId="reliability"
                fill={s.color}
                maxBarSize={18}
                radius={index === series.length - 1 ? [0, 4, 4, 0] : 0}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </Box>

      <AccessibleDataTable
        caption={ariaLabel}
        columns={[
          t('dashboard.advancedInsights.member'),
          t('dashboard.advancedInsights.onTimeRate'),
          t('dashboard.advancedInsights.onTime'),
          t('dashboard.advancedInsights.paidLate'),
          t('dashboard.advancedInsights.pastDue'),
          t('dashboard.advancedInsights.evaluated'),
        ]}
        rows={data.map((datum) => ({
          key: datum.id,
          cells: [
            <MemberNameCell key="n" label={datum.label} href={datum.href} />,
            formatRate(datum.onTimeRate),
            datum.onTime,
            datum.paidLate,
            datum.pastDue,
            datum.evaluated,
          ],
        }))}
      />
    </Box>
  )
}
