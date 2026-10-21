import {
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Link,
} from '@mui/material'
import { useSnackbar } from 'notistack'
import { useCallback, useEffect, useState, type MouseEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchStoredFile } from '@/shared/api/files'
import { getErrorMessage } from '@/shared/api/client'
import { triggerBlobDownload } from '@/shared/utils/download'

interface AuthenticatedFileLinkProps {
  storageKey: string
  children: ReactNode
  variant?: 'link' | 'button'
}

interface FilePreview {
  blob: Blob
  url: string
  contentType: string
  filename: string
}

function isImageType(contentType: string, filename: string): boolean {
  if (contentType.startsWith('image/')) return true
  return /\.(png|jpe?g|webp|gif)$/i.test(filename)
}

function isPdfType(contentType: string, filename: string): boolean {
  if (contentType === 'application/pdf') return true
  return /\.pdf$/i.test(filename)
}

export function AuthenticatedFileLink({
  storageKey,
  children,
  variant = 'link',
}: AuthenticatedFileLinkProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const [loading, setLoading] = useState(false)
  const [preview, setPreview] = useState<FilePreview | null>(null)

  useEffect(() => {
    return () => {
      if (preview?.url) URL.revokeObjectURL(preview.url)
    }
  }, [preview])

  const closePreview = useCallback(() => {
    setPreview(null)
  }, [])

  const handleClick = async (event: MouseEvent<HTMLElement>) => {
    event.preventDefault()
    event.stopPropagation()
    if (loading || !storageKey) return
    setLoading(true)
    try {
      const file = await fetchStoredFile(storageKey)
      const url = URL.createObjectURL(file.blob)
      if (isImageType(file.contentType, file.filename) || isPdfType(file.contentType, file.filename)) {
        setPreview({
          blob: file.blob,
          url,
          contentType: file.contentType,
          filename: file.filename,
        })
      } else {
        triggerBlobDownload(file.blob, file.filename)
        URL.revokeObjectURL(url)
      }
    } catch (error) {
      enqueueSnackbar(getErrorMessage(error, t('files.openFailed')), { variant: 'error' })
    } finally {
      setLoading(false)
    }
  }

  const control =
    variant === 'button' ? (
      <Button
        size="small"
        onClick={handleClick}
        disabled={loading}
        startIcon={loading ? <CircularProgress size={14} color="inherit" /> : undefined}
      >
        {children}
      </Button>
    ) : (
      <Link
        component="button"
        type="button"
        underline="hover"
        onClick={handleClick}
        disabled={loading}
        sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, verticalAlign: 'baseline' }}
      >
        {loading ? <CircularProgress size={12} color="inherit" /> : null}
        {children}
      </Link>
    )

  const showImage = preview ? isImageType(preview.contentType, preview.filename) : false
  const showPdf = preview ? isPdfType(preview.contentType, preview.filename) : false

  return (
    <>
      {control}
      <Dialog open={Boolean(preview)} onClose={closePreview} fullWidth maxWidth="md">
        <DialogTitle>{preview?.filename || t('files.previewTitle')}</DialogTitle>
        <DialogContent>
          {showImage ? (
            <Box
              component="img"
              src={preview?.url}
              alt={preview?.filename || t('files.previewTitle')}
              sx={{ display: 'block', maxWidth: '100%', maxHeight: '70vh', mx: 'auto' }}
            />
          ) : null}
          {showPdf ? (
            <Box
              component="iframe"
              title={preview?.filename || t('files.previewTitle')}
              src={preview?.url}
              sx={{ width: '100%', height: '70vh', border: 0 }}
            />
          ) : null}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button
            onClick={() => {
              if (preview) triggerBlobDownload(preview.blob, preview.filename)
            }}
          >
            {t('files.download')}
          </Button>
          <Button variant="contained" onClick={closePreview}>
            {t('common.close')}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
