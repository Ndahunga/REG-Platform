import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type { AuthStatus, AuthUser } from '@/shared/types/auth'
import {
  hasAnyRole,
  hasPermission,
  isLeadershipRole,
  isOfficerRole,
  PERMISSION_CONTRIBUTION_WRITE,
  PERMISSION_SOCIAL_WRITE,
  PERMISSION_FINE_WRITE,
  PERMISSION_FUND_AUTHORIZE,
  PERMISSION_LOAN_APPROVE,
  PERMISSION_LOAN_WRITE,
  PERMISSION_MEMBERSHIP_MANAGE,
  PERMISSION_PAYOUT_WRITE,
  ROLE_ACCOUNTANT,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_PRESIDENT,
  ROLE_SUPER_ADMIN,
  ROLE_VICE_PRESIDENT,
} from '@/shared/types/auth'

/**
 * Access tokens live in Redux memory only (not localStorage).
 * Refresh uses the httpOnly `csams_refresh_token` cookie via `/auth/refresh`.
 */
export interface AuthState {
  user: AuthUser | null
  accessToken: string | null
  selectedCooperativeId: string | null
  status: AuthStatus
}

const initialState: AuthState = {
  user: null,
  accessToken: null,
  selectedCooperativeId: null,
  status: 'loading',
}

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    setCredentials(
      state,
      action: PayloadAction<{ user: AuthUser; accessToken: string }>,
    ) {
      state.user = action.payload.user
      state.accessToken = action.payload.accessToken
      state.status = 'authenticated'
      const isSuperAdmin = action.payload.user.roles?.includes(ROLE_SUPER_ADMIN)
      if (
        !isSuperAdmin &&
        !state.selectedCooperativeId &&
        action.payload.user.cooperativeIds?.length
      ) {
        state.selectedCooperativeId = action.payload.user.cooperativeIds[0]
      }
    },
    setSelectedCooperativeId(state, action: PayloadAction<string | null>) {
      state.selectedCooperativeId = action.payload
    },
    setAuthStatus(state, action: PayloadAction<AuthStatus>) {
      state.status = action.payload
    },
    clearAuth(state) {
      state.user = null
      state.accessToken = null
      state.selectedCooperativeId = null
      state.status = 'anonymous'
    },
  },
})

export const { setCredentials, setSelectedCooperativeId, setAuthStatus, clearAuth } =
  authSlice.actions

export const selectAuthUser = (state: { auth: AuthState }) => state.auth.user
export const selectAccessToken = (state: { auth: AuthState }) => state.auth.accessToken
export const selectAuthStatus = (state: { auth: AuthState }) => state.auth.status
export const selectSelectedCooperativeId = (state: { auth: AuthState }) =>
  state.auth.selectedCooperativeId
export const selectUserRoles = (state: { auth: AuthState }) => state.auth.user?.roles ?? []
export const selectIsAuthenticated = (state: { auth: AuthState }) =>
  state.auth.status === 'authenticated'
export const selectIsSuperAdmin = (state: { auth: AuthState }) =>
  state.auth.user?.roles.includes(ROLE_SUPER_ADMIN) ?? false
/** Any cooperative officer or Super Admin — operational staff chrome. */
export const selectIsCooperativeAdmin = (state: { auth: AuthState }) =>
  isOfficerRole(state.auth.user?.roles) ||
  (state.auth.user?.roles.includes(ROLE_SUPER_ADMIN) ?? false)
export const selectIsLeadership = (state: { auth: AuthState }) =>
  isLeadershipRole(state.auth.user?.roles)
export const selectCanManageMembers = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_MEMBERSHIP_MANAGE)
export const selectCanApproveLoans = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_LOAN_APPROVE)
export const selectCanRecordLoans = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_LOAN_WRITE)
export const selectCanAuthorizeFunds = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_FUND_AUTHORIZE)
export const selectCanPreparePayouts = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_PAYOUT_WRITE)
export const selectCanRecordContributions = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_CONTRIBUTION_WRITE)
export const selectCanWriteSocial = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_SOCIAL_WRITE)
export const selectCanManageLoans = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_LOAN_APPROVE) ||
  hasPermission(state.auth.user, PERMISSION_LOAN_WRITE)
export const selectCanManageFines = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_FINE_WRITE)
export const selectCanManageFineSettings = (state: { auth: AuthState }) =>
  hasAnyRole(state.auth.user?.roles, [ROLE_PRESIDENT, ROLE_COOPERATIVE_ADMIN, ROLE_SUPER_ADMIN])
export const selectCanReviewSharePurchases = (state: { auth: AuthState }) =>
  hasPermission(state.auth.user, PERMISSION_CONTRIBUTION_WRITE) &&
  hasAnyRole(state.auth.user?.roles, [
    ROLE_ACCOUNTANT,
    ROLE_PRESIDENT,
    ROLE_VICE_PRESIDENT,
    ROLE_COOPERATIVE_ADMIN,
    ROLE_SUPER_ADMIN,
  ])

export default authSlice.reducer
