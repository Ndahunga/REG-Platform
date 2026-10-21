import { Alert, Box, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { LoanInstallment, LoanSchedulePreview as LoanSchedulePreviewData } from '@/shared/types/loan'
import { EMPTY_MONEY_PLACEHOLDER, formatOptionalMoney } from '@/shared/utils/formatMoney'

interface LoanSchedulePreviewProps {
  schedule?: LoanSchedulePreviewData | null
  compact?: boolean
}

function displayDate(value?: string | null): string {
  return value?.trim() ? value : EMPTY_MONEY_PLACEHOLDER
}

export function LoanSchedulePreview({ schedule, compact = false }: LoanSchedulePreviewProps) {
  const { t } = useTranslation()
  if (!schedule) return null

  const columns: TableColumn<LoanInstallment>[] = [
    {
      id: 'installmentNumber',
      label: t('loans.schedule.installmentNumber'),
      render: (row) =>
        row.installmentNumber != null ? String(row.installmentNumber) : EMPTY_MONEY_PLACEHOLDER,
    },
    {
      id: 'dueDate',
      label: t('loans.fields.dueDate'),
      render: (row) => displayDate(row.dueDate),
    },
    {
      id: 'opening',
      label: t('loans.schedule.openingBalance'),
      render: (row) => formatOptionalMoney(row.openingPrincipalBalance),
      hideOnMobile: true,
    },
    {
      id: 'principal',
      label: t('loans.schedule.principalComponent'),
      render: (row) => formatOptionalMoney(row.principalComponent),
    },
    {
      id: 'interest',
      label: t('loans.schedule.interestComponent'),
      render: (row) => formatOptionalMoney(row.interestComponent),
    },
    {
      id: 'penaltyDue',
      label: t('loans.schedule.penaltyDue'),
      render: (row) => formatOptionalMoney(row.penaltyDue),
    },
    {
      id: 'total',
      label: t('loans.schedule.paymentAmount'),
      render: (row) => formatOptionalMoney(row.paymentAmount),
    },
    {
      id: 'principalPaid',
      label: t('loans.schedule.principalPaid'),
      render: (row) => formatOptionalMoney(row.principalPaid),
      hideOnMobile: true,
    },
    {
      id: 'interestPaid',
      label: t('loans.schedule.interestPaid'),
      render: (row) => formatOptionalMoney(row.interestPaid),
      hideOnMobile: true,
    },
    {
      id: 'penaltyPaid',
      label: t('loans.schedule.penaltyPaid'),
      render: (row) => formatOptionalMoney(row.penaltyPaid),
      hideOnMobile: true,
    },
    {
      id: 'paid',
      label: t('loans.schedule.amountPaid'),
      render: (row) => formatOptionalMoney(row.amountPaid),
      hideOnMobile: true,
    },
    {
      id: 'balance',
      label: t('loans.schedule.balance'),
      render: (row) =>
        formatOptionalMoney(row.remainingAmount ?? row.balance ?? row.paymentAmount),
    },
    {
      id: 'status',
      label: t('loans.fields.status'),
      render: (row) =>
        t(`loans.schedule.status.${row.status ?? 'PENDING'}`, {
          defaultValue: String(row.status ?? 'PENDING'),
        }),
    },
  ]

  return (
    <Box>
      <Alert severity="info" sx={{ mb: compact ? 1.5 : 2 }}>
        {t('loans.schedule.flatInterestNotice')}
        {!schedule.scheduleFinalized ? ` ${t('loans.schedule.previewDisclaimer')}` : ''}
        {` ${t('loans.schedule.contractualNotice')}`}
      </Alert>
      <Stack spacing={0.75} sx={{ mb: schedule.installments?.length ? 2 : 0 }}>
        {schedule.repaymentDateModel ? (
          <Typography variant="body2">
            {t('loans.fields.repaymentDateModel')}:{' '}
            {t(`loans.schedule.repaymentModel.${schedule.repaymentDateModel}`, {
              defaultValue: String(schedule.repaymentDateModel),
            })}
          </Typography>
        ) : null}
        <Typography variant="body2">
          {t('loans.schedule.regularMonthlyInterest')}:{' '}
          {formatOptionalMoney(schedule.regularMonthlyInterest)}
        </Typography>
        {schedule.prorataEnabled ? (
          <Typography variant="body2">
            {t('loans.schedule.firstPeriodInterest')}:{' '}
            {formatOptionalMoney(schedule.firstPeriodInterest)}
            {schedule.firstPeriodDays != null
              ? ` (${t('loans.schedule.firstPeriodDaysValue', { days: schedule.firstPeriodDays })})`
              : ''}
          </Typography>
        ) : null}
        <Typography variant="body2">
          {t('loans.schedule.totalInterest')}: {formatOptionalMoney(schedule.totalInterest)}
        </Typography>
        <Typography variant="body2">
          {t('loans.schedule.totalRepayment')}: {formatOptionalMoney(schedule.totalRepayment)}
        </Typography>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {t('loans.schedule.equalInstallment')}:{' '}
          {formatOptionalMoney(schedule.equalInstallmentAmount)}
        </Typography>
      </Stack>
      {schedule.installments && schedule.installments.length > 0 && !compact ? (
        <ResponsiveTable
          columns={columns}
          rows={schedule.installments}
          getRowId={(row) => String(row.id ?? row.installmentNumber ?? 'row')}
        />
      ) : null}
    </Box>
  )
}
