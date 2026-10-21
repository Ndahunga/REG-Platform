import AccountBalanceIcon from '@mui/icons-material/AccountBalance'
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings'
import DashboardIcon from '@mui/icons-material/Dashboard'
import HealthAndSafetyIcon from '@mui/icons-material/HealthAndSafety'
import LockResetIcon from '@mui/icons-material/LockReset'
import PersonIcon from '@mui/icons-material/Person'
import { SUPER_ADMIN_ROUTES, supportPath } from '@/shared/constants/routes'
import { ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import {
  SIDEBAR_GROUP_LABEL_KEYS,
  adminModuleNavItems,
  canAccessNavItem,
  dashboardNavItem,
  type NavItem,
  type SidebarNavGroup,
} from './navItems'

/**
 * Navigation of the system-administration portal. Deliberately separate from the Saving Scheme
 * `navItems`: a platform administrator does not get operational modules (members, loans, ledger, ...).
 */
const overview: NavItem = {
  labelKey: 'superAdmin.nav.overview',
  path: SUPER_ADMIN_ROUTES.home,
  icon: DashboardIcon,
  sidebarGroup: 'platform',
  end: true,
}

const platform: NavItem[] = [
  {
    labelKey: 'superAdmin.nav.savingSchemes',
    path: SUPER_ADMIN_ROUTES.cooperatives,
    icon: AccountBalanceIcon,
    sidebarGroup: 'platform',
  },
  {
    labelKey: 'superAdmin.nav.superAdmins',
    path: SUPER_ADMIN_ROUTES.admins,
    icon: AdminPanelSettingsIcon,
    sidebarGroup: 'platform',
  },
  {
    labelKey: 'superAdmin.nav.system',
    path: SUPER_ADMIN_ROUTES.system,
    icon: HealthAndSafetyIcon,
    sidebarGroup: 'platform',
  },
]

const account: NavItem[] = [
  {
    labelKey: 'nav.profile',
    path: SUPER_ADMIN_ROUTES.profile,
    icon: PersonIcon,
    sidebarGroup: 'account',
  },
  {
    labelKey: 'superAdmin.nav.changePassword',
    path: SUPER_ADMIN_ROUTES.changePassword,
    icon: LockResetIcon,
    sidebarGroup: 'account',
  },
]

export const superAdminNavItems: NavItem[] = [overview, ...platform, ...account]

export function getSuperAdminSidebarGroups(): SidebarNavGroup[] {
  return [
    { id: 'platform', labelKey: 'superAdmin.nav.groupAdministration', items: [overview, ...platform] },
    { id: 'account', labelKey: 'nav.group.account', items: account },
  ]
}

/**
 * Navigation of the support workspace: the Saving Scheme's own operational modules (the same definitions
 * the scheme's leadership sees), re-rooted under `/superadmin/cooperatives/:id/`, plus the SUPER_ADMIN's
 * own account pages.
 */
export function getSupportSidebarGroups(cooperativeId: string): SidebarNavGroup[] {
  const reroot = (item: NavItem): NavItem => ({ ...item, path: supportPath(cooperativeId, item.path) })
  const modules = adminModuleNavItems
    .filter((item) => canAccessNavItem(item, [ROLE_SUPER_ADMIN]))
    .map(reroot)
  const inGroup = (id: NavItem['sidebarGroup']) => modules.filter((item) => item.sidebarGroup === id)

  const groups: SidebarNavGroup[] = [
    { id: 'overview', labelKey: SIDEBAR_GROUP_LABEL_KEYS.overview, items: [reroot(dashboardNavItem)] },
    { id: 'operations', labelKey: SIDEBAR_GROUP_LABEL_KEYS.operations, items: inGroup('operations') },
    { id: 'finance', labelKey: SIDEBAR_GROUP_LABEL_KEYS.finance, items: inGroup('finance') },
    { id: 'governance', labelKey: SIDEBAR_GROUP_LABEL_KEYS.governance, items: inGroup('governance') },
    { id: 'account', labelKey: SIDEBAR_GROUP_LABEL_KEYS.account, items: [...inGroup('account'), ...account] },
  ]
  return groups.filter((group) => group.items.length > 0)
}
