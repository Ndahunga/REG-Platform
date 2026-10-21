import { useLocation } from 'react-router-dom'
import { ROUTES, SUPER_ADMIN_ROUTES, SUPPORT_SECTIONS, isSuperAdminPath, supportPath } from './routes'
import { ROLE_SUPER_ADMIN } from '@/shared/types/auth'

/** Where a signed-in user lands: the system portal for SUPER_ADMIN, the Saving Scheme dashboard otherwise. */
export function homeRouteForRoles(roles: readonly string[] | undefined): string {
  return roles?.includes(ROLE_SUPER_ADMIN) ? SUPER_ADMIN_ROUTES.home : ROUTES.dashboard
}

/**
 * Maps a Saving Scheme client path to its system-portal equivalent, for a SUPER_ADMIN who lands on a client
 * route. Anything without a dedicated equivalent goes to the portal home.
 */
export function superAdminEquivalentOf(pathname: string, selectedCooperativeId?: string | null): string {
  const section = pathname.split('/')[1]
  if (selectedCooperativeId && (SUPPORT_SECTIONS as readonly string[]).includes(section)) {
    return supportPath(selectedCooperativeId, pathname)
  }
  if (pathname === ROUTES.profile) return SUPER_ADMIN_ROUTES.profile
  if (pathname === ROUTES.changePassword) return SUPER_ADMIN_ROUTES.changePassword
  if (pathname === ROUTES.system) return SUPER_ADMIN_ROUTES.system
  if (pathname === ROUTES.cooperatives || pathname === `${ROUTES.cooperatives}/`) {
    return SUPER_ADMIN_ROUTES.cooperatives
  }
  if (pathname.startsWith(`${ROUTES.cooperatives}/`)) {
    return `${SUPER_ADMIN_ROUTES.cooperatives}${pathname.slice(ROUTES.cooperatives.length)}`
  }
  return SUPER_ADMIN_ROUTES.home
}

/** Route targets for pages that are shared between the client app and the system portal. */
export function usePortalRoutes() {
  const { pathname } = useLocation()
  const system = isSuperAdminPath(pathname)
  return {
    inPortal: system,
    home: system ? SUPER_ADMIN_ROUTES.home : ROUTES.dashboard,
    profile: system ? SUPER_ADMIN_ROUTES.profile : ROUTES.profile,
    changePassword: system ? SUPER_ADMIN_ROUTES.changePassword : ROUTES.changePassword,
    cooperatives: system ? SUPER_ADMIN_ROUTES.cooperatives : ROUTES.cooperatives,
    workspace: (id: string) => SUPER_ADMIN_ROUTES.workspaceDashboard(id),
    cooperativeDetail: (id: string) =>
      system ? SUPER_ADMIN_ROUTES.cooperativeDetail(id) : ROUTES.cooperativeDetail(id),
  }
}
