import DownloadIcon from '@mui/icons-material/Download'
import {
  Alert,
  Box,
  Button,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import { downloadPositionsCsv, fetchMyPosition, fetchSchemePositions, type MemberPosition } from '@/shared/api/positions'
import { LoadingState } from '@/shared/components/LoadingState'
import { hasPermission } from '@/shared/types/auth'

const money = (v: number | string | null | undefined) => Math.round(Number(v ?? 0)).toLocaleString()

/**
 * Where each member stands, for any kind of scheme: capital (what they kept in, per contribution), their equal share of
 * income per share, their rotation position (owed to them, or an interest-free advance they are repaying), loans and
 * fines, and the net. Officers see everyone with the scheme reconciliation; a member sees their own statement.
 */
export function MemberPositionsPanel({ cooperativeId }: { cooperativeId: string }) {
  const user = useAppSelector((s) => s.auth.user)
  const staff =
    hasPermission(user, 'PAYOUT_WRITE') || hasPermission(user, 'CONTRIBUTION_WRITE') || hasPermission(user, 'REPORT_READ')
  return staff ? <SchemePositions cooperativeId={cooperativeId} /> : <MyPosition cooperativeId={cooperativeId} />
}

function SchemePositions({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const query = useQuery({ queryKey: ['positions', cooperativeId], queryFn: () => fetchSchemePositions(cooperativeId) })
  if (query.isLoading) return <LoadingState />
  if (query.isError || !query.data) return <Alert severity="error">{getErrorMessage(query.error, t('errors.generic'))}</Alert>
  const s = query.data
  const diff = Number(s.unexplainedDifference)
  return (
    <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider', mb: 3 }} data-testid="member-positions">
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.5, flexWrap: 'wrap' }} useFlexGap>
        <Typography variant="h6" sx={{ flex: 1 }}>
          {t('positions.title')}
        </Typography>
        <Button
          startIcon={<DownloadIcon />}
          onClick={() =>
            downloadPositionsCsv(cooperativeId).catch((e) => enqueueSnackbar(getErrorMessage(e, t('errors.generic')), { variant: 'error' }))
          }
        >
          {t('positions.export')}
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t('positions.help')}
      </Typography>
      <Alert severity={Math.abs(diff) < 1 ? 'success' : 'warning'} sx={{ mb: 2 }} data-testid="positions-reconciliation">
        {t('positions.reconciliation', {
          expected: money(s.expectedAssets),
          actual: money(s.actualAssets),
          difference: money(diff),
          income: money(s.incomePool),
          perShare: money(s.incomePerShare),
        })}
      </Alert>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t('positions.member')}</TableCell>
              <TableCell align="right">{t('positions.shares')}</TableCell>
              <TableCell align="right">{t('positions.capital')}</TableCell>
              <TableCell align="right">{t('positions.income')}</TableCell>
              <TableCell align="right">{t('positions.rotation')}</TableCell>
              <TableCell align="right">{t('positions.arrears')}</TableCell>
              <TableCell align="right">{t('positions.loans')}</TableCell>
              <TableCell align="right">{t('positions.fines')}</TableCell>
              <TableCell align="right">{t('positions.owed')}</TableCell>
              <TableCell align="right">{t('positions.net')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {s.members.map((m) => (
              <TableRow key={m.memberUserId}>
                <TableCell>{m.memberName}</TableCell>
                <TableCell align="right">{m.shares}</TableCell>
                <TableCell align="right">{money(m.capital)}</TableCell>
                <TableCell align="right">{money(m.incomeShare)}</TableCell>
                <TableCell align="right" sx={{ color: Number(m.rotationPosition) < 0 ? 'warning.main' : undefined }}>
                  {money(m.rotationPosition)}
                </TableCell>
                <TableCell align="right">{money(m.contributionArrears)}</TableCell>
                <TableCell align="right">{money(m.loansOutstanding)}</TableCell>
                <TableCell align="right">{money(m.finesOutstanding)}</TableCell>
                <TableCell align="right">{money(m.owedToMember)}</TableCell>
                <TableCell align="right" sx={{ fontWeight: 600 }}>
                  {money(m.netPosition)}
                </TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell sx={{ fontWeight: 600 }}>{t('positions.total')}</TableCell>
              <TableCell align="right">{s.totalShares}</TableCell>
              <TableCell align="right">{money(s.totalCapital)}</TableCell>
              <TableCell align="right">{money(s.incomePool)}</TableCell>
              <TableCell align="right">{money(s.totalRotationPosition)}</TableCell>
              <TableCell />
              <TableCell align="right">{money(s.totalLoansOutstanding)}</TableCell>
              <TableCell align="right">{money(s.totalFinesOutstanding)}</TableCell>
              <TableCell align="right">{money(s.totalOwedToMembers)}</TableCell>
              <TableCell />
            </TableRow>
          </TableBody>
        </Table>
      </Box>
    </Paper>
  )
}

function MyPosition({ cooperativeId }: { cooperativeId: string }) {
  const query = useQuery({ queryKey: ['positions', cooperativeId, 'me'], queryFn: () => fetchMyPosition(cooperativeId) })
  if (query.isLoading) return <LoadingState />
  if (query.isError || !query.data) return null
  return <Statement position={query.data} />
}

export function Statement({ position: m }: { position: MemberPosition }) {
  const { t } = useTranslation()
  const rotation = Number(m.rotationPosition)
  const rows: [string, number | string][] = [
    [t('positions.openingCapital'), m.openingCapital],
    [t('positions.contributedCapital'), m.contributedCapital],
    [t('positions.sharePurchases'), m.sharePurchases],
    [t('positions.shareOutPaid'), -Number(m.shareOutPaid)],
    [t('positions.capital'), m.capital],
    [t('positions.income'), m.incomeShare],
    [t('positions.rotatedPaid'), Number(m.openingRotationPaid) + Number(m.rotatedPaid)],
    [t('positions.rotationReceived'), -(Number(m.openingRotationReceived) + Number(m.rotationReceived))],
    [t('positions.rotation'), m.rotationPosition],
    [t('positions.loans'), -Number(m.loansOutstanding)],
    [t('positions.fines'), -Number(m.finesOutstanding)],
    [t('positions.owed'), m.owedToMember],
  ]
  return (
    <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider', mb: 3 }} data-testid="my-statement">
      <Typography variant="h6" gutterBottom>
        {t('positions.myTitle')}
      </Typography>
      {rotation < 0 ? (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          {t('positions.advanceNote', { amount: money(-rotation) })}
        </Alert>
      ) : rotation > 0 ? (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          {t('positions.owedNote', { amount: money(rotation) })}
        </Alert>
      ) : null}
      <Table size="small">
        <TableBody>
          {rows.map(([label, value]) => (
            <TableRow key={label}>
              <TableCell>{label}</TableCell>
              <TableCell align="right">{money(value)}</TableCell>
            </TableRow>
          ))}
          <TableRow>
            <TableCell sx={{ fontWeight: 700 }}>{t('positions.net')}</TableCell>
            <TableCell align="right" sx={{ fontWeight: 700 }}>
              {money(m.netPosition)}
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </Paper>
  )
}
