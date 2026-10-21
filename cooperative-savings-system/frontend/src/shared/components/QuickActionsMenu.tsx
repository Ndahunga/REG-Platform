import AddIcon from '@mui/icons-material/Add'
import FavoriteIcon from '@mui/icons-material/Favorite'
import GavelIcon from '@mui/icons-material/Gavel'
import GroupsIcon from '@mui/icons-material/Groups'
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import PaymentsIcon from '@mui/icons-material/Payments'
import SavingsIcon from '@mui/icons-material/Savings'
import ShowChartIcon from '@mui/icons-material/ShowChart'
import VolunteerActivismIcon from '@mui/icons-material/VolunteerActivism'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import {
  Box,
  Button,
  Chip,
  Divider,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Typography,
  Tooltip,
} from '@mui/material'
import type { SvgIconComponent } from '@mui/icons-material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectAuthUser } from '@/app/store/authSlice'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { ROUTES } from '@/shared/constants/routes'
import {
  hasAnyRole,
  hasPermission,
  PERMISSION_MEMBERSHIP_MANAGE,
  SECRETARY_ACCESS_ROLES,
  type AuthUser,
} from '@/shared/types/auth'

export interface QuickActionItem {
  id: string
  labelKey: string
  badgeKey: string
  badgeColor:
    | 'default'
    | 'primary'
    | 'secondary'
    | 'error'
    | 'info'
    | 'success'
    | 'warning'
  icon: SvgIconComponent
  path: string
  group: 'quick' | 'group'
  /** Required permission to show this action. Super Admin always qualifies. */
  permission?: string
  /** Required roles to show this action (e.g. members list). */
  roles?: readonly string[]
  /** When true, keep the item available in subscription read-only mode. */
  readNavigation?: boolean
}

export const QUICK_ACTIONS: QuickActionItem[] = [
  {
    id: 'add-member',
    labelKey: 'actions.addMember',
    badgeKey: 'actions.badges.add',
    badgeColor: 'success',
    icon: AddIcon,
    path: `${ROUTES.members}?action=register`,
    group: 'quick',
    permission: PERMISSION_MEMBERSHIP_MANAGE,
  },
  {
    id: 'update-contributions',
    labelKey: 'actions.updateContributions',
    badgeKey: 'actions.badges.edit',
    badgeColor: 'info',
    icon: SavingsIcon,
    path: `${ROUTES.contributions}?tab=monthly`,
    group: 'quick',
  },
  {
    id: 'special-contributions',
    labelKey: 'actions.specialContributions',
    badgeKey: 'actions.badges.special',
    badgeColor: 'secondary',
    icon: VolunteerActivismIcon,
    path: `${ROUTES.contributions}?tab=special`,
    group: 'quick',
  },
  {
    id: 'manage-loans',
    labelKey: 'actions.manageLoans',
    badgeKey: 'actions.badges.loan',
    badgeColor: 'warning',
    icon: AccountBalanceWalletIcon,
    path: ROUTES.loans,
    group: 'quick',
    readNavigation: true,
  },
  {
    id: 'manage-fines',
    labelKey: 'actions.manageFines',
    badgeKey: 'actions.badges.fine',
    badgeColor: 'error',
    icon: GavelIcon,
    path: ROUTES.fines,
    group: 'quick',
    readNavigation: true,
  },
  {
    id: 'fine-payment-queue',
    labelKey: 'actions.finePaymentQueue',
    badgeKey: 'actions.badges.clear',
    badgeColor: 'error',
    icon: GavelIcon,
    path: ROUTES.finePayments,
    group: 'quick',
  },
  {
    id: 'member-view',
    labelKey: 'actions.memberView',
    badgeKey: 'actions.badges.view',
    badgeColor: 'primary',
    icon: GroupsIcon,
    path: ROUTES.members,
    group: 'quick',
    roles: SECRETARY_ACCESS_ROLES,
    readNavigation: true,
  },
  {
    id: 'share-out',
    labelKey: 'actions.shareOutPayout',
    badgeKey: 'actions.badges.payout',
    badgeColor: 'success',
    icon: PaymentsIcon,
    path: `${ROUTES.payouts}?tab=new`,
    group: 'group',
  },
  {
    id: 'create-investment',
    labelKey: 'actions.createInvestment',
    badgeKey: 'actions.badges.invest',
    badgeColor: 'success',
    icon: ShowChartIcon,
    path: `${ROUTES.investments}?action=create`,
    group: 'group',
  },
  {
    id: 'social-dashboard',
    labelKey: 'actions.socialDashboard',
    badgeKey: 'actions.badges.social',
    badgeColor: 'secondary',
    icon: FavoriteIcon,
    path: ROUTES.socialFund,
    group: 'group',
    readNavigation: true,
  },
]

export function canShowQuickAction(
  item: QuickActionItem,
  user: Pick<AuthUser, 'roles' | 'permissions'> | null | undefined,
): boolean {
  if (item.permission && !hasPermission(user, item.permission)) return false
  if (item.roles?.length && !hasAnyRole(user?.roles, item.roles)) return false
  return true
}

export function quickActionsForUser(
  user: Pick<AuthUser, 'roles' | 'permissions'> | null | undefined,
  group: 'quick' | 'group',
): QuickActionItem[] {
  return QUICK_ACTIONS.filter((item) => item.group === group && canShowQuickAction(item, user))
}

interface QuickActionsMenuProps {
  buttonVariant?: 'contained' | 'outlined' | 'text'
  size?: 'small' | 'medium'
}

export function QuickActionsMenu({
  buttonVariant = 'outlined',
  size = 'medium',
}: QuickActionsMenuProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAppSelector(selectAuthUser)
  const { canWrite, readOnly } = useCooperativeSubscription()
  const [anchor, setAnchor] = useState<null | HTMLElement>(null)
  const writeBlocked = readOnly || !canWrite

  const quick = quickActionsForUser(user, 'quick')
  const group = quickActionsForUser(user, 'group')

  const go = (path: string, blocked: boolean) => {
    if (blocked) return
    setAnchor(null)
    navigate(path)
  }

  const renderItem = (item: QuickActionItem) => {
    const Icon = item.icon
    const blocked = writeBlocked && !item.readNavigation
    return (
      <Tooltip key={item.id} title={blocked ? t('subscription.readOnlyAction') : ''}>
        <span>
          <MenuItem
            onClick={() => go(item.path, blocked)}
            disabled={blocked}
            sx={{ py: 1.25, gap: 1 }}
          >
            <ListItemIcon>
              <Icon fontSize="small" />
            </ListItemIcon>
            <ListItemText primary={t(item.labelKey)} />
            <Chip
              size="small"
              label={t(item.badgeKey)}
              color={item.badgeColor}
              sx={{ height: 22, fontSize: '0.65rem', fontWeight: 700, letterSpacing: 0.4 }}
            />
          </MenuItem>
        </span>
      </Tooltip>
    )
  }

  return (
    <>
      <Button
        variant={buttonVariant}
        size={size}
        endIcon={<ExpandMoreIcon />}
        onClick={(e) => setAnchor(e.currentTarget)}
        aria-haspopup="menu"
        aria-expanded={Boolean(anchor)}
      >
        {t('common.actions')}
      </Button>
      <Menu
        anchorEl={anchor}
        open={Boolean(anchor)}
        onClose={() => setAnchor(null)}
        slotProps={{ paper: { sx: { minWidth: 320, maxHeight: 520 } } }}
      >
        {quick.length > 0 ? (
          <>
            <Box sx={{ px: 2, pt: 1.5, pb: 0.5 }}>
              <Typography variant="overline" color="text.secondary">
                {t('actions.quickActions')}
              </Typography>
            </Box>
            {quick.map(renderItem)}
          </>
        ) : null}
        {quick.length > 0 && group.length > 0 ? <Divider sx={{ my: 1 }} /> : null}
        {group.length > 0 ? (
          <>
            <Box sx={{ px: 2, pt: 0.5, pb: 0.5 }}>
              <Typography variant="overline" color="text.secondary">
                {t('actions.groupActions')}
              </Typography>
            </Box>
            {group.map(renderItem)}
          </>
        ) : null}
      </Menu>
    </>
  )
}
