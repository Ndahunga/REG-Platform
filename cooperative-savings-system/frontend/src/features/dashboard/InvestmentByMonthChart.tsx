import { Box, Paper, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useMemo } from 'react'
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
import { fetchInvestmentsByMonthChart } from '@/shared/api/dashboard'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { formatMoney } from '@/shared/utils/formatMoney'
import { dashboardChartTitleSx } from './dashboardTypography'

interface InvestmentByMonthChartProps {
  cooperativeId: string
  year: number
  currency?: string
}

export function InvestmentByMonthChart({
  cooperativeId,
  year,
  currency = 'RWF',
}: InvestmentByMonthChartProps) {
  const { t } = useTranslation()

  const chartQuery = useQuery({
    queryKey: ['dashboard', 'charts', 'investments-by-month', cooperativeId, year],
    queryFn: () => fetchInvestmentsByMonthChart(cooperativeId, year),
    enabled: Boolean(cooperativeId),
  })

  const chartData = useMemo(() => {
    const byMonth = new Map(
      (chartQuery.data ?? []).map((point) => [
        point.month,
        {
          capitalDeployed: Number(point.capitalDeployed) || 0,
          investmentCount: Number(point.investmentCount) || 0,
        },
      ]),
    )
    return Array.from({ length: 12 }, (_, i) => {
      const month = i + 1
      const values = byMonth.get(month) ?? { capitalDeployed: 0, investmentCount: 0 }
      return {
        month,
        label: dayjs().month(i).format('MMM'),
        capitalDeployed: values.capitalDeployed,
        investmentCount: values.investmentCount,
      }
    })
  }, [chartQuery.data])

  const empty = chartData.every((d) => d.capitalDeployed === 0 && d.investmentCount === 0)

  return (
    <Paper
      elevation={0}
      data-testid="investments-by-month-chart"
      sx={{
        p: { xs: 2.5, md: 3 },
        height: '100%',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
      }}
    >
      <Typography variant="h6" component="h3" sx={[dashboardChartTitleSx, { mb: 1.5 }]}>
        {t('dashboard.advancedInsights.investmentsByMonth')}
      </Typography>

      {chartQuery.isError ? (
        <ErrorState
          message={getErrorMessage(chartQuery.error)}
          onRetry={() => void chartQuery.refetch()}
        />
      ) : chartQuery.isLoading ? (
        <Box sx={{ height: 260, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Typography color="text.secondary">{t('common.loading')}</Typography>
        </Box>
      ) : empty ? (
        <Box
          sx={{ height: 260, display: 'flex', alignItems: 'center', justifyContent: 'center', px: 2 }}
          data-testid="investments-by-month-empty"
        >
          <Typography color="text.secondary" align="center">
            {t('dashboard.advancedInsights.investmentsByMonthEmpty')}
          </Typography>
        </Box>
      ) : (
        <Box sx={{ width: '100%', height: 260 }}>
          <ResponsiveContainer>
            <BarChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="label" tickLine={false} axisLine={false} />
              <YAxis tickLine={false} axisLine={false} width={56} />
              <Tooltip
                formatter={(value, _name, item) => {
                  const count = Number(item?.payload?.investmentCount ?? 0)
                  return [
                    `${formatMoney(String(value ?? 0), { currency })} (${t(
                      'dashboard.advancedInsights.investmentCountTooltip',
                      { count },
                    )})`,
                    t('dashboard.advancedInsights.capitalDeployed'),
                  ]
                }}
              />
              <Bar dataKey="capitalDeployed" fill="#FF7A00" radius={[4, 4, 0, 0]} maxBarSize={36} />
            </BarChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Paper>
  )
}
