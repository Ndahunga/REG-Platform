import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMembers } from '@/shared/api/members'
import {
  exportReport,
  fetchReportTypes,
  fetchWhatsAppStatus,
  shareReportViaWhatsApp,
} from '@/shared/api/reports'
import { LoadingState } from '@/shared/components/LoadingState'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { LEDGER_TRANSACTION_TYPES } from '@/shared/types/ledger'
import { memberDisplayName } from '@/shared/types/member'
import { MEMBER_PRIMARY_REPORTS, STAFF_PRIMARY_REPORTS } from '@/shared/types/report'
import {
  defaultExportFilename,
  defaultReportFromDate,
  defaultReportToDate,
  isReportStatusValidFor,
  isValidReportWhatsAppRecipient,
  reportStatusOptions,
  reportSupportsFromTo,
  reportSupportsMember,
  reportSupportsStatus,
  reportSupportsTransactionType,
  reportTypeLabelKey,
  validateReportTimeline,
} from './reportHelpers'

interface ReportsExportPanelProps {
  cooperativeId: string
  initialReportType?: string
}

export function ReportsExportPanel({
  cooperativeId,
  initialReportType,
}: ReportsExportPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const { canWrite, readOnly } = useCooperativeSubscription(cooperativeId)
  const shareBlocked = readOnly || !canWrite
  const today = dayjs()
  const todayIso = today.format('YYYY-MM-DD')

  const [reportType, setReportType] = useState(initialReportType ?? '')
  const [fromDate, setFromDate] = useState(defaultReportFromDate(today))
  const [toDate, setToDate] = useState(defaultReportToDate(today))
  const [memberUserId, setMemberUserId] = useState('')
  const [status, setStatus] = useState('')
  const [transactionType, setTransactionType] = useState('')
  const [shareOpen, setShareOpen] = useState(false)
  const [shareType, setShareType] = useState('')
  const [shareIncludeFilters, setShareIncludeFilters] = useState(false)
  const [recipientPhone, setRecipientPhone] = useState('')

  const typesQuery = useQuery({
    queryKey: ['reports', 'types', cooperativeId],
    queryFn: () => fetchReportTypes(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const membersQuery = useQuery({
    queryKey: ['members', cooperativeId, 'report-filter'],
    queryFn: () => fetchMembers(cooperativeId, { status: 'ACTIVE', size: 200, sort: 'firstName,asc' }),
    enabled: Boolean(cooperativeId),
  })

  const whatsappStatusQuery = useQuery({
    queryKey: ['reports', 'whatsapp-status', cooperativeId],
    queryFn: () => fetchWhatsAppStatus(cooperativeId),
    enabled: Boolean(cooperativeId),
    staleTime: 60_000,
  })
  const whatsappConfigured = whatsappStatusQuery.data?.configured === true

  const selectedMeta = useMemo(
    () => typesQuery.data?.find((item) => item.type === reportType) ?? null,
    [typesQuery.data, reportType],
  )
  const selfScoped = Boolean(typesQuery.data?.some((item) => item.selfScoped))

  useEffect(() => {
    if (!typesQuery.data) return
    const allowed = new Set(typesQuery.data.map((item) => String(item.type)))
    setReportType((current) => {
      const candidate = current || initialReportType || ''
      return candidate && allowed.has(candidate) ? candidate : ''
    })
  }, [typesQuery.data, initialReportType])

  const showDates = reportType ? reportSupportsFromTo(reportType, selectedMeta) : true
  const showMember =
    reportType && !selfScoped ? reportSupportsMember(reportType, selectedMeta) : false
  const showStatus = reportType ? reportSupportsStatus(reportType, selectedMeta) : false
  const showTxnType = reportType
    ? reportSupportsTransactionType(reportType, selectedMeta)
    : false

  const statusOptions = reportStatusOptions(reportType)
  const phoneInvalid =
    Boolean(recipientPhone.trim()) && !isValidReportWhatsAppRecipient(recipientPhone)

  // An "All" filter must be visible: MUI renders nothing for an empty select value unless displayEmpty is set.
  const allFilterSelect = (labelOf: (value: string) => string | undefined) => ({
    select: {
      displayEmpty: true,
      renderValue: (value: unknown) => (value ? (labelOf(String(value)) ?? String(value)) : t('common.all')),
    },
    inputLabel: { shrink: true },
  })

  const timelineIssue = validateReportTimeline(fromDate, toDate, today)
  const timelineValid = timelineIssue == null

  const buildExportPayload = (type: string, includeFilters: boolean) => ({
    reportType: type,
    fromDate,
    toDate,
    memberUserId: includeFilters && showMember && memberUserId ? memberUserId : null,
    status:
      includeFilters && showStatus && status && isReportStatusValidFor(type, status)
        ? status
        : null,
    transactionType: includeFilters && showTxnType && transactionType ? transactionType : null,
  })

  const exportMutation = useMutation({
    mutationFn: ({ type, includeFilters }: { type: string; includeFilters: boolean }) => {
      const issue = validateReportTimeline(fromDate, toDate, today)
      if (issue) {
        throw new Error(t(`reports.export.validation.${issue}`))
      }
      return exportReport(
        cooperativeId,
        buildExportPayload(type, includeFilters),
        defaultExportFilename(type),
      )
    },
    onSuccess: ({ filename }) => {
      enqueueSnackbar(t('reports.export.success', { filename }), { variant: 'success' })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const shareMutation = useMutation({
    mutationFn: ({
      type,
      includeFilters,
      phone,
    }: {
      type: string
      includeFilters: boolean
      phone: string
    }) => {
      const issue = validateReportTimeline(fromDate, toDate, today)
      if (issue) {
        throw new Error(t(`reports.export.validation.${issue}`))
      }
      if (!isValidReportWhatsAppRecipient(phone)) {
        throw new Error(t('reports.whatsapp.phoneInvalid'))
      }
      return shareReportViaWhatsApp(cooperativeId, {
        ...buildExportPayload(type, includeFilters),
        recipientPhone: phone.trim(),
      })
    },
    onSuccess: ({ filename }) => {
      enqueueSnackbar(t('reports.whatsapp.success', { filename }), { variant: 'success' })
      setShareOpen(false)
      setRecipientPhone('')
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('reports.whatsapp.failed')), { variant: 'error' })
    },
  })

  const types = typesQuery.data ?? []
  const allowed = new Set(types.map((item) => String(item.type)))
  const actionsBusy = exportMutation.isPending || shareMutation.isPending

  const openShare = (type: string, includeFilters: boolean) => {
    if (shareBlocked) {
      enqueueSnackbar(t('subscription.readOnlyShare'), { variant: 'warning' })
      return
    }
    if (!timelineValid) {
      enqueueSnackbar(t(`reports.export.validation.${timelineIssue}`), { variant: 'warning' })
      return
    }
    if (!whatsappConfigured) {
      enqueueSnackbar(t('reports.whatsapp.notConfigured'), { variant: 'warning' })
      return
    }
    setShareType(type)
    setShareIncludeFilters(includeFilters)
    setShareOpen(true)
  }

  if (typesQuery.isLoading) {
    return <LoadingState />
  }

  const primaryCards = (selfScoped ? MEMBER_PRIMARY_REPORTS : STAFF_PRIMARY_REPORTS)
    .filter((type) => allowed.has(type))
    .map((type) => ({
      type,
      titleKey: reportTypeLabelKey(type),
      descriptionKey: selfScoped
        ? `reports.primary.memberHint`
        : type === 'CONTRIBUTIONS'
          ? 'reports.primary.contributionsHint'
          : type === 'INVESTMENTS'
            ? 'reports.primary.investmentsHint'
            : 'reports.primary.fullHint',
    }))

  const runPrimary = (type: string) => {
    if (!timelineValid) {
      enqueueSnackbar(t(`reports.export.validation.${timelineIssue}`), { variant: 'warning' })
      return
    }
    exportMutation.mutate({ type, includeFilters: false })
  }

  const shareButton = (
    type: string,
    includeFilters: boolean,
    disabled: boolean,
    size: 'small' | 'medium' = 'small',
  ) => {
    const blocked = disabled || actionsBusy || !whatsappConfigured || shareBlocked
    const button = (
      <Button
        type="button"
        variant="outlined"
        size={size}
        onClick={() => openShare(type, includeFilters)}
        disabled={blocked}
      >
        {shareMutation.isPending
          ? t('reports.whatsapp.sending')
          : t('reports.whatsapp.share')}
      </Button>
    )
    const tooltip = shareBlocked
      ? t('subscription.readOnlyShare')
      : !whatsappConfigured
        ? t('reports.whatsapp.notConfigured')
        : ''
    if (!tooltip) return button
    return (
      <Tooltip title={tooltip}>
        <span>{button}</span>
      </Tooltip>
    )
  }

  return (
    <Box>
      {typesQuery.isError ? (
        <Alert
          severity="warning"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void typesQuery.refetch()}>
              {t('common.retry')}
            </Button>
          }
        >
          {getErrorMessage(typesQuery.error, t('errors.generic'))}
        </Alert>
      ) : null}
      {selfScoped ? (
        <Alert severity="info" sx={{ mb: 2, maxWidth: 720 }}>
          {t('reports.memberOnly')}
        </Alert>
      ) : null}
      {!whatsappConfigured ? (
        <Alert severity="info" sx={{ mb: 2, maxWidth: 720 }}>
          {t('reports.whatsapp.notConfigured')}
        </Alert>
      ) : null}
      <Typography variant="h6" gutterBottom>
        {t('reports.export.timelineTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5, maxWidth: 720 }}>
        {t('reports.export.timelineDescription')}
      </Typography>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{
          mb: 3,
          maxWidth: 720,
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: timelineIssue ? 'warning.main' : 'divider',
          borderRadius: 1,
          bgcolor: 'background.paper',
        }}
      >
        <TextField
          type="date"
          required
          label={t('reports.export.fromDate')}
          value={fromDate}
          onChange={(e) => setFromDate(e.target.value)}
          error={Boolean(timelineIssue && timelineIssue !== 'futureTo')}
          helperText={
            timelineIssue && timelineIssue !== 'futureTo'
              ? t(`reports.export.validation.${timelineIssue}`)
              : t('reports.export.timelineFromHint')
          }
          slotProps={{
            inputLabel: { shrink: true },
            htmlInput: {
              max: todayIso,
            },
          }}
          fullWidth
        />
        <TextField
          type="date"
          required
          label={t('reports.export.toDate')}
          value={toDate}
          onChange={(e) => setToDate(e.target.value)}
          error={Boolean(timelineIssue && timelineIssue !== 'futureFrom')}
          helperText={
            timelineIssue && timelineIssue !== 'futureFrom'
              ? t(`reports.export.validation.${timelineIssue}`)
              : t('reports.export.timelineToHint')
          }
          slotProps={{
            inputLabel: { shrink: true },
            htmlInput: { max: todayIso, min: fromDate || undefined },
          }}
          fullWidth
        />
      </Stack>

      {primaryCards.length ? (
        <>
          <Typography variant="h6" gutterBottom>
            {t('reports.primaryTitle')}
          </Typography>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            sx={{ mb: 3 }}
            useFlexGap
          >
            {primaryCards.map((card) => (
              <Box
                key={card.type}
                sx={{
                  flex: 1,
                  p: 2.5,
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 2,
                  bgcolor: 'background.paper',
                  boxShadow: '0 1px 3px rgba(15, 23, 42, 0.06)',
                }}
              >
                <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                  {t(card.titleKey)}
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
                  {t(card.descriptionKey)}
                </Typography>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  <Button
                    variant="contained"
                    size="small"
                    onClick={() => runPrimary(card.type)}
                    disabled={!timelineValid || actionsBusy}
                  >
                    {exportMutation.isPending
                      ? t('reports.export.exporting')
                      : t('reports.export.submit')}
                  </Button>
                  {shareButton(card.type, false, !timelineValid)}
                </Stack>
              </Box>
            ))}
          </Stack>
        </>
      ) : null}

      <Typography variant="h6" gutterBottom>
        {t('reports.advancedTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2, maxWidth: 720 }}>
        {t('reports.export.description')}
      </Typography>

      <Stack
        component="form"
        spacing={2}
        sx={{
          maxWidth: 560,
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1,
        }}
        onSubmit={(event) => {
          event.preventDefault()
          if (!reportType || !timelineValid) return
          exportMutation.mutate({ type: reportType, includeFilters: true })
        }}
      >
        <TextField
          select
          required
          label={t('reports.export.reportType')}
          value={reportType}
          onChange={(e) => {
            setReportType(e.target.value)
            // a status from the previous report type is meaningless for the new one
            setStatus('')
          }}
          fullWidth
        >
          {types.length === 0 ? (
            <MenuItem value="" disabled>
              {t('reports.export.noTypes')}
            </MenuItem>
          ) : (
            types.map((item) => (
              <MenuItem key={item.type} value={item.type}>
                {item.label && item.label !== item.type
                  ? item.label
                  : t(reportTypeLabelKey(item.type), { defaultValue: item.type })}
              </MenuItem>
            ))
          )}
        </TextField>

        {showDates ? (
          <Alert severity="info">{t('reports.export.usesTimeline')}</Alert>
        ) : null}

        {showMember ? (
          <TextField
            select
            label={t('reports.export.member')}
            value={memberUserId}
            onChange={(e) => setMemberUserId(e.target.value)}
            fullWidth
            slotProps={allFilterSelect((id) => {
              const member = (membersQuery.data?.content ?? []).find((m) => m.userId === id)
              return member ? memberDisplayName(member) : undefined
            })}
          >
            <MenuItem value="">{t('common.all')}</MenuItem>
            {(membersQuery.data?.content ?? []).map((member) => (
              <MenuItem key={member.userId} value={member.userId}>
                {memberDisplayName(member)}
              </MenuItem>
            ))}
          </TextField>
        ) : null}

        {showStatus ? (
          <TextField
            select
            label={t('reports.export.status')}
            value={isReportStatusValidFor(reportType, status) ? status : ''}
            onChange={(e) => setStatus(e.target.value)}
            fullWidth
            slotProps={allFilterSelect((value) => {
              const option = statusOptions.find((o) => o.value === value)
              return option ? t(option.labelKey, { defaultValue: value }) : undefined
            })}
          >
            <MenuItem value="">{t('common.all')}</MenuItem>
            {statusOptions.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {t(option.labelKey, { defaultValue: option.value })}
              </MenuItem>
            ))}
          </TextField>
        ) : null}

        {showTxnType ? (
          <TextField
            select
            label={t('reports.export.transactionType')}
            value={transactionType}
            onChange={(e) => setTransactionType(e.target.value)}
            fullWidth
            slotProps={allFilterSelect((value) => t(`ledger.types.${value}`, { defaultValue: value }))}
          >
            <MenuItem value="">{t('common.all')}</MenuItem>
            {LEDGER_TRANSACTION_TYPES.map((type) => (
              <MenuItem key={type} value={type}>
                {t(`ledger.types.${type}`, { defaultValue: type })}
              </MenuItem>
            ))}
          </TextField>
        ) : null}

        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button
            type="submit"
            variant="contained"
            disabled={!reportType || !timelineValid || actionsBusy}
          >
            {exportMutation.isPending
              ? t('reports.export.exporting')
              : t('reports.export.submit')}
          </Button>
          {shareButton(reportType, true, !reportType || !timelineValid, 'medium')}
        </Stack>
      </Stack>

      <Dialog
        open={shareOpen}
        onClose={() => {
          if (shareMutation.isPending) return
          setShareOpen(false)
        }}
        fullWidth
        maxWidth="xs"
      >
        <DialogTitle>{t('reports.whatsapp.dialogTitle')}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('reports.whatsapp.dialogDescription')}
          </Typography>
          {shareMutation.isPending ? (
            <Alert severity="info" sx={{ mb: 2 }}>
              {t('reports.whatsapp.sending')}
            </Alert>
          ) : null}
          <TextField
            autoFocus
            fullWidth
            label={t('reports.whatsapp.phone')}
            value={recipientPhone}
            onChange={(e) => setRecipientPhone(e.target.value)}
            helperText={
              phoneInvalid ? t('reports.whatsapp.phoneInvalid') : t('reports.whatsapp.phoneHint')
            }
            error={phoneInvalid}
            disabled={shareMutation.isPending}
            placeholder="07XXXXXXXX"
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={() => setShareOpen(false)}
            disabled={shareMutation.isPending}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            disabled={
              shareBlocked ||
              shareMutation.isPending ||
              !isValidReportWhatsAppRecipient(recipientPhone)
            }
            onClick={() =>
              shareMutation.mutate({
                type: shareType,
                includeFilters: shareIncludeFilters,
                phone: recipientPhone,
              })
            }
          >
            {shareMutation.isPending
              ? t('reports.whatsapp.sending')
              : t('reports.whatsapp.send')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
