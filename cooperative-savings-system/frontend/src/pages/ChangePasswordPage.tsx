import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Stack,
  TextField,
} from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { yupResolver } from '@hookform/resolvers/yup'
import * as yup from 'yup'
import { setCredentials } from '@/app/store/authSlice'
import { useAppDispatch } from '@/app/store/hooks'
import { changePassword } from '@/shared/api/auth'
import { getErrorMessage } from '@/shared/api/client'
import { PageHeader } from '@/shared/components/PageHeader'
import { usePortalRoutes } from '@/shared/constants/portal'

interface ChangePasswordFormValues {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

const schema = yup.object({
  currentPassword: yup.string().required('Current password is required'),
  newPassword: yup
    .string()
    .min(8, 'At least 8 characters')
    .max(128, 'At most 128 characters')
    .notOneOf([yup.ref('currentPassword')], 'New password must be different from current password')
    .required('New password is required'),
  confirmPassword: yup
    .string()
    .oneOf([yup.ref('newPassword')], 'Passwords must match')
    .required('Confirm your password'),
})

export function ChangePasswordPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const routes = usePortalRoutes()
  const dispatch = useAppDispatch()
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ChangePasswordFormValues>({
    resolver: yupResolver(schema),
    defaultValues: {
      currentPassword: '',
      newPassword: '',
      confirmPassword: '',
    },
  })

  const mutation = useMutation({
    mutationFn: changePassword,
    onSuccess: (data) => {
      dispatch(setCredentials({ user: data.user, accessToken: data.accessToken }))
      setErrorMessage(null)
      setSuccessMessage(t('changePassword.success'))
      reset()
    },
    onError: (error) => {
      setSuccessMessage(null)
      setErrorMessage(getErrorMessage(error, t('errors.generic')))
    },
  })

  const onSubmit = handleSubmit((values) => {
    setErrorMessage(null)
    setSuccessMessage(null)
    mutation.mutate({
      currentPassword: values.currentPassword,
      newPassword: values.newPassword,
    })
  })

  return (
    <Box>
      <PageHeader
        title={t('changePassword.title')}
        description={t('changePassword.description')}
        backTo={routes.profile}
      />
      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', maxWidth: 520 }}>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Box component="form" onSubmit={onSubmit} noValidate>
            <Stack spacing={2.5}>
              <TextField
                label={t('changePassword.currentPassword')}
                type="password"
                autoComplete="current-password"
                fullWidth
                error={Boolean(errors.currentPassword)}
                helperText={errors.currentPassword?.message}
                {...register('currentPassword')}
              />
              <TextField
                label={t('changePassword.newPassword')}
                type="password"
                autoComplete="new-password"
                fullWidth
                error={Boolean(errors.newPassword)}
                helperText={errors.newPassword?.message}
                {...register('newPassword')}
              />
              <TextField
                label={t('changePassword.confirmPassword')}
                type="password"
                autoComplete="new-password"
                fullWidth
                error={Boolean(errors.confirmPassword)}
                helperText={errors.confirmPassword?.message}
                {...register('confirmPassword')}
              />
              {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}
              {successMessage ? <Alert severity="success">{successMessage}</Alert> : null}
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
                <Button
                  type="submit"
                  variant="contained"
                  disabled={mutation.isPending}
                  startIcon={
                    mutation.isPending ? <CircularProgress size={18} color="inherit" /> : null
                  }
                >
                  {t('changePassword.submit')}
                </Button>
                <Button variant="outlined" onClick={() => navigate(routes.profile)}>
                  {t('common.cancel')}
                </Button>
              </Stack>
            </Stack>
          </Box>
        </CardContent>
      </Card>
    </Box>
  )
}
