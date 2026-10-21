import { Box, Paper, Stack, Typography, useTheme } from '@mui/material'
import { useTranslation } from 'react-i18next'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { PlatformOverview } from '@/shared/types/dashboard'
import {
  COOPERATIVE_STATUS_COLORS,
  PENDING_WORK_COLORS,
  cooperativeStatusSlices,
  pendingWorkBars,
} from './platformOverviewCharts'
import { dashboardChartTitleSx } from './dashboardTypography'

interface SuperAdminOverviewChartsProps {
  overview: PlatformOverview
}

export function SuperAdminOverviewCharts({ overview }: SuperAdminOverviewChartsProps) {
  const { t } = useTranslation()
  const theme = useTheme()
  const pieData = cooperativeStatusSlices(overview).map((slice) => ({
    ...slice,
    name: t(`status.${slice.key}`, { defaultValue: slice.key }),
  }))
  const barData = pendingWorkBars(overview).map((bar) => ({
    ...bar,
    name: t(`dashboard.super.pending.${bar.key}`),
    fill: PENDING_WORK_COLORS[bar.key],
  }))
  const tick = theme.palette.text.secondary
  const tooltipStyle = {
    backgroundColor: theme.palette.background.paper,
    border: `1px solid ${theme.palette.divider}`,
    borderRadius: 8,
  }

  return (
    <Box
      sx={{
        display: 'grid',
        gap: 2,
        mb: 3,
        gridTemplateColumns: { xs: '1fr', md: '1fr 1.2fr' },
      }}
    >
      <Paper elevation={0} sx={{ p: { xs: 2, md: 2.5 }, border: '1px solid', borderColor: 'divider' }}>
        <Typography variant="h6" component="h3" gutterBottom sx={dashboardChartTitleSx}>
          {t('dashboard.super.statusChartTitle')}
        </Typography>
        {pieData.length === 0 ? (
          <Typography color="text.secondary">{t('dashboard.super.chartsEmpty')}</Typography>
        ) : (
          <Box sx={{ width: '100%', height: 280 }}>
            <ResponsiveContainer>
              <PieChart>
                <Pie
                  data={pieData}
                  dataKey="value"
                  nameKey="name"
                  cx="50%"
                  cy="50%"
                  innerRadius={56}
                  outerRadius={92}
                  paddingAngle={2}
                >
                  {pieData.map((slice) => (
                    <Cell key={slice.key} fill={COOPERATIVE_STATUS_COLORS[slice.key]} />
                  ))}
                </Pie>
                <Tooltip contentStyle={tooltipStyle} />
                <Legend />
              </PieChart>
            </ResponsiveContainer>
          </Box>
        )}
      </Paper>

      <Paper elevation={0} sx={{ p: { xs: 2, md: 2.5 }, border: '1px solid', borderColor: 'divider' }}>
        <Stack spacing={0.5} sx={{ mb: 1 }}>
          <Typography variant="h6" component="h3" sx={dashboardChartTitleSx}>
            {t('dashboard.super.pendingChartTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('dashboard.super.pendingChartHint')}
          </Typography>
        </Stack>
        <Box sx={{ width: '100%', height: 280 }}>
          <ResponsiveContainer>
            <BarChart data={barData} layout="vertical" margin={{ top: 8, right: 16, left: 8, bottom: 8 }}>
              <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="rgba(27,77,140,0.12)" />
              <XAxis type="number" allowDecimals={false} tick={{ fill: tick, fontSize: 12 }} tickLine={false} axisLine={false} />
              <YAxis
                type="category"
                dataKey="name"
                width={88}
                tick={{ fill: tick, fontSize: 12 }}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip contentStyle={tooltipStyle} />
              <Bar dataKey="value" radius={[0, 4, 4, 0]} maxBarSize={18}>
                {barData.map((bar) => (
                  <Cell key={bar.key} fill={bar.fill} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </Box>
      </Paper>
    </Box>
  )
}
