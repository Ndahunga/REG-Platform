import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { clearAuth } from '@/app/store/authSlice'
import { useAppDispatch } from '@/app/store/hooks'
import { logout as logoutRequest } from '@/shared/api/auth'
import { ROUTES } from '@/shared/constants/routes'

/** Sign-out shared by every account menu: revoke the session, always clear local auth, return to login. */
export function useLogout() {
  const navigate = useNavigate()
  const dispatch = useAppDispatch()
  const [loggingOut, setLoggingOut] = useState(false)

  const logout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    try {
      await logoutRequest()
    } catch {
      // clear anyway
    } finally {
      dispatch(clearAuth())
      navigate(ROUTES.login, { replace: true })
      setLoggingOut(false)
    }
  }

  return { logout, loggingOut }
}
