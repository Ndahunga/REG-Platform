import {
  Alert,
  Box,
  Button,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage } from '@/shared/api/client'
import { createFine } from '@/shared/api/fines'
import { fetchMembers } from '@/shared/api/members'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ROUTES } from '@/shared/constants/routes'
import { FINE_CALCULATION_MODES } from '@/shared/types/fine'
import { memberDisplayName } from '@/shared/types/member'
import {
  fineIssueDefaults,
  fineIssueSchema,
  toFineCreatePayload,
  type FineIssueFormValues,
} from './fineFormSchemas'

interface FineIssuePanelProps {
  cooperativeId: string
}

export function FineIssuePanel({ cooperativeId }: FineIssuePanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()

  const membersQuery = useQuery({
    queryKey: ['members', cooperativeId, 'fine-select'],
    queryFn: () => fetchMembers(cooperativeId, { status: 'ACTIVE', size: 200 }),
    enabled: Boolean(cooperativeId),
  })

  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<FineIssueFormValues>({
    defaultValues: fineIssueDefaults,
    resolver: yupResolver(fineIssueSchema),
  })

  const calculationMode = useWatch({ control, name: 'calculationMode' })

  const mutation = useMutation({
    mutationFn: (values: FineIssueFormValues) =>
      createFine(cooperativeId, toFineCreatePayload(values)),
    onSuccess: (fine) => {
      enqueueSnackbar(t('fines.issue.success'), { variant: 'success' })
      reset(fineIssueDefaults)
      void queryClient.invalidateQueries({ queryKey: ['fines'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      navigate(ROUTES.fineDetail(fine.id))
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  if (membersQuery.isLoading) return <LoadingState variant="skeleton" rows={3} />
  if (membersQuery.isError) {
    return (
      <ErrorState
        message={getErrorMessage(membersQuery.error)}
        onRetry={() => void membersQuery.refetch()}
      />
    )
  }

  return (
    <Box
      component="form"
      noValidate
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      sx={{
        maxWidth: 560,
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {t('fines.issue.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('fines.issue.description')}
      </Typography>

      <Alert severity="info" sx={{ mb: 2 }}>
        {t('fines.issue.hint')}
      </Alert>

      <Stack spacing={2}>
        <TextField
          select
          label={t('fines.fields.member')}
          error={Boolean(errors.memberUserId)}
          helperText={errors.memberUserId?.message}
          {...register('memberUserId')}
          fullWidth
        >
          {(membersQuery.data?.content ?? []).map((member) => (
            <MenuItem key={member.userId} value={member.userId}>
              {memberDisplayName(member)}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          label={t('fines.fields.calculationMode')}
          error={Boolean(errors.calculationMode)}
          helperText={errors.calculationMode?.message}
          {...register('calculationMode')}
          fullWidth
        >
          {FINE_CALCULATION_MODES.map((mode) => (
            <MenuItem key={mode} value={mode}>
              {t(`fines.calculationMode.${mode}`)}
            </MenuItem>
          ))}
        </TextField>
        {calculationMode === 'FIXED' ? (
          <TextField
            label={t('fines.fields.amount')}
            error={Boolean(errors.amount)}
            helperText={errors.amount?.message}
            {...register('amount')}
            fullWidth
          />
        ) : (
          <>
            <TextField
              label={t('fines.fields.baseAmount')}
              error={Boolean(errors.baseAmount)}
              helperText={errors.baseAmount?.message}
              {...register('baseAmount')}
              fullWidth
            />
            <TextField
              label={t('fines.fields.dailyIncrement')}
              error={Boolean(errors.dailyIncrement)}
              helperText={errors.dailyIncrement?.message}
              {...register('dailyIncrement')}
              fullWidth
            />
            <TextField
              label={t('fines.fields.overdueDays')}
              error={Boolean(errors.overdueDays)}
              helperText={errors.overdueDays?.message}
              {...register('overdueDays')}
              fullWidth
            />
          </>
        )}
        <TextField
          label={t('fines.fields.reason')}
          error={Boolean(errors.reason)}
          helperText={errors.reason?.message}
          {...register('reason')}
          fullWidth
          multiline
          minRows={2}
        />
        <TextField
          label={t('fines.fields.notes')}
          {...register('notes')}
          fullWidth
          multiline
          minRows={2}
        />
        <TextField
          type="date"
          label={t('fines.fields.issuedDate')}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
          error={Boolean(errors.issuedDate)}
          helperText={errors.issuedDate?.message}
          {...register('issuedDate')}
          fullWidth
        />
        <TextField
          type="date"
          label={t('fines.fields.dueDate')}
          slotProps={{ inputLabel: { shrink: true } }}
          error={Boolean(errors.dueDate)}
          helperText={errors.dueDate?.message}
          {...register('dueDate')}
          fullWidth
        />
        <Button
          type="submit"
          variant="contained"
          disabled={mutation.isPending}
          sx={{ alignSelf: 'flex-start' }}
        >
          {t('fines.issue.submit')}
        </Button>
      </Stack>
    </Box>
  )
}
