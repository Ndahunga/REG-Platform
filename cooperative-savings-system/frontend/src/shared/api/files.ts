import { apiClient } from './client'
import { unwrapApiData } from './auth'
import { parseContentDispositionFilename, throwIfBlobError } from '@/shared/utils/download'

export type FileUploadCategory =
  | 'FINE_PAYMENT_EVIDENCE'
  | 'CONTRIBUTION_EVIDENCE'
  | 'SHARE_PURCHASE_EVIDENCE'
  | 'SOCIAL_EVIDENCE'
  | 'INVESTMENT_DOCUMENT'
  | 'INCOME_EXPENSE_DOCUMENT'
  | 'GENERAL_DOCUMENT'

export interface StoredFileInfo {
  id: string
  cooperativeId?: string
  originalFilename: string
  storageKey: string
  contentType?: string
  sizeBytes?: number
  category?: string
  downloadUrl?: string
}

export interface StoredFileBlob {
  blob: Blob
  contentType: string
  filename: string
}

export async function uploadCooperativeFile(
  cooperativeId: string,
  file: File,
  category: FileUploadCategory,
): Promise<StoredFileInfo> {
  const form = new FormData()
  form.append('file', file)
  form.append('category', category)
  const { data } = await apiClient.post(
    `/cooperatives/${cooperativeId}/files`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  )
  return unwrapApiData<StoredFileInfo>(data)
}

export function normalizeStorageKey(storageKey: string): string {
  return storageKey.replace(/^\/+/, '').replace(/^api\/v1\/files\//, '')
}

/** Relative API path used by `apiClient` (base URL already includes `/api/v1`). */
export function fileApiPath(storageKey: string): string {
  return `/files/${normalizeStorageKey(storageKey)}`
}

export function fileDownloadPath(storageKey: string): string {
  return `/api/v1/files/${normalizeStorageKey(storageKey)}`
}

export function fallbackFilename(storageKey: string): string {
  const last = normalizeStorageKey(storageKey).split('/').pop()
  return last && last.trim() ? last : 'file'
}

/** Fetch a stored file with the session Bearer token (browser navigation cannot send it). */
export async function fetchStoredFile(storageKey: string): Promise<StoredFileBlob> {
  const response = await apiClient.get(fileApiPath(storageKey), {
    responseType: 'blob',
    headers: {
      Accept: 'image/*,application/pdf,*/*',
    },
  })
  const raw = response.data as Blob
  await throwIfBlobError(raw, 'File download failed')
  const headerType = String(response.headers['content-type'] ?? '')
    .split(';')[0]
    .trim()
  const contentType = raw.type && raw.type !== 'application/octet-stream' ? raw.type : headerType
  const blob =
    contentType && raw.type !== contentType ? new Blob([raw], { type: contentType }) : raw
  const filename = parseContentDispositionFilename(
    response.headers['content-disposition'] as string | undefined,
    fallbackFilename(storageKey),
  )
  return { blob, contentType: blob.type || contentType, filename }
}
