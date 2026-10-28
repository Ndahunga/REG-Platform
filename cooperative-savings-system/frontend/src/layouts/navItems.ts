import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import AssessmentIcon from '@mui/icons-material/Assessment'
import FactCheckIcon from '@mui/icons-material/FactCheck'
import AssignmentTurnedInIcon from '@mui/icons-material/AssignmentTurnedIn'
import DashboardIcon from '@mui/icons-material/Dashboard'
import FavoriteIcon from '@mui/icons-material/Favorite'
import GavelIcon from '@mui/icons-material/Gavel'
import GroupsIcon from '@mui/icons-material/Groups'
import HistoryEduIcon from '@mui/icons-material/HistoryEdu'
import HistoryIcon from '@mui/icons-material/History'
import MenuBookIcon from '@mui/icons-material/MenuBook'
import NotificationsIcon from '@mui/icons-material/Notifications'
import PaymentsIcon from '@mui/icons-material/Payments'
import RequestQuoteIcon from '@mui/icons-material/RequestQuote'
import AutorenewIcon from '@mui/icons-material/Autorenew'
import PersonIcon from '@mui/icons-material/Person'
import PriceCheckIcon from '@mui/icons-material/PriceCheck'
import ReceiptIcon from '@mui/icons-material/Receipt'
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong'
import SavingsIcon from '@mui/icons-material/Savings'
import SettingsIcon from '@mui/icons-material/Settings'
import ShowChartIcon from '@mui/icons-material/ShowChart'
import type { SvgIconComponent } from '@mui/icons-material'
import { ROUTES } from '@/shared/constants/routes'
import {
  BILLING_MANAGER_ROLES,
  FINANCE_ACCESS_ROLES,
  LOAN_OPS_ROLES,
  ROLE_SUPER_ADMIN,
  SECRETARY_ACCESS_ROLES,
  STAFF_ROLES,
  isOfficerRole,
} from '@/shared/types/auth'

export type SidebarGroupId =
  | 'overview'
  | 'operations'
  | 'finance'
  | 'governance'
  | 'platform'
  | 'account'

export interface NavItem {
  labelKey: string
  path: string
  icon: SvgIconComponent
  /** If set, user must have at least one of these roles to see the item. */
  roles?: string[]
  /** Legacy AdminNavMenu grouping (kept for compatibility). */
  group?: 'main' | 'advanced' | 'super'
  /** Authenticated sidebar section. */
  sidebarGroup: SidebarGroupId
  /** Active only on the exact path, never on its sub-routes (portal home items). */
  end?: boolean
}

export const SIDEBAR_GROUP_ORDER: SidebarGroupId[] = [
  'overview',
  'operations',
  'finance',
  'governance',
  'platform',
  'account',
]

export const SIDEBAR_GROUP_LABEL_KEYS: Record<SidebarGroupId, string> = {
  overview: 'nav.group.overview',
  operations: 'nav.group.operations',
  finance: 'nav.group.finance',
  governance: 'nav.group.governance',
  platform: 'nav.group.platform',
  account: 'nav.group.account',
}

const LEADERSHIP_NAV_ROLES = [
  'PRESIDENT',
  'VICE_PRESIDENT',
  'COOPERATIVE_ADMIN',
  ROLE_SUPER_ADMIN,
]

/** Top-level Dashboard link — all authenticated users. */
export const dashboardNavItem: NavItem = {
  labelKey: 'nav.dashboard',
  path: ROUTES.dashboard,
  icon: DashboardIcon,
  sidebarGroup: 'overview',
}

const profileNavItem: NavItem = {
  labelKey: 'nav.profile',
  path: ROUTES.profile,
  icon: PersonIcon,
  sidebarGroup: 'account',
}

/**
 * MEMBER-facing primary navigation (mobile drawer + sidebar).
 * Labels use member-oriented wording where possible.
 */
export const memberNavItems: NavItem[] = [
  dashboardNavItem,
  {
    labelKey: 'nav.myContributions',
    path: ROUTES.contributions,
    icon: SavingsIcon,
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.myLoans',
    path: ROUTES.loans,
    icon: AccountBalanceWalletIcon,
    sidebarGroup: 'operations',
  },
  { labelKey: 'nav.myFines', path: ROUTES.fines, icon: GavelIcon, sidebarGroup: 'operations' },
  {
    labelKey: 'nav.mySocial',
    path: ROUTES.socialFund,
    icon: FavoriteIcon,
    sidebarGroup: 'operations',
  },
  { labelKey: 'nav.rotation', path: ROUTES.rotation, icon: AutorenewIcon, sidebarGroup: 'operations' },
  { labelKey: 'nav.payments', path: ROUTES.payments, icon: RequestQuoteIcon, sidebarGroup: 'finance' },
  { labelKey: 'nav.shareOut', path: ROUTES.payouts, icon: PaymentsIcon, sidebarGroup: 'finance' },
  { labelKey: 'nav.reports', path: ROUTES.reports, icon: AssessmentIcon, sidebarGroup: 'finance' },
  {
    labelKey: 'nav.notifications',
    path: ROUTES.notifications,
    icon: NotificationsIcon,
    sidebarGroup: 'account',
  },
  profileNavItem,
]

/** Officer modules shown in the authenticated sidebar / mobile drawer. */
export const adminModuleNavItems: NavItem[] = [
  {
    labelKey: 'nav.members',
    path: ROUTES.members,
    icon: GroupsIcon,
    roles: SECRETARY_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.contributions',
    path: ROUTES.contributions,
    icon: SavingsIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.contributionApprovals',
    path: `${ROUTES.contributions}?tab=approvals`,
    icon: AssignmentTurnedInIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    // Rotation was only in the member menu, so officers (who get adminModuleNavItems) could not reach it.
    labelKey: 'nav.rotation',
    path: ROUTES.rotation,
    icon: AutorenewIcon,
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.loans',
    path: ROUTES.loans,
    icon: AccountBalanceWalletIcon,
    roles: LOAN_OPS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    // Replaces the old "Loan Approvals" shortcut. Loan approvals are still reachable from the Loans page
    // (Approvals tab). Same roles as the Regular Contribution Approvals item: the share-purchase reviewers.
    labelKey: 'nav.sharePurchaseApprovals',
    path: `${ROUTES.contributions}?tab=share-approvals`,
    icon: PriceCheckIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.fines',
    path: ROUTES.fines,
    icon: GavelIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.finePaymentQueue',
    path: ROUTES.finePayments,
    icon: GavelIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.socialDashboard',
    path: ROUTES.socialFund,
    icon: FavoriteIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    labelKey: 'nav.socialApprovals',
    path: `${ROUTES.socialFund}?tab=approvals`,
    icon: AssignmentTurnedInIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'operations',
  },
  {
    // Combined payments: officers review, verify and post; payers follow their own payments on the same page.
    labelKey: 'nav.payments',
    path: ROUTES.payments,
    icon: RequestQuoteIcon,
    roles: [...new Set([...FINANCE_ACCESS_ROLES, ...LOAN_OPS_ROLES])],
    group: 'main',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.investments',
    path: ROUTES.investments,
    icon: ShowChartIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.shareOut',
    path: ROUTES.payouts,
    icon: PaymentsIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.transactions',
    path: ROUTES.transactions,
    icon: ReceiptLongIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'main',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.reports',
    path: ROUTES.reports,
    icon: AssessmentIcon,
    roles: STAFF_ROLES,
    group: 'main',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.reconciliation',
    path: ROUTES.reconciliation,
    icon: FactCheckIcon,
    roles: STAFF_ROLES,
    group: 'main',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.ledger',
    path: ROUTES.ledger,
    icon: MenuBookIcon,
    roles: FINANCE_ACCESS_ROLES,
    group: 'advanced',
    sidebarGroup: 'finance',
  },
  {
    labelKey: 'nav.notifications',
    path: ROUTES.notifications,
    icon: NotificationsIcon,
    roles: STAFF_ROLES,
    group: 'main',
    sidebarGroup: 'account',
  },
  {
    labelKey: 'nav.historicalImport',
    path: ROUTES.historicalImport,
    icon: HistoryEduIcon,
    roles: LEADERSHIP_NAV_ROLES,
    group: 'main',
    sidebarGroup: 'governance',
  },
  {
    labelKey: 'nav.billing',
    path: ROUTES.billing,
    icon: ReceiptIcon,
    roles: BILLING_MANAGER_ROLES,
    group: 'main',
    sidebarGroup: 'governance',
  },
  {
    labelKey: 'nav.settings',
    path: ROUTES.settings,
    icon: SettingsIcon,
    roles: LEADERSHIP_NAV_ROLES,
    group: 'main',
    sidebarGroup: 'governance',
  },
  {
    labelKey: 'nav.auditLogs',
    path: ROUTES.auditLogs,
    icon: HistoryIcon,
    roles: SECRETARY_ACCESS_ROLES,
    group: 'advanced',
    sidebarGroup: 'governance',
  },
]

/**
 * Backward-compatible combined lists used by older tests / imports.
 * Prefer memberNavItems / adminModuleNavItems for new UI.
 */
export const mainNavItems: NavItem[] = [
  dashboardNavItem,
  ...adminModuleNavItems.filter((i) => i.group === 'main' || !i.group),
  profileNavItem,
]

export const adminNavItems: NavItem[] = adminModuleNavItems.filter(
  (i) => i.group === 'advanced' || i.group === 'super',
)

export function canAccessNavItem(item: NavItem, userRoles: string[]): boolean {
  if (!item.roles?.length) return true
  return item.roles.some((role) => userRoles.includes(role))
}

export function isCooperativeAdminUser(userRoles: string[]): boolean {
  return isOfficerRole(userRoles)
}

export function isSuperAdminUser(userRoles: string[]): boolean {
  return userRoles.includes(ROLE_SUPER_ADMIN)
}

export function isAdminUser(userRoles: string[]): boolean {
  return isCooperativeAdminUser(userRoles) || isSuperAdminUser(userRoles)
}

/** Flat role-filtered list used by mobile drawer and as the sidebar source. */
export function getMobileNavItems(userRoles: string[]): NavItem[] {
  if (isAdminUser(userRoles)) {
    const items: NavItem[] = [
      dashboardNavItem,
      ...adminModuleNavItems.filter((item) => canAccessNavItem(item, userRoles)),
      profileNavItem,
    ]
    const seen = new Set<string>()
    return items.filter((item) => {
      if (seen.has(item.path)) return false
      seen.add(item.path)
      return true
    })
  }
  return memberNavItems.filter((item) => canAccessNavItem(item, userRoles))
}

export interface SidebarNavGroup {
  id: SidebarGroupId
  labelKey: string
  items: NavItem[]
}

/** Role-filtered items grouped for the authenticated sidebar. */
export function getSidebarNavGroups(userRoles: string[]): SidebarNavGroup[] {
  const items = getMobileNavItems(userRoles)
  return SIDEBAR_GROUP_ORDER.map((id) => ({
    id,
    labelKey: SIDEBAR_GROUP_LABEL_KEYS[id],
    items: items.filter((item) => item.sidebarGroup === id),
  })).filter((group) => group.items.length > 0)
}

export function navItemPathBase(path: string): string {
  return path.split('?')[0]
}

/**
 * Active-state helper for sidebar/drawer items.
 * Query-bearing items (e.g. ?tab=approvals) win when the search matches;
 * otherwise the parent module stays active for detail routes.
 */
export function isNavItemActive(pathname: string, search: string, itemPath: string): boolean {
  const [base, query] = itemPath.split('?')
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)

  if (query) {
    const expected = new URLSearchParams(query)
    for (const [key, value] of expected.entries()) {
      if (params.get(key) !== value) return false
    }
    return pathname === base
  }

  if (pathname.startsWith(`${base}/`)) {
    return true
  }

  if (pathname === base) {
    // Leave the base module inactive when a tabbed sibling owns the URL.
    return !params.has('tab')
  }

  return false
}

/** The four expandable sections of the Saving Scheme sidebar (Dashboard stays a plain top-level link). */
export type AppSectionId = 'operations' | 'finance' | 'administration' | 'profile'

export interface AppSidebarSection {
  id: AppSectionId
  labelKey: string
  icon: SvgIconComponent
  items: NavItem[]
}

export const APP_SECTION_META: Record<AppSectionId, { labelKey: string; icon: SvgIconComponent }> = {
  operations: { labelKey: 'nav.group.operations', icon: GroupsIcon },
  finance: { labelKey: 'nav.group.finance', icon: AccountBalanceWalletIcon },
  administration: { labelKey: 'nav.group.governance', icon: SettingsIcon },
  profile: { labelKey: 'nav.profile', icon: PersonIcon },
}

export const APP_SECTION_ORDER: AppSectionId[] = ['operations', 'finance', 'administration', 'profile']

/**
 * Section overrides by item path (including the ?tab= variants). Everything else follows the item's
 * existing `sidebarGroup`: operations -> operations, finance -> finance, governance -> administration,
 * account -> profile. The items themselves (and the Super Admin support navigation) are unchanged.
 */
const APP_SECTION_BY_PATH: Record<string, AppSectionId> = {
  [ROUTES.loans]: 'finance',
  [ROUTES.socialFund]: 'finance',
  [`${ROUTES.socialFund}?tab=approvals`]: 'finance',
  [`${ROUTES.contributions}?tab=share-approvals`]: 'finance',
}

export function appSectionOf(item: NavItem): AppSectionId | null {
  const override = APP_SECTION_BY_PATH[item.path]
  if (override) return override
  switch (item.sidebarGroup) {
    case 'operations':
      return 'operations'
    case 'finance':
      return 'finance'
    case 'governance':
    case 'platform':
      return 'administration'
    case 'account':
      return 'profile'
    default:
      return null
  }
}

/** Role-filtered Dashboard link plus the four sections (empty sections are omitted). */
export function getAppSidebarSections(userRoles: string[]): {
  dashboard: NavItem
  sections: AppSidebarSection[]
} {
  const items = getMobileNavItems(userRoles)
  const sections = APP_SECTION_ORDER.map((id) => ({
    id,
    ...APP_SECTION_META[id],
    items: items.filter((item) => appSectionOf(item) === id),
  })).filter((section) => section.items.length > 0)
  return { dashboard: dashboardNavItem, sections }
}
