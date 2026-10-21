import { Box, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { DashboardSubscriptionCard } from '@/features/subscription/DashboardSubscriptionCard'
import { isActionNeeded } from '@/features/subscription/subscriptionAccess'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { CurrentSchemeName } from './CurrentSchemeName'
import { MemberQuickActionsSection } from './MemberQuickActionsSection'
import { MonthlyContributionsChart } from './MonthlyContributionsChart'
import { MyMemberStatusSection } from './MyMemberStatusSection'
import { dashboardHintSx, dashboardWelcomeTitleSx } from './dashboardTypography'

interface MemberDashboardProps {
  cooperativeId: string
}

export function MemberDashboard({ cooperativeId }: MemberDashboardProps) {
  const { t } = useTranslation()
  const user = useAppSelector((s) => s.auth.user)
  const { subscription, effectiveStatus, daysRemaining, isSuccess } =
    useCooperativeSubscription(cooperativeId)
  const showCard = isSuccess && isActionNeeded(effectiveStatus, daysRemaining)

  return (
    <Box>
      <Box sx={{ mb: 3 }}>
        <Typography variant="h4" component="h1" gutterBottom sx={dashboardWelcomeTitleSx}>
          {t('dashboard.member.welcome', { name: user?.firstName || user?.fullName || '' })}
        </Typography>
        <CurrentSchemeName cooperativeId={cooperativeId} />
        <Typography variant="body1" color="text.secondary" sx={dashboardHintSx}>
          {t('dashboard.member.description')}
        </Typography>
      </Box>

      {showCard ? <DashboardSubscriptionCard subscription={subscription} variant="member" /> : null}

      <Box sx={{ mb: 3 }}>
        <MemberQuickActionsSection cooperativeId={cooperativeId} />
      </Box>

      <Box sx={{ mb: 3 }}>
        <MyMemberStatusSection cooperativeId={cooperativeId} />
      </Box>

      <MonthlyContributionsChart cooperativeId={cooperativeId} currency="RWF" />
    </Box>
  )
}
