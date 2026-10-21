import { Box, useTheme } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { AccessibleDataTable, MemberNameCell } from './AccessibleDataTable'
import { ChartTooltip } from './ChartTooltip'
import { CHART_COLORS, compactNumber, truncateLabel } from './chartPalette'
import type { RankedBarDatum } from './dashboardVisuals'

export interface RankedDetailColumn {
  key: string
  label: string
  format?: (value: number | string) => string
}

interface RankedBarChartProps {
  data: RankedBarDatum[]
  /** Caption of the accessible data table. */
  ariaLabel: string
  /** Header/tooltip label of the plotted value, e.g. "Contributions". */
  valueLabel: string
  /** Header of the first column, e.g. "Member". */
  nameLabel: string
  formatValue: (value: number) => string
  detailColumns?: RankedDetailColumn[]
  color?: string
}

const ROW_HEIGHT = 36
const Y_AXIS_WIDTH = 116

interface TickProps {
  x?: number | string
  y?: number | string
  payload?: { index: number; value: string }
}

/**
 * Horizontal bar chart for ranked comparisons (never a pie). Member names are readable, the tooltip carries
 * the exact amount and secondary details, and the same data is available as an accessible table that also
 * holds the real member links.
 */
export function RankedBarChart({
  data,
  ariaLabel,
  valueLabel,
  nameLabel,
  formatValue,
  detailColumns = [],
  color = CHART_COLORS.blue,
}: RankedBarChartProps) {
  const { t } = useTranslation()
  const theme = useTheme()
  const height = Math.max(120, data.length * ROW_HEIGHT + 24)
  const fmt = (column: RankedDetailColumn, value: number | string | undefined) =>
    value === undefined ? '—' : column.format ? column.format(value) : String(value)

  const renderTick = ({ x = 0, y = 0, payload }: TickProps) => {
    const datum = payload ? data[payload.index] : undefined
    const text = (
      <text
        x={0}
        y={0}
        dy={4}
        textAnchor="end"
        fontSize={12}
        fill={datum?.href ? theme.palette.primary.main : theme.palette.text.primary}
      >
        {truncateLabel(String(payload?.value ?? ''), 16)}
        <title>{String(payload?.value ?? '')}</title>
      </text>
    )
    return (
      <g transform={`translate(${x},${y})`}>
        {datum?.href ? (
          <RouterLink to={datum.href} tabIndex={-1}>
            {text}
          </RouterLink>
        ) : (
          text
        )}
      </g>
    )
  }

  return (
    <Box>
      <Box aria-hidden="true" sx={{ width: '100%', height }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            layout="vertical"
            margin={{ top: 4, right: 56, left: 0, bottom: 4 }}
            barCategoryGap="22%"
          >
            <CartesianGrid horizontal={false} stroke={theme.palette.divider} strokeDasharray="3 3" />
            <XAxis type="number" hide domain={[0, 'dataMax']} />
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
                const datum: RankedBarDatum | undefined = active ? payload?.[0]?.payload : undefined
                if (!datum) return null
                return (
                  <ChartTooltip
                    title={datum.label}
                    lines={[
                      { label: valueLabel, value: formatValue(datum.value), color },
                      ...detailColumns.map((column) => ({
                        label: column.label,
                        value: fmt(column, datum.meta[column.key]),
                      })),
                    ]}
                  />
                )
              }}
            />
            <Bar dataKey="value" fill={color} radius={[0, 4, 4, 0]} maxBarSize={20} isAnimationActive={false}>
              <LabelList
                dataKey="value"
                position="right"
                formatter={(value: unknown) => compactNumber(value)}
                style={{ fontSize: 12, fill: theme.palette.text.secondary }}
              />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </Box>

      <AccessibleDataTable
        caption={ariaLabel}
        columns={[nameLabel, t('dashboard.chartData.rank'), valueLabel, ...detailColumns.map((c) => c.label)]}
        rows={data.map((datum) => ({
          key: datum.id,
          cells: [
            <MemberNameCell key="n" label={datum.label} href={datum.href} />,
            datum.rank,
            formatValue(datum.value),
            ...detailColumns.map((column) => fmt(column, datum.meta[column.key])),
          ],
        }))}
      />
    </Box>
  )
}
