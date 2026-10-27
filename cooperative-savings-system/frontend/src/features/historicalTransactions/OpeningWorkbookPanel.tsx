import DownloadIcon from '@mui/icons-material/Download'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import {
  Alert,
  Box,
  Button,
  Chip,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import {
  downloadOpeningWorkbookTemplate,
  fetchOpeningWorkbookHistory,
  importOpeningWorkbook,
} from '@/shared/api/positions'

const money = (v: number | string | null | undefined) => Math.round(Number(v ?? 0)).toLocaleString()

/**
 * Third migration workbook, after members/share capital and loans: the running rotation (who is paid when, what each
 * turn already received, rotated money each member paid), what the scheme owes members, and income not yet shared.
 * Once imported it can be corrected: a new upload replaces the current one (which stays listed as replaced) and needs
 * the reason for the correction.
 */
export function OpeningWorkbookPanel({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [reason, setReason] = useState('')
  const history = useQuery({
    queryKey: ['opening-workbook-history', cooperativeId],
    queryFn: () => fetchOpeningWorkbookHistory(cooperativeId),
  })
  const hasCurrent = (history.data ?? []).some((u) => u.status === 'CURRENT')
  const reasonMissing = hasCurrent && reason.trim().length < 10
  const upload = useMutation({
    mutationFn: (file: File) => importOpeningWorkbook(cooperativeId, file, hasCurrent ? reason.trim() : undefined),
    onSuccess: () => {
      setReason('')
      void queryClient.invalidateQueries({ queryKey: ['opening-workbook-history', cooperativeId] })
      void queryClient.invalidateQueries({ queryKey: ['positions'] })
      void queryClient.invalidateQueries({ queryKey: ['rotation'] })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })
  const r = upload.data
  return (
    <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }} data-testid="opening-workbook">
      <Typography variant="h6" gutterBottom>
        {t('openingWorkbook.title')}
      </Typography>
      <Stack spacing={1.5}>
        <Alert severity="info">{t('openingWorkbook.help')}</Alert>
        <Typography variant="body2" component="div">
          <ol style={{ margin: 0, paddingLeft: 20 }}>
            <li>{t('openingWorkbook.step1')}</li>
            <li>{t('openingWorkbook.step2')}</li>
            <li>{t('openingWorkbook.step3')}</li>
          </ol>
        </Typography>
        {hasCurrent ? (
          <>
            <Alert severity="warning">{t('openingWorkbook.revision.notice')}</Alert>
            <TextField
              label={t('openingWorkbook.revision.reason')}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              helperText={t('openingWorkbook.revision.reasonHint')}
              multiline
              minRows={2}
              slotProps={{ htmlInput: { maxLength: 500 } }}
            />
          </>
        ) : null}
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button
            startIcon={<DownloadIcon />}
            variant="outlined"
            onClick={() =>
              downloadOpeningWorkbookTemplate(cooperativeId).catch((e) =>
                enqueueSnackbar(getErrorMessage(e, t('errors.generic')), { variant: 'error' }),
              )
            }
          >
            {t('openingWorkbook.download')}
          </Button>
          <Button
            startIcon={<UploadFileIcon />}
            variant="contained"
            disabled={upload.isPending || reasonMissing}
            onClick={() => input.current?.click()}
          >
            {hasCurrent ? t('openingWorkbook.revision.upload') : t('openingWorkbook.upload')}
          </Button>
          <input
            ref={input}
            type="file"
            hidden
            accept=".xlsx"
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) upload.mutate(file)
              e.target.value = ''
            }}
          />
        </Stack>
        {r ? (
          <Box>
            <Alert severity={Number(r.rotation?.difference ?? 0) === 0 ? 'success' : 'warning'}>
              {t('openingWorkbook.result', {
                members: r.membersUpdated,
                owed: money(r.totalOwedToMembers),
                income: money(r.unallocatedIncome),
              })}
              {r.rotation
                ? ' ' +
                  t('openingWorkbook.rotationResult', {
                    paid: money(r.rotation.totalRotatedPaid),
                    received: money(r.rotation.totalReceived),
                    expected: money(r.rotation.expectedRotationCash),
                    difference: money(r.rotation.difference),
                  })
                : ''}
            </Alert>
          </Box>
        ) : null}
        {(history.data ?? []).length > 0 ? (
          <Box sx={{ overflowX: 'auto' }}>
            <Typography variant="subtitle2" sx={{ mt: 1, mb: 0.5 }}>
              {t('openingWorkbook.revision.historyTitle')}
            </Typography>
            <Table size="small" data-testid="opening-workbook-history">
              <TableHead>
                <TableRow>
                  <TableCell>{t('openingWorkbook.revision.version')}</TableCell>
                  <TableCell>{t('openingWorkbook.revision.file')}</TableCell>
                  <TableCell>{t('openingWorkbook.revision.uploadedBy')}</TableCell>
                  <TableCell>{t('openingWorkbook.revision.uploadedAt')}</TableCell>
                  <TableCell>{t('openingWorkbook.revision.status')}</TableCell>
                  <TableCell>{t('openingWorkbook.revision.why')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(history.data ?? []).map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>{u.version}</TableCell>
                    <TableCell>{u.originalFilename ?? '—'}</TableCell>
                    <TableCell>{u.uploadedByName || '—'}</TableCell>
                    <TableCell>{u.uploadedAt ? dayjs(u.uploadedAt).format('YYYY-MM-DD HH:mm') : '—'}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        color={u.status === 'CURRENT' ? 'success' : 'default'}
                        label={t(`openingWorkbook.revision.statuses.${u.status}`, { defaultValue: u.status })}
                      />
                    </TableCell>
                    <TableCell>{u.revisionReason ?? '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        ) : null}
      </Stack>
    </Paper>
  )
}
