import { Box, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { LoanApplicationForm } from '@/shared/types/loan'
import { formatMoney } from '@/shared/utils/formatMoney'

function Row({ label, value }: { label: string; value?: string | null }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 500 }}>
        {value || '—'}
      </Typography>
    </Box>
  )
}

interface LoanApplicationFormViewProps {
  form?: LoanApplicationForm | null
  amount?: string | number | null
  purpose?: string | null
  termMonths?: number | string | null
}

export function LoanApplicationFormView({
  form,
  amount,
  purpose,
  termMonths,
}: LoanApplicationFormViewProps) {
  const { t } = useTranslation()
  if (!form) return null
  const requested = amount ?? form.requestedAmount
  const shownPurpose = purpose ?? form.purpose
  const term = termMonths ?? form.termMonths

  return (
    <Box
      sx={{
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {t('loans.application.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('loans.application.description')}
      </Typography>
      <Stack spacing={1.5}>
        <Row label={t('loans.application.cooperative')} value={form.cooperativeName} />
        <Row label={t('loans.application.member')} value={form.memberFullName} />
        <Row label={t('members.fields.nationalId')} value={form.nationalId} />
        <Row label={t('members.fields.phone')} value={form.phone} />
        <Row label={t('members.fields.email')} value={form.email} />
        <Row label={t('members.fields.address')} value={form.address} />
        <Row label={t('members.fields.membershipDate')} value={form.membershipDate} />
        <Row
          label={t('loans.fields.amount')}
          value={
            requested != null && requested !== ''
              ? formatMoney(requested, { currency: form.currency || 'RWF' })
              : '—'
          }
        />
        <Row
          label={t('loans.fields.termMonths')}
          value={term != null && term !== '' ? String(term) : '—'}
        />
        <Row label={t('loans.fields.purpose')} value={shownPurpose} />
        <Row
          label={t('loans.fields.interestRate')}
          value={
            form.interestRatePercent != null ? `${form.interestRatePercent}%` : '—'
          }
        />
      </Stack>
    </Box>
  )
}
