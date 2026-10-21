import { useState } from 'react'
import CloseIcon from '@mui/icons-material/Close'
import { Alert, Button, IconButton, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsLeadership } from '@/app/store/authSlice'
import { canManageBilling } from '@/features/billing/billingAccess'
import { ROUTES } from '@/shared/constants/routes'
import { useCooperativeSubscription } from './useCooperativeSubscription'
import { dismissTrialNoticeForToday, isTrialNoticeDismissedToday } from './trialNoticeDismissal'
import {
  TRIAL_WARNING_DAYS,
  accessUntilIso,
  formatSubscriptionDate,
} from './subscriptionAccess'

export function SubscriptionBanner() {
  const { t } = useTranslation()
  const isLeadership = useAppSelector(selectIsLeadership)
  const roles = useAppSelector((s) => s.auth.user?.roles)
  const [dismissedKey, setDismissedKey] = useState<string | null>(null)
  const { cooperativeId, subscription, effectiveStatus, readOnly, isSuperAdmin, daysRemaining, isSuccess } =
    useCooperativeSubscription()

  if (!cooperativeId || !isSuccess) return null

  const until = formatSubscriptionDate(accessUntilIso(subscription))
  const trialEndingSoon =
    effectiveStatus === 'TRIAL' && daysRemaining != null && daysRemaining <= TRIAL_WARNING_DAYS
  const leadership = isLeadership || isSuperAdmin
  const membersRestricted = subscription?.writeAllowed === false

  if (effectiveStatus === 'ACTIVE' && !isSuperAdmin) {
    return null
  }

  if (effectiveStatus === 'TRIAL') {
    // Quiet until the trial is about to end; then one dismissible notice per day (see trialNoticeDismissal).
    if (!trialEndingSoon || !subscription) return null
    const noticeKey = `${cooperativeId}|${subscription.trialEndsAt ?? ''}`
    if (dismissedKey === noticeKey || isTrialNoticeDismissedToday(cooperativeId, subscription.trialEndsAt)) {
      return null
    }
    const canRenew = canManageBilling(roles)
    return (
      <Alert
        severity="warning"
        sx={{ mx: { xs: 2, sm: 3 }, mt: 2 }}
        data-testid="subscription-banner"
        action={
          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
            {canRenew ? (
              <Button
                component={RouterLink}
                to={ROUTES.billing}
                color="inherit"
                size="small"
                data-testid="trial-notice-billing-cta"
              >
                {t('subscription.trial.viewPlans')}
              </Button>
            ) : null}
            <IconButton
              aria-label={t('subscription.trial.dismiss')}
              color="inherit"
              size="small"
              onClick={() => {
                dismissTrialNoticeForToday(cooperativeId, subscription.trialEndsAt)
                setDismissedKey(noticeKey)
              }}
            >
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
        }
      >
        <Typography variant="subtitle2">{t('subscription.trial.endingTitle')}</Typography>
        <Typography variant="body2">
          {t('subscription.trial.endingBody', {
            count: daysRemaining ?? 0,
            date: until || t('subscription.unknownDate'),
          })}
        </Typography>
        {!canRenew ? (
          <Typography variant="body2">{t('subscription.dashboard.contactLeadership')}</Typography>
        ) : null}
      </Alert>
    )
  }

  if (effectiveStatus === 'PAST_DUE') {
    return (
      <Alert severity="warning" sx={{ mx: { xs: 2, sm: 3 }, mt: 2 }} data-testid="subscription-banner">
        <Typography variant="subtitle2">{t('subscription.pastDue.title')}</Typography>
        <Typography variant="body2">
          {leadership
            ? t('subscription.pastDue.leadership', { date: until || t('subscription.unknownDate') })
            : t('subscription.pastDue.member')}
        </Typography>
      </Alert>
    )
  }

  if (effectiveStatus === 'CANCELED' && !readOnly) {
    return (
      <Alert severity="info" sx={{ mx: { xs: 2, sm: 3 }, mt: 2 }} data-testid="subscription-banner">
        <Typography variant="subtitle2">{t('subscription.canceled.title')}</Typography>
        <Typography variant="body2">
          {t('subscription.canceled.body', { date: until || t('subscription.unknownDate') })}
        </Typography>
      </Alert>
    )
  }

  if (effectiveStatus === 'ACTIVE' && isSuperAdmin) {
    return null
  }

  if (readOnly || effectiveStatus === 'EXPIRED' || effectiveStatus === 'NONE' || effectiveStatus === 'CANCELED') {
    const none = effectiveStatus === 'NONE'
    return (
      <Alert severity="warning" sx={{ mx: { xs: 2, sm: 3 }, mt: 2 }} data-testid="subscription-banner">
        <Typography variant="subtitle2">
          {none ? t('subscription.none.title') : t('subscription.expired.title')}
        </Typography>
        <Typography variant="body2">
          {isSuperAdmin && membersRestricted
            ? t('subscription.superAdminNote')
            : leadership
              ? t('subscription.expired.leadership')
              : t('subscription.expired.member')}
        </Typography>
      </Alert>
    )
  }

  return null
}
