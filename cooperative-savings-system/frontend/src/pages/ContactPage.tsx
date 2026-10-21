import SendIcon from '@mui/icons-material/Send'
import WhatsAppIcon from '@mui/icons-material/WhatsApp'
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Container,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation } from '@tanstack/react-query'
import { useMemo } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import {
  buildContactSchema,
  contactDefaults,
  COUNTRY_CODES,
  CONTACT_LIMITS,
  toContactPayload,
  type ContactFormValues,
} from '@/features/contact/contactForm'
import { sendContactMessage } from '@/shared/api/contact'
import { whatsappHrefWithText } from '@/shared/constants/supportContacts'

const WHATSAPP_GREEN = '#1FA855'

/**
 * Public contact page: a compact form whose message is emailed to OuWealth support by the backend. The page only
 * says "sent" once the backend confirmed it; a failure keeps what the visitor typed.
 */
export function ContactPage() {
  const { t } = useTranslation()
  const schema = useMemo(() => buildContactSchema(t), [t])

  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ContactFormValues>({
    resolver: yupResolver(schema),
    defaultValues: contactDefaults,
  })

  const mutation = useMutation({
    mutationFn: (values: ContactFormValues) => sendContactMessage(toContactPayload(values)),
    // clear the form only after the backend accepted the message
    onSuccess: () => reset(contactDefaults),
  })

  const onValid = handleSubmit((values) => {
    if (mutation.isPending) return
    mutation.mutate(values)
  })

  return (
    <Box data-testid="contact-page" sx={{ py: { xs: 4, md: 7 } }}>
      <Container maxWidth={false} sx={{ maxWidth: 608 }} data-testid="contact-column">
        <Typography variant="h2" component="h1" gutterBottom sx={{ fontSize: { xs: '2rem', md: '2.5rem' } }}>
          {t('public.contact.title')}
        </Typography>
        <Typography color="text.secondary" component="p" sx={{ mb: 3 }}>
          {t('public.contact.intro')}
        </Typography>

        <Paper
          elevation={0}
          data-testid="contact-form-card"
          sx={{
            width: '100%',
            maxWidth: 560,
            mx: 'auto',
            p: { xs: 2.5, sm: 3.5 },
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 3,
            boxShadow: '0 12px 32px rgba(15, 23, 42, 0.08)',
          }}
        >
          <Typography variant="h5" component="h2" gutterBottom>
            {t('public.contact.formTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
            {t('public.contact.formHint')}
          </Typography>

          <Box component="form" onSubmit={onValid} noValidate data-testid="contact-form">
            <Stack spacing={2.5}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  label={t('public.contact.firstName')}
                  autoComplete="given-name"
                  required
                  fullWidth
                  error={Boolean(errors.firstName)}
                  helperText={errors.firstName?.message}
                  slotProps={{ htmlInput: { maxLength: CONTACT_LIMITS.name + 20 } }}
                  {...register('firstName')}
                />
                <TextField
                  label={t('public.contact.lastName')}
                  autoComplete="family-name"
                  required
                  fullWidth
                  error={Boolean(errors.lastName)}
                  helperText={errors.lastName?.message}
                  slotProps={{ htmlInput: { maxLength: CONTACT_LIMITS.name + 20 } }}
                  {...register('lastName')}
                />
              </Stack>
              <TextField
                label={t('public.contact.email')}
                type="email"
                autoComplete="email"
                required
                fullWidth
                error={Boolean(errors.email)}
                helperText={errors.email?.message}
                {...register('email')}
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <Controller
                  name="countryCode"
                  control={control}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      select
                      label={t('public.contact.countryCode')}
                      required
                      error={Boolean(errors.countryCode)}
                      helperText={errors.countryCode?.message}
                      sx={{ width: { sm: 190 }, flexShrink: 0 }}
                    >
                      {COUNTRY_CODES.map((country) => (
                        <MenuItem key={country.iso} value={country.dial}>
                          {country.label}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
                <TextField
                  label={t('public.contact.phone')}
                  type="tel"
                  autoComplete="tel-national"
                  placeholder="07XXXXXXXX"
                  fullWidth
                  error={Boolean(errors.phone)}
                  helperText={errors.phone?.message ?? t('public.contact.phoneHint')}
                  {...register('phone')}
                />
              </Stack>
              <TextField
                label={t('public.contact.message')}
                required
                fullWidth
                multiline
                minRows={4}
                error={Boolean(errors.message)}
                helperText={errors.message?.message}
                {...register('message')}
              />

              {mutation.isSuccess ? (
                <Alert severity="success" data-testid="contact-sent">
                  {t('public.contact.sent')}
                </Alert>
              ) : null}
              {mutation.isError ? (
                <Alert severity="error" data-testid="contact-failed">
                  {t('public.contact.failed')}
                </Alert>
              ) : null}

              <Stack
                direction="row"
                useFlexGap
                spacing={1.5}
                data-testid="contact-actions"
                sx={{ justifyContent: 'flex-end', flexWrap: 'wrap' }}
              >
                <Button
                  component="a"
                  href={whatsappHrefWithText(t('public.contact.whatsappPrefill'))}
                  target="_blank"
                  rel="noopener noreferrer"
                  variant="outlined"
                  startIcon={<WhatsAppIcon />}
                  sx={{
                    width: 'auto',
                    minWidth: 140,
                    minHeight: 44,
                    px: 3,
                    color: WHATSAPP_GREEN,
                    borderColor: WHATSAPP_GREEN,
                    '&:hover': { borderColor: '#188A45', bgcolor: 'rgba(31,168,85,0.08)' },
                  }}
                >
                  {t('public.contact.whatsappChat')}
                </Button>
                <Button
                  type="submit"
                  variant="contained"
                  disabled={mutation.isPending}
                  startIcon={
                    mutation.isPending ? <CircularProgress size={18} color="inherit" /> : <SendIcon />
                  }
                  sx={{ width: 'auto', minWidth: 140, minHeight: 44, px: 3 }}
                >
                  {mutation.isPending ? t('public.contact.sending') : t('public.contact.send')}
                </Button>
              </Stack>
            </Stack>
          </Box>
        </Paper>
      </Container>
    </Box>
  )
}
