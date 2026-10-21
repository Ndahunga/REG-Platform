import GavelIcon from '@mui/icons-material/Gavel'
import { Box, Paper, Skeleton, Stack, Typography, useTheme } from '@mui/material'
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
import type { FinesInsights } from '@/shared/types/dashboard'
import { formatMoney } from '@/shared/utils/formatMoney'
import { AccessibleDataTable } from './AccessibleDataTable'
import { analyticsCardSx } from './analyticsStyles'
import { ChartTooltip } from './ChartTooltip'
import { CHART_COLORS, compactNumber } from './chartPalette'
import { fineActivityData } from './dashboardVisuals'
import { dashboardChartTitleSx } from './dashboardTypography'

interface FineActivityCardProps {
  fines: FinesInsights | undefined
  currency?: string
  loading?: boolean
}

const CHART_HEIGHT = 140

/**
 * This month's fine flows as bars: issued vs collected. They are separate flows (collections can settle
 * older fines), so they are not drawn as parts of one whole.
 */
export function FineActivityCard({ fines, currency = 'RWF', loading }: FineActivityCardProps) {
  const { t } = useTranslation()
  const theme = useTheme()
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, { currency })
  const activity = useMemo(() => fineActivityData(fines), [fines])

  const data = useMemo(
    () => [
      { name: t('dashboard.insights.finesIssued'), amount: activity.issued, fill: CHART_COLORS.red },
      { name: t('dashboard.insights.finesCollected'), amount: activity.collected, fill: CHART_COLORS.green },
    ],
    [activity.collected, activity.issued, t],
  )

  return (
    <Paper
      elevation={0}
      data-testid="fine-activity-card"
      sx={analyticsCardSx}
    >
      <Stack direction="row" spacing={1} sx={{ mb: 1.25, alignItems: 'center' }}>
        <GavelIcon fontSize="small" color="action" />
        <Typography variant="subtitle1" sx={dashboardChartTitleSx}>
          {t('dashboard.insights.fineActivityTitle')}
        </Typography>
      </Stack>

      {loading || !fines ? (
        <Skeleton variant="rounded" height={CHART_HEIGHT} />
      ) : (
        <>
          {activity.hasActivity ? (
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
          ) : (
            <Box data-testid="fine-activity-empty" sx={{ py: 1.5 }}>
              <Typography variant="body2" color="text.secondary">
                {t('dashboard.glance.fineActivityEmpty')}
              </Typography>
            </Box>
          )}

          <Typography variant="body2" sx={{ fontWeight: 600, mt: 'auto', pt: 1.5, overflowWrap: 'anywhere' }} data-testid="fines-issued-value">
            {t('dashboard.insights.finesIssued')}:{' '}
            {t('dashboard.insights.finesIssuedValue', {
              count: fines.issuedCountCurrentMonth,
              amount: money(fines.issuedAmountCurrentMonth),
            })}
          </Typography>
          <AccessibleDataTable
            caption={t('dashboard.insights.fineActivityTitle')}
            columns={[t('dashboard.chartData.metric'), t('dashboard.insights.amount')]}
            rows={data.map((entry) => ({ key: entry.name, cells: [entry.name, money(entry.amount)] }))}
          />
        </>
      )}
    </Paper>
  )
}
