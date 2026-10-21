import { Box, Paper, Skeleton, Typography, useTheme } from '@mui/material'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { formatMoney } from '@/shared/utils/formatMoney'
import { AccessibleDataTable } from './AccessibleDataTable'
import { analyticsCardSx } from './analyticsStyles'
import { ChartTooltip } from './ChartTooltip'
import { CHART_COLORS, compactNumber } from './chartPalette'
import { toFiniteNumber } from './dashboardVisuals'
import { dashboardChartTitleSx, dashboardKpiLabelSx } from './dashboardTypography'

interface LoansIssuedVsRepaidChartProps {
  issuedAmount: string | number
  repaidAmount: string | number
  currency?: string
  loading?: boolean
  /** Shown as a callout under the chart. Outstanding principal is a balance, not a share of the bars. */
  outstandingPrincipal?: string | number | null
}

const CHART_HEIGHT = 170

/**
 * Compact bars for this month's loan flows. Issued and repaid are separate flows (not parts of one
 * total), so this is a bar chart, never a pie.
 */
export function LoansIssuedVsRepaidChart({
  issuedAmount,
  repaidAmount,
  currency = 'RWF',
  loading,
  outstandingPrincipal,
}: LoansIssuedVsRepaidChartProps) {
  const { t } = useTranslation()
  const theme = useTheme()
  const issued = toFiniteNumber(issuedAmount)
  const repaid = toFiniteNumber(repaidAmount)
  const empty = issued === 0 && repaid === 0
  const money = (value: number) => formatMoney(value, { currency })

  const data = useMemo(
    () => [
      { name: t('dashboard.insights.loansIssued'), amount: issued, fill: CHART_COLORS.blue },
      { name: t('dashboard.insights.loansRepaid'), amount: repaid, fill: CHART_COLORS.orange },
    ],
    [issued, repaid, t],
  )

  return (
    <Paper
      elevation={0}
      data-testid="loans-issued-vs-repaid-chart"
      sx={analyticsCardSx}
    >
      <Typography variant="subtitle1" sx={[dashboardChartTitleSx, { mb: 1.25 }]}>
        {t('dashboard.insights.issuedVsRepaidTitle')}
      </Typography>

      {loading ? (
        <Skeleton variant="rounded" height={CHART_HEIGHT} />
      ) : empty ? (
        <Box sx={{ py: 1.5, mb: 1.5 }} data-testid="loans-issued-vs-repaid-empty">
          <Typography variant="body2" color="text.secondary">
            {t('dashboard.insights.issuedVsRepaidEmpty')}
          </Typography>
        </Box>
      ) : (
        <>
          <Box aria-hidden="true" sx={{ width: '100%', height: CHART_HEIGHT }}>
            <ResponsiveContainer>
              <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke={theme.palette.divider} />
                <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: theme.palette.text.secondary }} />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  width={44}
                  tickFormatter={(value: unknown) => compactNumber(value)}
                  tick={{ fontSize: 12, fill: theme.palette.text.secondary }}
                />
                <Tooltip
                  cursor={{ fill: theme.palette.action.hover }}
                  content={({ active, payload }) => {
                    const item = active ? payload?.[0]?.payload : undefined
                    if (!item) return null
                    return (
                      <ChartTooltip
                        title={item.name}
                        lines={[{ label: t('dashboard.insights.amount'), value: money(item.amount), color: item.fill }]}
                      />
                    )
                  }}
                />
                <Bar dataKey="amount" radius={[4, 4, 0, 0]} maxBarSize={56} isAnimationActive={false}>
                  {data.map((entry) => (
                    <Cell key={entry.name} fill={entry.fill} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </Box>
          <AccessibleDataTable
            caption={t('dashboard.insights.issuedVsRepaidTitle')}
            columns={[t('dashboard.chartData.metric'), t('dashboard.insights.amount')]}
            rows={data.map((entry) => ({ key: entry.name, cells: [entry.name, money(entry.amount)] }))}
          />
        </>
      )}

      {outstandingPrincipal !== undefined && outstandingPrincipal !== null && !loading ? (
        <Box
          data-testid="loan-outstanding-callout"
          sx={{ mt: 'auto', pt: 1.5, borderTop: '1px solid', borderColor: 'divider' }}
        >
          <Typography variant="caption" color="text.secondary" sx={{ ...dashboardKpiLabelSx, textTransform: 'uppercase' }}>
            {t('dashboard.glance.outstandingPrincipal')}
          </Typography>
          <Typography variant="h6" component="p" sx={{ fontSize: '1rem', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
            {money(toFiniteNumber(outstandingPrincipal))}
          </Typography>
        </Box>
      ) : null}
    </Paper>
  )
}
