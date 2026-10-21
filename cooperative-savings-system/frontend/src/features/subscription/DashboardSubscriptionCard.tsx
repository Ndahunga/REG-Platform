import { Button, Card, CardContent, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { canManageBilling, billingCtaKey } from '@/features/billing/billingAccess'
import { ROUTES } from '@/shared/constants/routes'
import type { CooperativeSubscription } from '@/shared/types/cooperative'
import {
  accessUntilIso,
  effectiveSubscriptionStatus,
  formatSubscriptionDate,
} from './subscriptionAccess'

interface DashboardSubscriptionCardProps {
  subscription: CooperativeSubscription | null
  variant: 'leadership' | 'member'
}

export function DashboardSubscriptionCard({ subscription, variant }: DashboardSubscriptionCardProps) {
  const { t } = useTranslation()
  const roles = useAppSelector((s) => s.auth.user?.roles)
  const manage = canManageBilling(roles)
  const status = effectiveSubscriptionStatus(subscription)
  const until = formatSubscriptionDate(accessUntilIso(subscription))
  const cycle = subscription?.billingCycle

  if (variant === 'member' && status !== 'PAST_DUE' && status !== 'EXPIRED' && status !== 'NONE') {
    if (status === 'CANCELED' && subscription?.writeAllowed) return null
    if (status === 'TRIAL' || status === 'ACTIVE') return null
  }

  const body = (() => {
    switch (status) {
      case 'TRIAL':
        return t('subscription.dashboard.trial', {
          date: until || t('subscription.unknownDate'),
          count: subscription?.daysRemaining ?? 0,
        })
      case 'ACTIVE':
        return t('subscription.dashboard.active', {
          cycle: cycle ? t(`subscription.cycle.${cycle}`) : t('subscription.status.ACTIVE'),
          date: until || t('subscription.unknownDate'),
        })
      case 'PAST_DUE':
        return t('subscription.dashboard.pastDue', { date: until || t('subscription.unknownDate') })
      case 'CANCELED':
        return subscription?.writeAllowed
          ? t('subscription.dashboard.canceled', { date: until || t('subscription.unknownDate') })
          : t('subscription.expired.leadership')
      case 'NONE':
        return t('subscription.none.title')
      default:
        return variant === 'member' ? t('subscription.expired.member') : t('subscription.expired.leadership')
    }
  })()

  return (
    <Card variant="outlined" data-testid="dashboard-subscription-card" sx={{ mb: 2 }}>
      <CardContent>
        <Stack spacing={0.5}>
          <Typography variant="subtitle2">{t(`subscription.status.${status}`)}</Typography>
          <Typography variant="body2" color="text.secondary">
            {body}
          </Typography>
          {manage ? (
            <Button
              component={RouterLink}
              to={ROUTES.billing}
              size="small"
              variant="contained"
              sx={{ mt: 1, alignSelf: 'flex-start' }}
              data-testid="dashboard-billing-cta"
            >
              {t(billingCtaKey(status))}
            </Button>
          ) : (
            <Typography variant="body2" sx={{ mt: 1 }}>
              {t('subscription.dashboard.contactLeadership')}
            </Typography>
          )}
        </Stack>
      </CardContent>
    </Card>
  )
}
