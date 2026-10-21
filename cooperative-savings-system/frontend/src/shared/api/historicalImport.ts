import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  HistoricalImportConfirm,
  HistoricalImportPreview,
  HistoricalImportSummary,
} from '@/shared/types/historicalImport'
import {
  parseContentDispositionFilename,
  throwIfBlobError,
  triggerBlobDownload,
} from '@/shared/utils/download'

const LONG_TIMEOUT_MS = 120_000

export async function downloadHistoricalImportTemplate(
  cooperativeId: string,
  fallbackFilename = 'ikimina-migration-template.xlsx',
): Promise<{ filename: string }> {
  const response = await apiClient.get(
    `/cooperatives/${cooperativeId}/historical-imports/template`,
    { responseType: 'blob', timeout: LONG_TIMEOUT_MS },
  )
  const blob = response.data as Blob
  await throwIfBlobError(blob, 'Template download failed')
  const filename = parseContentDispositionFilename(
    response.headers['content-disposition'] as string | undefined,
    fallbackFilename,
  )
  triggerBlobDownload(blob, filename)
  return { filename }
}

/** The separate one-sheet template for loans still outstanding at the cutover. */
export async function downloadHistoricalImportLoanTemplate(
  cooperativeId: string,
  fallbackFilename = 'ikimina-opening-loans-template.xlsx',
): Promise<{ filename: string }> {
  const response = await apiClient.get(
    `/cooperatives/${cooperativeId}/historical-imports/loan-template`,
    { responseType: 'blob', timeout: LONG_TIMEOUT_MS },
  )
  const blob = response.data as Blob
  await throwIfBlobError(blob, 'Template download failed')
  const filename = parseContentDispositionFilename(
    response.headers['content-disposition'] as string | undefined,
    fallbackFilename,
  )
  triggerBlobDownload(blob, filename)
  return { filename }
}

export async function previewHistoricalImport(
  cooperativeId: string,
  file: File,
  cutoverDate: string,
  openingAvailableFunds: string,
): Promise<HistoricalImportPreview> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('cutoverDate', cutoverDate)
  // The loan workbook carries no cash position: send it only with the opening balances.
  if (openingAvailableFunds) formData.append('openingAvailableFunds', openingAvailableFunds)
  const response = await apiClient.post<ApiResponse<HistoricalImportPreview>>(
    `/cooperatives/${cooperativeId}/historical-imports/preview`,
    formData,
    {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: LONG_TIMEOUT_MS,
    },
  )
  return unwrapApiData(response.data)
}

export async function confirmHistoricalImport(
  cooperativeId: string,
  importId: string,
): Promise<HistoricalImportConfirm> {
  const response = await apiClient.post<ApiResponse<HistoricalImportConfirm>>(
    `/cooperatives/${cooperativeId}/historical-imports/${importId}/confirm`,
    undefined,
    { timeout: LONG_TIMEOUT_MS },
  )
  return unwrapApiData(response.data)
}

export async function cancelHistoricalImport(
  cooperativeId: string,
  importId: string,
): Promise<HistoricalImportSummary> {
  const response = await apiClient.post<ApiResponse<HistoricalImportSummary>>(
    `/cooperatives/${cooperativeId}/historical-imports/${importId}/cancel`,
  )
  return unwrapApiData(response.data)
}

export async function fetchHistoricalImport(
  cooperativeId: string,
  importId: string,
): Promise<HistoricalImportPreview> {
  const response = await apiClient.get<ApiResponse<HistoricalImportPreview>>(
    `/cooperatives/${cooperativeId}/historical-imports/${importId}`,
  )
  return unwrapApiData(response.data)
}

export async function fetchHistoricalImportHistory(
  cooperativeId: string,
): Promise<HistoricalImportSummary[]> {
  const response = await apiClient.get<ApiResponse<HistoricalImportSummary[]>>(
    `/cooperatives/${cooperativeId}/historical-imports`,
  )
  return unwrapApiData(response.data) ?? []
}
