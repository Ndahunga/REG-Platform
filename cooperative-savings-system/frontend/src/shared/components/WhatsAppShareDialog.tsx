import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { isValidReportWhatsAppRecipient } from '@/features/reports/reportHelpers'

interface WhatsAppShareDialogProps {
  open: boolean
  pending: boolean
  phone: string
  title: string
  description: string
  sendingLabel: string
  onPhoneChange: (value: string) => void
  onClose: () => void
  onSend: () => void
}

export function WhatsAppShareDialog({
  open,
  pending,
  phone,
  title,
  description,
  sendingLabel,
  onPhoneChange,
  onClose,
  onSend,
}: WhatsAppShareDialogProps) {
  const { t } = useTranslation()
  const valid = isValidReportWhatsAppRecipient(phone)

  return (
    <Dialog
      open={open}
      onClose={() => {
        if (pending) return
        onClose()
      }}
      fullWidth
      maxWidth="xs"
    >
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {description}
        </Typography>
        {pending ? (
          <Alert severity="info" sx={{ mb: 2 }}>
            {sendingLabel}
          </Alert>
        ) : null}
        <TextField
          autoFocus
          fullWidth
          label={t('reports.whatsapp.phone')}
          value={phone}
          onChange={(e) => onPhoneChange(e.target.value)}
          helperText={t('reports.whatsapp.phoneHint')}
          error={Boolean(phone.trim()) && !valid}
          disabled={pending}
          placeholder="07XXXXXXXX"
        />
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={pending}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" disabled={pending || !valid} onClick={onSend}>
          {pending ? sendingLabel : t('reports.whatsapp.send')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
