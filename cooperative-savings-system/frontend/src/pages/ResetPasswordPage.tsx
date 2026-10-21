import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Link as MuiLink,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom'
import { yupResolver } from '@hookform/resolvers/yup'
import * as yup from 'yup'
import { confirmPasswordReset } from '@/shared/api/auth'
import { getErrorMessage } from '@/shared/api/client'
import { AuthBrand } from '@/shared/components/AuthBrand'
import { ROUTES } from '@/shared/constants/routes'

interface ResetPasswordFormValues {
  newPassword: string
  confirmPassword: string
}

const schema = yup.object({
  newPassword: yup.string().min(8, 'At least 8 characters').required('Password is required'),
  confirmPassword: yup
    .string()
    .oneOf([yup.ref('newPassword')], 'Passwords must match')
    .required('Confirm your password'),
})

export function ResetPasswordPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const token = useMemo(() => searchParams.get('token')?.trim() ?? '', [searchParams])
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ResetPasswordFormValues>({
    resolver: yupResolver(schema),
    defaultValues: { newPassword: '', confirmPassword: '' },
  })

  const mutation = useMutation({
    mutationFn: confirmPasswordReset,
    onSuccess: () => {
      setErrorMessage(null)
      setSuccessMessage(t('resetPassword.success'))
      window.setTimeout(() => navigate(ROUTES.login, { replace: true }), 1500)
    },
    onError: (error) => {
      setSuccessMessage(null)
      setErrorMessage(getErrorMessage(error, t('errors.generic')))
    },
  })

  const onSubmit = handleSubmit((values) => {
    if (!token) {
      setErrorMessage(t('resetPassword.missingToken'))
      return
    }
    setErrorMessage(null)
    setSuccessMessage(null)
    mutation.mutate({ token, newPassword: values.newPassword })
  })

  return (
    <Stack spacing={3}>
      <AuthBrand title={t('app.name')} subtitle={t('resetPassword.subtitle')} />

      <Card
        elevation={0}
        sx={{
          border: '1px solid',
          borderColor: 'divider',
          boxShadow: 'var(--shadow-soft)',
          bgcolor: 'background.paper',
          backdropFilter: 'blur(8px)',
        }}
      >
        <CardContent sx={{ p: { xs: 2.5, sm: 3.5 } }}>
          <Typography variant="h5" gutterBottom>
            {t('resetPassword.title')}
          </Typography>
          {!token ? (
            <Alert severity="error" sx={{ mt: 2 }}>
              {t('resetPassword.missingToken')}
            </Alert>
          ) : null}
          <Box component="form" onSubmit={onSubmit} noValidate>
            <Stack spacing={2.5} sx={{ mt: 2 }}>
              <TextField
                label={t('resetPassword.newPassword')}
                type="password"
                autoComplete="new-password"
                fullWidth
                disabled={!token}
                error={Boolean(errors.newPassword)}
                helperText={errors.newPassword?.message}
                {...register('newPassword')}
              />
              <TextField
                label={t('resetPassword.confirmPassword')}
                type="password"
                autoComplete="new-password"
                fullWidth
                disabled={!token}
                error={Boolean(errors.confirmPassword)}
                helperText={errors.confirmPassword?.message}
                {...register('confirmPassword')}
              />
              {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}
              {successMessage ? <Alert severity="success">{successMessage}</Alert> : null}
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={!token || mutation.isPending}
                startIcon={
                  mutation.isPending ? <CircularProgress size={18} color="inherit" /> : null
                }
              >
                {t('resetPassword.submit')}
              </Button>
              <MuiLink
                component={RouterLink}
                to={ROUTES.login}
                variant="body2"
                sx={{ textAlign: 'center' }}
              >
                {t('forgotPassword.backToLogin')}
              </MuiLink>
            </Stack>
          </Box>
        </CardContent>
      </Card>
    </Stack>
  )
}
