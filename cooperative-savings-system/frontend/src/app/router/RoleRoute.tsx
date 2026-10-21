import { Navigate, Outlet } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { ROUTES } from '@/shared/constants/routes'

/** Requires the signed-in user to have at least one of the given roles. */
export function RoleRoute({ roles }: { roles: string[] }) {
  const userRoles = useAppSelector((s) => s.auth.user?.roles ?? [])
  const allowed = roles.some((role) => userRoles.includes(role))

  if (!allowed) {
    return <Navigate to={ROUTES.dashboard} replace />
  }

  return <Outlet />
}
