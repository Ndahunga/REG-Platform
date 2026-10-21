import {
  Box,
  Button,
  Chip,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchSocialFundReport } from '@/shared/api/socialFund'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { MetricCard } from '@/shared/components/MetricCard'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { SocialContribution, SocialDisbursement } from '@/shared/types/socialFund'
import {
  socialContributionDisplayName,
  socialDisbursementDisplayName,
} from '@/shared/types/socialFund'
import { formatMoney } from '@/shared/utils/formatMoney'
import { todayIsoDate } from '@/shared/utils/filterValidation'
import { socialStatusColor } from './socialFundHelpers'
import {
  socialFundReportDefaults,
  socialFundReportSchema,
  type SocialFundReportFormValues,
} from './socialFundFormSchemas'

interface SocialFundReportPanelProps {
  cooperativeId: string
}

export function SocialFundReportPanel({ cooperativeId }: SocialFundReportPanelProps) {
  const { t } = useTranslation()
  const [range, setRange] = useState<SocialFundReportFormValues | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SocialFundReportFormValues>({
    defaultValues: socialFundReportDefaults(),
    resolver: yupResolver(socialFundReportSchema),
  })

  const reportQuery = useQuery({
    queryKey: ['social-fund', 'report', cooperativeId, range?.from, range?.to],
    queryFn: () =>
      fetchSocialFundReport(cooperativeId, {
        from: range!.from,
        to: range!.to,
      }),
    enabled: Boolean(cooperativeId && range?.from && range?.to),
  })

  const contributionColumns: TableColumn<SocialContribution>[] = [
    {
      id: 'member',
      label: t('socialFund.fields.member'),
      render: (row) => socialContributionDisplayName(row),
    },
    {
      id: 'amount',
      label: t('socialFund.fields.amount'),
      render: (row) => formatMoney(row.amount),
    },
    {
      id: 'date',
      label: t('socialFund.fields.contributionDate'),
      render: (row) => row.contributionDate || '—',
      hideOnMobile: true,
    },
    {
      id: 'status',
      label: t('socialFund.fields.status'),
      render: (row) => (
        <Chip
          size="small"
          color={socialStatusColor(String(row.status))}
          label={t(`socialFund.status.${row.status}`, { defaultValue: row.status })}
        />
      ),
    },
  ]

  const disbursementColumns: TableColumn<SocialDisbursement>[] = [
    {
      id: 'beneficiary',
      label: t('socialFund.fields.beneficiary'),
      render: (row) => socialDisbursementDisplayName(row),
    },
    {
      id: 'amount',
      label: t('socialFund.fields.amount'),
      render: (row) => formatMoney(row.amount),
    },
    {
      id: 'reason',
      label: t('socialFund.fields.reason'),
      render: (row) => row.reason || '—',
      hideOnMobile: true,
    },
    {
      id: 'date',
      label: t('socialFund.fields.disbursementDate'),
      render: (row) => row.disbursementDate || '—',
      hideOnMobile: true,
    },
  ]

  const report = reportQuery.data
  const currency = report?.currency || 'RWF'

  return (
    <Stack spacing={2.5}>
      <Box
        component="form"
        onSubmit={handleSubmit((values) => setRange(values))}
        sx={{
          maxWidth: 560,
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1,
        }}
      >
        <Typography variant="h6" gutterBottom>
          {t('socialFund.report.title')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('socialFund.report.description')}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField
            type="date"
            label={t('socialFund.report.from')}
            slotProps={{
              inputLabel: { shrink: true },
              htmlInput: { max: todayIsoDate() },
            }}
            error={Boolean(errors.from)}
            helperText={errors.from?.message}
            {...register('from')}
            fullWidth
          />
          <TextField
            type="date"
            label={t('socialFund.report.to')}
            slotProps={{
              inputLabel: { shrink: true },
              htmlInput: { max: todayIsoDate() },
            }}
            error={Boolean(errors.to)}
            helperText={errors.to?.message}
            {...register('to')}
            fullWidth
          />
          <Button type="submit" variant="contained" sx={{ alignSelf: { sm: 'center' } }}>
            {t('socialFund.report.run')}
          </Button>
        </Stack>
      </Box>

      {!range ? (
        <Typography color="text.secondary">{t('socialFund.report.prompt')}</Typography>
      ) : null}

      {range && reportQuery.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}
      {range && reportQuery.isError ? (
        <ErrorState
          message={getErrorMessage(reportQuery.error)}
          onRetry={() => void reportQuery.refetch()}
        />
      ) : null}

      {range && report && !reportQuery.isLoading ? (
        <Stack spacing={2.5}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} useFlexGap>
            <Box sx={{ flex: 1 }}>
              <MetricCard
                label={t('socialFund.report.approvedContributions')}
                value={formatMoney(report.totalApprovedContributions, { currency })}
              />
            </Box>
            <Box sx={{ flex: 1 }}>
              <MetricCard
                label={t('socialFund.report.approvedDisbursements')}
                value={formatMoney(report.totalApprovedDisbursements, { currency })}
              />
            </Box>
            {report.netChange != null ? (
              <Box sx={{ flex: 1 }}>
                <MetricCard
                  label={t('socialFund.report.netChange')}
                  value={formatMoney(report.netChange, { currency })}
                />
              </Box>
            ) : null}
          </Stack>

          <Box>
            <Typography variant="h6" gutterBottom>
              {t('socialFund.report.contributionsHeading')}
            </Typography>
            <ResponsiveTable
              columns={contributionColumns}
              rows={report.contributions ?? []}
              getRowId={(row) => row.id}
              emptyTitle={t('socialFund.contributions.emptyTitle')}
              emptyDescription={t('socialFund.report.emptyPeriod')}
            />
          </Box>

          <Box>
            <Typography variant="h6" gutterBottom>
              {t('socialFund.report.disbursementsHeading')}
            </Typography>
            <ResponsiveTable
              columns={disbursementColumns}
              rows={report.disbursements ?? []}
              getRowId={(row) => row.id}
              emptyTitle={t('socialFund.disbursements.emptyTitle')}
              emptyDescription={t('socialFund.report.emptyPeriod')}
            />
          </Box>
        </Stack>
      ) : null}
    </Stack>
  )
}
