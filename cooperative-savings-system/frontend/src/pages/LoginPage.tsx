import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Link as MuiLink,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, Navigate, useNavigate } from 'react-router-dom'
import { yupResolver } from '@hookform/resolvers/yup'
import * as yup from 'yup'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { setCredentials, setSelectedCooperativeId } from '@/app/store/authSlice'
import { login } from '@/shared/api/auth'
import { getErrorMessage } from '@/shared/api/client'
import { AuthSplitShell } from '@/layouts/AuthSplitShell'
import { LOGIN_SUCCESS_STATE } from '@/features/branding/loginSuccessSplash'
import { AUTH_ACTION_BUTTON_SX } from '@/layouts/authFormStyles'
import { plainLinkSx } from '@/shared/styles/plainLink'
import { ROUTES } from '@/shared/constants/routes'
import { ROLE_PRESIDENT, ROLE_SUPER_ADMIN } from '@/shared/types/auth'
import { homeRouteForRoles } from '@/shared/constants/portal'
import { isPreviewLoginEnabled } from '@/shared/auth/previewLogin'

interface LoginFormValues {
  username: string
  password: string
}

const schema = yup.object({
  username: yup.string().trim().required('Username is required'),
  password: yup.string().required('Password is required'),
})

export function LoginPage() {
  const { t } = useTranslation()
  const dispatch = useAppDispatch()
  const navigate = useNavigate()
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: yupResolver(schema),
    defaultValues: { username: '', password: '' },
  })

  // Set when this page itself completes a login, so the "already signed in" redirect never skips the splash.
  const loggedInHere = useRef(false)
  const authStatus = useAppSelector((s) => s.auth.status)
  const authRoles = useAppSelector((s) => s.auth.user?.roles)

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: (data) => {
      loggedInHere.current = true
      dispatch(setCredentials({ user: data.user, accessToken: data.accessToken }))
      navigate(ROUTES.loginSuccess, {
        replace: true,
        state: { ...LOGIN_SUCCESS_STATE, next: homeRouteForRoles(data.user.roles) },
      })
    },
    onError: (error) => {
      setErrorMessage(getErrorMessage(error, t('errors.loginFailed')))
    },
  })

  const onSubmit = handleSubmit((values) => {
    setErrorMessage(null)
    mutation.mutate(values)
  })

  const enterDevPreview = () => {
    if (!isPreviewLoginEnabled()) {
      return
    }
    dispatch(
      setCredentials({
        accessToken: 'dev-preview-memory-token',
        user: {
          id: 'dev-preview',
          username: 'dev.preview',
          email: 'demo@terimbere.local',
          firstName: 'Dev',
          lastName: 'Preview',
          fullName: 'Dev Preview User',
          roles: [ROLE_PRESIDENT, ROLE_SUPER_ADMIN],
          permissions: [],
          cooperativeIds: ['demo-coop-1'],
        },
      }),
    )
    dispatch(setSelectedCooperativeId('demo-coop-1'))
    navigate(ROUTES.dashboard, { replace: true })
  }

  // A restored session (refresh cookie) or an already signed-in user revisiting /login goes straight home.
  if (authStatus === 'authenticated' && !loggedInHere.current) {
    return <Navigate to={homeRouteForRoles(authRoles)} replace />
  }

  return (
    <AuthSplitShell supporting={t('login.shellText')}>
      <Typography variant="h5" component="h1" gutterBottom>
        {t('login.title')}
      </Typography>
      <Box component="form" onSubmit={onSubmit} noValidate>
        <Stack spacing={2.5} sx={{ mt: 2 }}>
          <TextField
            label={t('login.username')}
            type="text"
            autoComplete="username"
            fullWidth
            error={Boolean(errors.username)}
            helperText={errors.username?.message}
            {...register('username')}
          />
          <TextField
            label={t('login.password')}
            type="password"
            autoComplete="current-password"
            fullWidth
            error={Boolean(errors.password)}
            helperText={errors.password?.message}
            {...register('password')}
          />
          <Box sx={{ textAlign: 'right', mt: -1 }}>
            <MuiLink component={RouterLink} to={ROUTES.forgotPassword} variant="body2" underline="none" sx={plainLinkSx}>
              {t('login.forgotPassword')}
            </MuiLink>
          </Box>
          {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}
          <Button
            type="submit"
            variant="contained"
            disabled={mutation.isPending}
            data-testid="login-submit"
            sx={{ ...AUTH_ACTION_BUTTON_SX, alignSelf: 'flex-end' }}
            startIcon={
              mutation.isPending ? <CircularProgress size={18} color="inherit" /> : null
            }
          >
            {t('login.submit')}
          </Button>
          <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
            {t('login.noAccount')}{' '}
            <MuiLink component={RouterLink} to={ROUTES.signup} underline="none" sx={plainLinkSx} data-testid="login-register-link">
              {t('login.createAccount')}
            </MuiLink>
          </Typography>
          {isPreviewLoginEnabled() ? (
            <Stack spacing={1}>
              <Alert severity="warning">{t('login.devPreviewWarning')}</Alert>
              <Button variant="outlined" size="large" onClick={enterDevPreview}>
                {t('login.devPreview')}
              </Button>
            </Stack>
          ) : null}
        </Stack>
      </Box>
    </AuthSplitShell>
  )
}
