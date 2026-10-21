import { Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import {
  accessUntilIso,
  effectiveSubscriptionStatus,
  formatSubscriptionDate,
} from '@/features/subscription/subscriptionAccess'
import type { CooperativeSubscription } from '@/shared/types/cooperative'
import { formatMoney } from '@/shared/utils/formatMoney'
import type { BillingPlanQuote, BillingPlansResponse } from '@/shared/types/billing'
import { planByCycle } from '@/shared/api/billing'
import { subscriptionStatusChipColor } from './billingAccess'

function Detail({ label, value }: { label: string; value?: string | null }) {
  if (!value) return null
  return (
    <Stack spacing={0.25}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2">{value}</Typography>
    </Stack>
  )
}

function headline(
  status: ReturnType<typeof effectiveSubscriptionStatus>,
  subscription: CooperativeSubscription | null,
  monthly: BillingPlanQuote | undefined,
  annual: BillingPlanQuote | undefined,
  currency: string,
  t: (key: string, options?: Record<string, unknown>) => string,
): { title: string; body: string } {
  const until = formatSubscriptionDate(accessUntilIso(subscription))
  const remaining = subscription?.daysRemaining
  switch (status) {
    case 'TRIAL':
      return {
        title: t('subscription.status.TRIAL'),
        body: [
          remaining != null ? t('subscription.billing.daysRemaining', { count: remaining }) : '',
          until ? t('subscription.billing.trialEnds', { date: until }) : '',
        ]
          .filter(Boolean)
          .join(' · '),
      }
    case 'ACTIVE':
      if (subscription?.billingCycle === 'ANNUAL' && annual) {
        return {
          title: t('subscription.billing.activeAnnual'),
          body: [
            `${formatMoney(annual.amount, { currency })} ${t('subscription.billing.perYear')}`,
            Number(annual.discountPercent) > 0
              ? t('subscription.billing.savePercent', { percent: Number(annual.discountPercent) })
              : '',
            until ? t('subscription.billing.periodEnds', { date: until }) : '',
          ]
            .filter(Boolean)
            .join(' · '),
        }
      }
      return {
        title: t('subscription.billing.activeMonthly'),
        body: [
          monthly
            ? `${formatMoney(monthly.amount, { currency })} ${t('subscription.billing.perMonth')}`
            : '',
          until ? t('subscription.billing.periodEnds', { date: until }) : '',
        ]
          .filter(Boolean)
          .join(' · '),
      }
    case 'PAST_DUE':
      return {
        title: t('subscription.status.PAST_DUE'),
        body: t('subscription.billing.pastDueAccess', { date: until || t('subscription.unknownDate') }),
      }
    case 'EXPIRED':
      return {
        title: t('subscription.status.EXPIRED'),
        body: t('subscription.billing.expiredReadOnly'),
      }
    case 'CANCELED':
      return {
        title: t('subscription.status.CANCELED'),
        body: until
          ? t('subscription.canceled.body', { date: until })
          : t('subscription.status.CANCELED'),
      }
    default:
      return {
        title: t('subscription.status.NONE'),
        body: t('subscription.billing.noneRequired'),
      }
  }
}

interface CurrentSubscriptionCardProps {
  subscription: CooperativeSubscription | null
  plans: BillingPlansResponse | undefined
}

export function CurrentSubscriptionCard({ subscription, plans }: CurrentSubscriptionCardProps) {
  const { t } = useTranslation()
  const status = effectiveSubscriptionStatus(subscription)
  const currency = plans?.currency || 'RWF'
  const monthly = planByCycle(plans?.plans, 'MONTHLY')
  const annual = planByCycle(plans?.plans, 'ANNUAL')
  const copy = headline(status, subscription, monthly, annual, currency, t)
  const cycle = subscription?.billingCycle

  return (
    <Card variant="outlined" data-testid="current-subscription-card">
      <CardContent>
        <Stack spacing={2}>
          <Stack
            direction="row"
            spacing={1}
            useFlexGap
            sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          >
            <Typography variant="h6" component="h2">
              {t('subscription.billing.currentTitle')}
            </Typography>
            <Chip
              size="small"
              color={subscriptionStatusChipColor(status)}
              label={t(`subscription.status.${status}`)}
              aria-label={`${t('subscription.billing.status')}: ${t(`subscription.status.${status}`)}`}
            />
          </Stack>
          <Typography variant="subtitle1">{copy.title}</Typography>
          <Typography variant="body2" color="text.secondary">
            {copy.body}
          </Typography>
          {status === 'TRIAL' ? (
            <Typography variant="body2">{t('subscription.billing.trialFree')}</Typography>
          ) : null}
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            useFlexGap
            sx={{ flexWrap: 'wrap' }}
          >
            <Detail label={t('subscription.billing.status')} value={t(`subscription.status.${status}`)} />
            <Detail
              label={t('subscription.billing.plan')}
              value={
                cycle === 'ANNUAL'
                  ? t('subscription.billing.activeAnnual')
                  : cycle === 'MONTHLY'
                    ? t('subscription.billing.activeMonthly')
                    : status === 'TRIAL'
                      ? t('subscription.status.TRIAL')
                      : undefined
              }
            />
            <Detail
              label={t('subscription.billing.cycle')}
              value={cycle ? t(`subscription.cycle.${cycle}`) : undefined}
            />
            <Detail
              label={t('subscription.billing.trialStart')}
              value={formatSubscriptionDate(subscription?.trialStartedAt)}
            />
            <Detail
              label={t('subscription.billing.trialEnd')}
              value={formatSubscriptionDate(subscription?.trialEndsAt)}
            />
            <Detail
              label={t('subscription.billing.periodStart')}
              value={formatSubscriptionDate(subscription?.currentPeriodStartedAt)}
            />
            <Detail
              label={t('subscription.billing.periodEnd')}
              value={formatSubscriptionDate(subscription?.currentPeriodEndsAt)}
            />
            <Detail
              label={t('subscription.billing.pastDueUntil')}
              value={formatSubscriptionDate(subscription?.pastDueUntil)}
            />
            <Detail
              label={t('subscription.billing.canceledAt')}
              value={formatSubscriptionDate(subscription?.canceledAt)}
            />
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  )
}
