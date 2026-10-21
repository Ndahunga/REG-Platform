import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import FavoriteIcon from '@mui/icons-material/Favorite'
import GavelIcon from '@mui/icons-material/Gavel'
import PaymentsIcon from '@mui/icons-material/Payments'
import SavingsIcon from '@mui/icons-material/Savings'
import { Paper, Stack, Typography } from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { BuySharesDialog } from '@/features/shares'
import { ROUTES } from '@/shared/constants/routes'
import { dashboardSectionTitleSx } from './dashboardTypography'

const QUICK_LINKS = [
  {
    labelKey: 'dashboard.member.links.submitContribution',
    path: `${ROUTES.contributions}?tab=submit`,
    icon: SavingsIcon,
  },
  {
    labelKey: 'dashboard.member.links.applyLoan',
    path: `${ROUTES.loans}?tab=request`,
    icon: AccountBalanceWalletIcon,
  },
  {
    labelKey: 'dashboard.member.links.submitSocial',
    path: `${ROUTES.socialFund}?tab=submit`,
    icon: FavoriteIcon,
  },
  {
    labelKey: 'dashboard.member.links.contributions',
    path: ROUTES.contributions,
    icon: SavingsIcon,
  },
  {
    labelKey: 'dashboard.member.links.loans',
    path: ROUTES.loans,
    icon: AccountBalanceWalletIcon,
  },
  {
    labelKey: 'dashboard.member.links.fines',
    path: ROUTES.fines,
    icon: GavelIcon,
  },
  {
    labelKey: 'dashboard.member.links.social',
    path: ROUTES.socialFund,
    icon: FavoriteIcon,
  },
  {
    labelKey: 'dashboard.member.links.payouts',
    path: ROUTES.payouts,
    icon: PaymentsIcon,
  },
] as const

const QUICK_ACTION_SX = {
  px: 2,
  py: 1.25,
  display: 'flex',
  alignItems: 'center',
  gap: 1,
  color: 'text.primary',
  border: '1px solid',
  borderColor: 'divider',
  borderRadius: 2,
  minHeight: 44,
  bgcolor: 'background.paper',
  cursor: 'pointer',
  font: 'inherit',
  appearance: 'none',
  textAlign: 'left',
  '&:hover': { borderColor: 'primary.main', color: 'primary.main' },
} as const

interface MemberQuickActionsSectionProps {
  cooperativeId: string
}

/**
 * The member's own quick actions (Buy Shares, submit contribution, apply for a loan, personal links).
 * Rendered once, directly above "My member status", on both the member and the officer dashboards.
 */
export function MemberQuickActionsSection({ cooperativeId }: MemberQuickActionsSectionProps) {
  const { t } = useTranslation()
  const [buySharesOpen, setBuySharesOpen] = useState(false)

  return (
    <>
      <Paper
        elevation={0}
        data-testid="member-quick-actions"
        sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}
      >
        <Typography variant="h6" component="h2" gutterBottom sx={dashboardSectionTitleSx}>
          {t('dashboard.member.actionsTitle')}
        </Typography>
        <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap' }} useFlexGap>
          <Paper
            component="button"
            type="button"
            elevation={0}
            onClick={() => setBuySharesOpen(true)}
            sx={QUICK_ACTION_SX}
          >
            <PaymentsIcon fontSize="small" />
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {t('shares.buy.action')}
            </Typography>
            <ChevronRightIcon fontSize="small" sx={{ ml: 0.25, opacity: 0.7 }} />
          </Paper>
          {QUICK_LINKS.map((link) => {
            const Icon = link.icon
            return (
              <Paper
                key={link.path}
                component={RouterLink}
                to={link.path}
                elevation={0}
                sx={{ ...QUICK_ACTION_SX, textDecoration: 'none' }}
              >
                <Icon fontSize="small" />
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {t(link.labelKey)}
                </Typography>
                <ChevronRightIcon fontSize="small" sx={{ ml: 0.25, opacity: 0.7 }} />
              </Paper>
            )
          })}
        </Stack>
      </Paper>

      <BuySharesDialog
        open={buySharesOpen}
        cooperativeId={cooperativeId}
        onClose={() => setBuySharesOpen(false)}
      />
    </>
  )
}
