import { yupResolver } from '@hookform/resolvers/yup'
import {
  Alert,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import * as yup from 'yup'
import type { SuperAdminCreateRequest } from '@/shared/api/superAdmins'

interface FormValues {
  firstName: string
  lastName: string
  username: string
  email: string
  phone: string
  password: string
  confirmPassword: string
}

const defaults: FormValues = {
  firstName: '',
  lastName: '',
  username: '',
  email: '',
  phone: '',
  password: '',
  confirmPassword: '',
}

const schema: yup.ObjectSchema<FormValues> = yup.object({
  firstName: yup.string().trim().max(128, 'At most 128 characters').required('First name is required'),
  lastName: yup.string().trim().max(128, 'At most 128 characters').required('Last name is required'),
  username: yup
    .string()
    .trim()
    .required('Username is required')
    .min(3, 'At least 3 characters')
    .max(64, 'At most 64 characters'),
  email: yup
    .string()
    .trim()
    .max(255, 'At most 255 characters')
    .email('Enter a valid email address')
    .required('Email is required'),
  phone: yup.string().trim().max(32, 'At most 32 characters').default(''),
  password: yup
    .string()
    .required('Password is required')
    .min(8, 'At least 8 characters')
    .max(128, 'At most 128 characters'),
  confirmPassword: yup
    .string()
    .oneOf([yup.ref('password')], 'Passwords must match')
    .required('Confirm the password'),
})

interface SuperAdminFormDialogProps {
  open: boolean
  loading?: boolean
  /** Backend conflict/validation message for the last attempt, shown above the form. */
  errorMessage?: string | null
  onClose: () => void
  onSubmit: (payload: SuperAdminCreateRequest) => void
}

export function SuperAdminFormDialog({
  open,
  loading = false,
  errorMessage,
  onClose,
  onSubmit,
}: SuperAdminFormDialogProps) {
  const { t } = useTranslation()
  const {
    register,
    handleSubmit,
    reset,
    resetField,
    formState: { errors },
  } = useForm<FormValues>({ resolver: yupResolver(schema), defaultValues: defaults })

  useEffect(() => {
    if (open) reset(defaults)
  }, [open, reset])

  return (
    <Dialog open={open} onClose={loading ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('superAdmin.admins.createTitle')}</DialogTitle>
      <form
        noValidate
        onSubmit={handleSubmit((values) => {
          onSubmit({
            firstName: values.firstName.trim(),
            lastName: values.lastName.trim(),
            username: values.username.trim(),
            email: values.email.trim().toLowerCase(),
            phone: values.phone.trim() || undefined,
            password: values.password,
          })
          // The password does not linger in form state once it has been handed to the request.
          resetField('password')
          resetField('confirmPassword')
        })}
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 0.5 }}>
            {errorMessage ? (
              <Alert severity="error" data-testid="super-admin-form-error">
                {errorMessage}
              </Alert>
            ) : null}
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label={t('superAdmin.admins.fields.firstName')}
                required
                fullWidth
                error={Boolean(errors.firstName)}
                helperText={errors.firstName?.message}
                {...register('firstName')}
              />
              <TextField
                label={t('superAdmin.admins.fields.lastName')}
                required
                fullWidth
                error={Boolean(errors.lastName)}
                helperText={errors.lastName?.message}
                {...register('lastName')}
              />
            </Stack>
            <TextField
              label={t('superAdmin.admins.fields.username')}
              required
              fullWidth
              autoComplete="off"
              error={Boolean(errors.username)}
              helperText={errors.username?.message}
              {...register('username')}
            />
            <TextField
              label={t('superAdmin.admins.fields.email')}
              required
              fullWidth
              type="email"
              autoComplete="off"
              error={Boolean(errors.email)}
              helperText={errors.email?.message}
              {...register('email')}
            />
            <TextField
              label={t('superAdmin.admins.fields.phone')}
              fullWidth
              error={Boolean(errors.phone)}
              helperText={errors.phone?.message}
              {...register('phone')}
            />
            <TextField
              label={t('superAdmin.admins.fields.password')}
              required
              fullWidth
              type="password"
              autoComplete="new-password"
              error={Boolean(errors.password)}
              helperText={errors.password?.message}
              {...register('password')}
            />
            <TextField
              label={t('superAdmin.admins.fields.confirmPassword')}
              required
              fullWidth
              type="password"
              autoComplete="new-password"
              error={Boolean(errors.confirmPassword)}
              helperText={errors.confirmPassword?.message}
              {...register('confirmPassword')}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={loading}
            startIcon={loading ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            {t('superAdmin.admins.create')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
