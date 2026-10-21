import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsSuperAdmin } from '@/app/store/authSlice'
import { superAdminEquivalentOf } from '@/shared/constants/portal'
import { parseSupportPath } from '@/shared/constants/routes'

/**
 * Guards the Saving Scheme client application. A system SUPER_ADMIN is not a Saving Scheme user, so any
 * client route sends them to the system portal: to the same page inside the support workspace when a
 * Saving Scheme is open (this is what keeps the existing pages' internal links working), to the matching
 * portal page where one exists, and otherwise to the portal home.
 */
export function ClientPortalRoute() {
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const selectedCooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const { pathname, search, hash } = useLocation()
  if (isSuperAdmin) {
    const target = superAdminEquivalentOf(pathname, selectedCooperativeId)
    const keepsPage = parseSupportPath(target) !== null
    return <Navigate to={keepsPage ? `${target}${search}${hash}` : target} replace />
  }
  return <Outlet />
}
