import EditIcon from '@mui/icons-material/Edit'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import * as yup from 'yup'
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { setCredentials, selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { updateMe } from '@/shared/api/auth'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMyCooperatives } from '@/shared/api/cooperatives'
import { PageHeader } from '@/shared/components/PageHeader'
import { RoleDutiesNote } from '@/shared/components/RoleDutiesNote'
import { usePortalRoutes } from '@/shared/constants/portal'
import type { AuthUser } from '@/shared/types/auth'

interface ProfileFormValues {
  firstName: string
  lastName: string
  username: string
  email: string
}

const profileSchema = yup.object({
  firstName: yup.string().trim().required('First name is required').max(128, 'At most 128 characters'),
  lastName: yup.string().trim().required('Last name is required').max(128, 'At most 128 characters'),
  username: yup
    .string()
    .trim()
    .required('Username is required')
    .min(3, 'At least 3 characters')
    .max(64, 'At most 64 characters')
    .matches(/^[a-zA-Z0-9._-]+$/, 'Use letters, numbers, . _ - only'),
  email: yup
    .string()
    .trim()
    .required('Email is required')
    .max(255, 'At most 255 characters')
    .email('Enter a valid email'),
})

function fromUser(user: AuthUser): ProfileFormValues {
  return {
    firstName: user.firstName ?? '',
    lastName: user.lastName ?? '',
    username: user.username ?? '',
    email: user.email ?? '',
  }
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {label}
      </Typography>
      <Typography variant="body1" sx={{ fontWeight: 500 }}>
        {value || '—'}
      </Typography>
    </Box>
  )
}

export function ProfilePage() {
  const routes = usePortalRoutes()
  const { t } = useTranslation()
  const dispatch = useAppDispatch()
  const { enqueueSnackbar } = useSnackbar()
  const user = useAppSelector((s) => s.auth.user)
  const accessToken = useAppSelector((s) => s.auth.accessToken)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const [editOpen, setEditOpen] = useState(false)

  const cooperativesQuery = useQuery({
    queryKey: ['cooperatives', 'mine'],
    queryFn: fetchMyCooperatives,
    enabled: Boolean(user),
  })

  const cooperativeChips = useMemo(() => {
    const ids = user?.cooperativeIds ?? []
    const byId = new Map((cooperativesQuery.data ?? []).map((coop) => [coop.id, coop.name]))
    return ids.map((id) => ({ id, name: byId.get(id) || '' })).filter((item) => item.name)
  }, [user?.cooperativeIds, cooperativesQuery.data])

  return (
    <Box>
      <PageHeader
        title={t('pages.profile.title')}
        description={t('pages.profile.description')}
        actions={
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <Button
              variant="outlined"
              startIcon={<EditIcon />}
              onClick={() => setEditOpen(true)}
              disabled={!user}
            >
              {t('common.edit')}
            </Button>
            <Button component={RouterLink} to={routes.changePassword} variant="contained">
              {t('profile.changePassword')}
            </Button>
          </Stack>
        }
      />

      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', maxWidth: 640 }}>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          {!user ? (
            <Typography color="text.secondary">{t('errors.unauthorized')}</Typography>
          ) : (
            <Stack spacing={2.5}>
              <InfoRow label={t('profile.fullName')} value={user.fullName} />
              <InfoRow label={t('profile.username')} value={user.username} />
              <InfoRow label={t('profile.email')} value={user.email} />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2.5}>
                <InfoRow label={t('profile.firstName')} value={user.firstName} />
                <InfoRow label={t('profile.lastName')} value={user.lastName} />
              </Stack>
              <Divider />
              <Box>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                  {t('profile.roles')}
                </Typography>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  {user.roles.length ? (
                    user.roles.map((role) => (
                      <Chip
                        key={role}
                        label={t(`roles.${role}`, { defaultValue: role })}
                        size="small"
                        color="primary"
                      />
                    ))
                  ) : (
                    <Typography variant="body2">—</Typography>
                  )}
                </Stack>
                <Box sx={{ mt: 1.5 }}>
                  <RoleDutiesNote roles={user.roles} compact />
                </Box>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                  {t('profile.cooperatives')}
                </Typography>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  {cooperativesQuery.isLoading && (user.cooperativeIds?.length ?? 0) > 0 ? (
                    <CircularProgress size={18} />
                  ) : cooperativeChips.length ? (
                    cooperativeChips.map((coop) => (
                      <Chip key={coop.id} label={coop.name} size="small" variant="outlined" />
                    ))
                  ) : (
                    <Typography variant="body2">
                      {isSuperAdmin ? t('profile.notAMember') : '—'}
                    </Typography>
                  )}
                </Stack>
              </Box>
            </Stack>
          )}
        </CardContent>
      </Card>

      {user ? (
        <ProfileEditDialog
          open={editOpen}
          user={user}
          onClose={() => setEditOpen(false)}
          onSaved={(updated) => {
            if (accessToken) {
              dispatch(setCredentials({ user: updated, accessToken }))
            }
            setEditOpen(false)
            enqueueSnackbar(t('profile.updateSuccess'), { variant: 'success' })
          }}
        />
      ) : null}
    </Box>
  )
}

function ProfileEditDialog({
  open,
  user,
  onClose,
  onSaved,
}: {
  open: boolean
  user: AuthUser
  onClose: () => void
  onSaved: (user: AuthUser) => void
}) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ProfileFormValues>({
    resolver: yupResolver(profileSchema),
    defaultValues: fromUser(user),
  })

  useEffect(() => {
    if (!open) return
    reset(fromUser(user))
  }, [open, user, reset])

  const mutation = useMutation({
    mutationFn: (values: ProfileFormValues) =>
      updateMe({
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        username: values.username.trim(),
        email: values.email.trim(),
      }),
    onSuccess: onSaved,
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  return (
    <Dialog open={open} onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('profile.editTitle')}</DialogTitle>
      <form
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
        noValidate
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 0.5 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label={t('profile.firstName')}
                required
                fullWidth
                error={Boolean(errors.firstName)}
                helperText={errors.firstName?.message}
                {...register('firstName')}
              />
              <TextField
                label={t('profile.lastName')}
                required
                fullWidth
                error={Boolean(errors.lastName)}
                helperText={errors.lastName?.message}
                {...register('lastName')}
              />
            </Stack>
            <TextField
              label={t('profile.username')}
              required
              fullWidth
              error={Boolean(errors.username)}
              helperText={errors.username?.message || t('profile.usernameHint')}
              {...register('username')}
            />
            <TextField
              label={t('profile.email')}
              required
              fullWidth
              type="email"
              error={Boolean(errors.email)}
              helperText={errors.email?.message}
              {...register('email')}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose} disabled={mutation.isPending}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={mutation.isPending}
            startIcon={mutation.isPending ? <CircularProgress size={18} color="inherit" /> : null}
          >
            {t('common.save')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
