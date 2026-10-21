import { Outlet } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsSuperAdmin } from '@/app/store/authSlice'
import { NotFoundPage } from '@/pages/NotFoundPage'

/**
 * Gate for the system-administration portal. Anonymous visitors are handled by `ProtectedRoute` (login);
 * an authenticated user without the global SUPER_ADMIN role gets the not-found page, so the portal's
 * existence is not advertised to Saving Scheme users. The backend enforces the same rule independently.
 */
export function SuperAdminRoute() {
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  if (!isSuperAdmin) {
    return <NotFoundPage />
  }
  return <Outlet />
}
