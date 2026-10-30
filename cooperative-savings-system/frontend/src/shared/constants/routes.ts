export const ROUTES = {
  home: '/',
  about: '/about',
  contact: '/contact',
  terms: '/terms',
  login: '/login',
  loginSuccess: '/login-success',
  signup: '/signup',
  forgotPassword: '/forgot-password',
  resetPassword: '/reset-password',
  changePassword: '/change-password',
  dashboard: '/dashboard',
  members: '/members',
  memberDetail: (userId: string) => `/members/${userId}`,
  contributions: '/contributions',
  specialCampaign: (campaignId: string) => `/contributions/special/${campaignId}`,
  loans: '/loans',
  loanDetail: (loanId: string) => `/loans/${loanId}`,
  fines: '/fines',
  fineDetail: (fineId: string) => `/fines/${fineId}`,
  finePayments: '/fine-payments',
  socialFund: '/social-fund',
  investments: '/investments',
  investmentDetail: (investmentId: string) => `/investments/${investmentId}`,
  transactions: '/transactions',
  ledger: '/ledger',
  payouts: '/payouts',
  rotation: '/rotation',
  payments: '/payments',
  payoutDetail: (runId: string) => `/payouts/${runId}`,
  reports: '/reports',
  reconciliation: '/reconciliation',
  expenses: '/expenses',
  historicalImport: '/historical-import',
  billing: '/billing',
  billingPaymentReturn: '/billing/payment-return',
  settings: '/settings',
  notifications: '/notifications',
  auditLogs: '/audit-logs',
  profile: '/profile',
  cooperatives: '/cooperatives',
  cooperativeDetail: (id: string) => `/cooperatives/${id}`,
  system: '/system',
} as const

export type AppRoute = Exclude<(typeof ROUTES)[keyof typeof ROUTES], (...args: never[]) => string>

/** The OuWealth system-administration portal. Only the global SUPER_ADMIN role may enter. */
export const SUPER_ADMIN_ROUTES = {
  home: '/superadmin',
  admins: '/superadmin/admins',
  cooperatives: '/superadmin/cooperatives',
  cooperativeDetail: (id: string) => `/superadmin/cooperatives/${id}`,
  /** Support workspace: a Saving Scheme opened by a SUPER_ADMIN, under the system portal. */
  workspace: (id: string) => `/superadmin/cooperatives/${id}`,
  workspaceDashboard: (id: string) => `/superadmin/cooperatives/${id}/dashboard`,
  system: '/superadmin/system',
  profile: '/superadmin/profile',
  changePassword: '/superadmin/change-password',
} as const

export const SUPER_ADMIN_BASE = SUPER_ADMIN_ROUTES.home

export function isSuperAdminPath(pathname: string): boolean {
  return pathname === SUPER_ADMIN_BASE || pathname.startsWith(`${SUPER_ADMIN_BASE}/`)
}

/** Saving Scheme client areas a SUPER_ADMIN may open in the support workspace (first path segment). */
export const SUPPORT_SECTIONS = [
  'dashboard',
  'members',
  'contributions',
  'loans',
  'fines',
  'fine-payments',
  'social-fund',
  'investments',
  'transactions',
  'ledger',
  'payouts',
  'rotation',
  'payments',
  'reports',
  'reconciliation',
  'expenses',
  'billing',
  'historical-import',
  'settings',
  'audit-logs',
  'notifications',
] as const

/** `/loans/abc` -> `/superadmin/cooperatives/<id>/loans/abc` (the same page, inside the support workspace). */
export function supportPath(cooperativeId: string, clientPath: string): string {
  return `${SUPER_ADMIN_ROUTES.workspace(cooperativeId)}${clientPath.startsWith('/') ? clientPath : `/${clientPath}`}`
}

const WORKSPACE_PATTERN = /^\/superadmin\/cooperatives\/([^/]+)\/([^/]+)/

/** The scheme and section of a support-workspace URL, or null for any other path. */
export function parseSupportPath(pathname: string): { cooperativeId: string; section: string } | null {
  const match = WORKSPACE_PATTERN.exec(pathname)
  return match ? { cooperativeId: match[1], section: match[2] } : null
}
