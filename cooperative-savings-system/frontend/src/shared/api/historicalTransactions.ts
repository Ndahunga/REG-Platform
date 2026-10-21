import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  HistoricalBatch,
  HistoricalEvent,
  HistoricalRow,
  ModelReport,
} from '@/shared/types/historicalTransactions'
import { parseContentDispositionFilename, throwIfBlobError, triggerBlobDownload } from '@/shared/utils/download'

/**
 * Multi-year historical transaction import. Uploading validates and stores a dry run that posts nothing; a different
 * officer with fund authority approves it, and only then is it posted. The backend is the only source of truth.
 */

const LONG_TIMEOUT_MS = 120_000
const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/historical-transactions`

export async function downloadHistoricalTransactionTemplate(
  cooperativeId: string,
  fallbackFilename = 'historical-transactions-template.xlsx',
): Promise<{ filename: string }> {
  const response = await apiClient.get(`${base(cooperativeId)}/template`, { responseType: 'blob', timeout: LONG_TIMEOUT_MS })
  const blob = response.data as Blob
  await throwIfBlobError(blob, 'Template download failed')
  const filename = parseContentDispositionFilename(response.headers['content-disposition'] as string | undefined, fallbackFilename)
  triggerBlobDownload(blob, filename)
  return { filename }
}

export async function previewHistoricalTransactions(cooperativeId: string, file: File): Promise<HistoricalBatch> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiClient.post<ApiResponse<HistoricalBatch>>(`${base(cooperativeId)}/preview`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: LONG_TIMEOUT_MS,
  })
  return unwrapApiData(response.data)
}

export async function fetchHistoricalTransactionBatches(cooperativeId: string): Promise<HistoricalBatch[]> {
  const response = await apiClient.get<ApiResponse<HistoricalBatch[]>>(base(cooperativeId))
  return unwrapApiData(response.data) ?? []
}

export async function fetchHistoricalTransactionRows(
  cooperativeId: string,
  importId: string,
  onlyErrors: boolean,
): Promise<HistoricalRow[]> {
  const response = await apiClient.get<ApiResponse<HistoricalRow[]>>(`${base(cooperativeId)}/${importId}/rows`, {
    params: { onlyErrors },
  })
  return unwrapApiData(response.data) ?? []
}

export async function fetchHistoricalTransactionEvents(cooperativeId: string, importId: string): Promise<HistoricalEvent[]> {
  const response = await apiClient.get<ApiResponse<HistoricalEvent[]>>(`${base(cooperativeId)}/${importId}/events`)
  return unwrapApiData(response.data) ?? []
}

/** Approves and posts in one transaction. A balance variance must be acknowledged with a written explanation. */
export async function approveHistoricalTransactions(
  cooperativeId: string,
  importId: string,
  body: { acknowledgeVariance: boolean; note?: string },
): Promise<HistoricalBatch> {
  const response = await apiClient.post<ApiResponse<HistoricalBatch>>(`${base(cooperativeId)}/${importId}/approve`, body, {
    timeout: LONG_TIMEOUT_MS,
  })
  return unwrapApiData(response.data)
}

export async function cancelHistoricalTransactions(cooperativeId: string, importId: string, note?: string): Promise<HistoricalBatch> {
  const response = await apiClient.post<ApiResponse<HistoricalBatch>>(`${base(cooperativeId)}/${importId}/cancel`, note ? { note } : {})
  return unwrapApiData(response.data)
}

export async function fetchModelReport(cooperativeId: string, from?: string, to?: string): Promise<ModelReport> {
  const response = await apiClient.get<ApiResponse<ModelReport>>(`/cooperatives/${cooperativeId}/reports/model-report`, {
    params: { ...(from ? { from } : {}), ...(to ? { to } : {}) },
  })
  return unwrapApiData(response.data)
}
