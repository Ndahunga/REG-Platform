import DownloadIcon from '@mui/icons-material/Download'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { downloadOpeningWorkbookTemplate, importOpeningWorkbook } from '@/shared/api/positions'

const money = (v: number | string | null | undefined) => Math.round(Number(v ?? 0)).toLocaleString()

/**
 * Third migration workbook, after members/share capital and loans: the running rotation (who is paid when, what each
 * turn already received, rotated money each member paid), what the scheme owes members, and income not yet shared.
 */
export function OpeningWorkbookPanel({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const input = useRef<HTMLInputElement>(null)
  const upload = useMutation({
    mutationFn: (file: File) => importOpeningWorkbook(cooperativeId, file),
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
          <Button startIcon={<UploadFileIcon />} variant="contained" disabled={upload.isPending} onClick={() => input.current?.click()}>
            {t('openingWorkbook.upload')}
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
      </Stack>
    </Paper>
  )
}
