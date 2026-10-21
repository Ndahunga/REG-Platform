import { Box, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { fetchMonthlyContributionsChart } from '@/shared/api/dashboard'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { formatMoney } from '@/shared/utils/formatMoney'
import { dashboardChartTitleSx } from './dashboardTypography'

interface MonthlyContributionsChartProps {
  cooperativeId: string
  currency?: string
}

/** Cooperative-wide monthly contributions bar chart, reused on both admin and member dashboards. */
export function MonthlyContributionsChart({
  cooperativeId,
  currency = 'RWF',
}: MonthlyContributionsChartProps) {
  const { t } = useTranslation()
  const [chartYear, setChartYear] = useState(dayjs().year())

  const chartQuery = useQuery({
    queryKey: ['dashboard', 'charts', 'monthly-contributions', cooperativeId, chartYear],
    queryFn: () => fetchMonthlyContributionsChart(cooperativeId, chartYear),
    enabled: Boolean(cooperativeId),
  })

  const yearOptions = useMemo(() => {
    const current = dayjs().year()
    return Array.from({ length: 6 }, (_, i) => current - 3 + i)
  }, [])

  const chartData = useMemo(() => {
    const byMonth = new Map(
      (chartQuery.data ?? []).map((point) => [point.month, Number(point.totalPaid) || 0]),
    )
    return Array.from({ length: 12 }, (_, i) => {
      const month = i + 1
      return {
        month,
        label: dayjs().month(i).format('MMM'),
        totalPaid: byMonth.get(month) ?? 0,
      }
    })
  }, [chartQuery.data])

  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 2.5, md: 3.5 },
        border: '1px solid',
        borderColor: 'divider',
        background:
          'linear-gradient(135deg, rgba(15,92,92,0.06) 0%, rgba(250,247,241,1) 55%)',
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2, justifyContent: 'space-between', alignItems: { sm: 'center' } }}
      >
        <Typography variant="h6" component="h2" sx={dashboardChartTitleSx}>
          {t('dashboard.charts.monthlyContributions')}
        </Typography>
        <TextField
          select
          size="small"
          label={t('contributions.fields.year')}
          value={chartYear}
          onChange={(e) => setChartYear(Number(e.target.value))}
          sx={{ minWidth: 110 }}
        >
          {yearOptions.map((y) => (
            <MenuItem key={y} value={y}>
              {y}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {chartQuery.isError ? (
        <ErrorState
          message={getErrorMessage(chartQuery.error)}
          onRetry={() => void chartQuery.refetch()}
        />
      ) : chartQuery.isLoading ? (
        <Box sx={{ height: 280, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Typography color="text.secondary">{t('common.loading')}</Typography>
        </Box>
      ) : chartData.every((d) => d.totalPaid === 0) ? (
        <Box
          sx={{ height: 280, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          data-testid="monthly-contributions-empty"
        >
          <Typography color="text.secondary">{t('dashboard.charts.monthlyContributionsEmpty')}</Typography>
        </Box>
      ) : (
        <Box sx={{ width: '100%', height: 280 }}>
          <ResponsiveContainer>
            <BarChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="rgba(255,122,0,0.18)" />
              <XAxis dataKey="label" tickLine={false} axisLine={false} />
              <YAxis tickLine={false} axisLine={false} width={56} />
              <Tooltip
                formatter={(value) => [
                  formatMoney(String(value ?? 0), { currency }),
                  t('dashboard.charts.paid'),
                ]}
              />
              <Bar dataKey="totalPaid" fill="#1B4D8C" radius={[4, 4, 0, 0]} maxBarSize={36} />
            </BarChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Paper>
  )
}
