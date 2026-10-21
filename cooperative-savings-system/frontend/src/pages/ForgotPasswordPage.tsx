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
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { yupResolver } from '@hookform/resolvers/yup'
import * as yup from 'yup'
import { requestPasswordReset } from '@/shared/api/auth'
import { getErrorMessage } from '@/shared/api/client'
import { AuthBrand } from '@/shared/components/AuthBrand'
import { ROUTES } from '@/shared/constants/routes'

interface ForgotPasswordFormValues {
  usernameOrEmail: string
}

const schema = yup.object({
  usernameOrEmail: yup.string().trim().required('Username or email is required'),
})

export function ForgotPasswordPage() {
  const { t } = useTranslation()
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ForgotPasswordFormValues>({
    resolver: yupResolver(schema),
    defaultValues: { usernameOrEmail: '' },
  })

  const mutation = useMutation({
    mutationFn: requestPasswordReset,
    onSuccess: () => {
      setErrorMessage(null)
      setSuccessMessage(t('forgotPassword.success'))
    },
    onError: (error) => {
      setSuccessMessage(null)
      setErrorMessage(getErrorMessage(error, t('errors.generic')))
    },
  })

  const onSubmit = handleSubmit((values) => {
    setErrorMessage(null)
    setSuccessMessage(null)
    mutation.mutate(values)
  })

  return (
    <Stack spacing={3}>
      <AuthBrand title={t('app.name')} subtitle={t('forgotPassword.subtitle')} />

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
            {t('forgotPassword.title')}
          </Typography>
          <Box component="form" onSubmit={onSubmit} noValidate>
            <Stack spacing={2.5} sx={{ mt: 2 }}>
              <TextField
                label={t('forgotPassword.usernameOrEmail')}
                autoComplete="username"
                fullWidth
                error={Boolean(errors.usernameOrEmail)}
                helperText={errors.usernameOrEmail?.message}
                {...register('usernameOrEmail')}
              />
              {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}
              {successMessage ? <Alert severity="success">{successMessage}</Alert> : null}
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={mutation.isPending}
                startIcon={
                  mutation.isPending ? <CircularProgress size={18} color="inherit" /> : null
                }
              >
                {t('forgotPassword.submit')}
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
