import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import {
  mapReportTypeInfo,
  type ReportExportRequest,
  type ReportTypeInfo,
  type ReportWhatsAppShareRequest,
  type ReportWhatsAppShareResult,
  type ReportWhatsAppStatus,
} from '@/shared/types/report'
import {
  parseContentDispositionFilename,
  throwIfBlobError,
  triggerBlobDownload,
} from '@/shared/utils/download'

function toExportBody(payload: ReportExportRequest): Record<string, unknown> {
  const body: Record<string, unknown> = {
    reportType: payload.reportType,
  }
  if (payload.fromDate) body.fromDate = payload.fromDate
  if (payload.toDate) body.toDate = payload.toDate
  if (payload.memberUserId) body.memberUserId = payload.memberUserId
  if (payload.status) body.status = payload.status
  if (payload.transactionType) body.transactionType = payload.transactionType
  return body
}

export async function fetchReportTypes(cooperativeId: string): Promise<ReportTypeInfo[]> {
  const response = await apiClient.get<
    ApiResponse<ReportTypeInfo[] | { types?: ReportTypeInfo[]; content?: ReportTypeInfo[] } | string[]>
  >(`/cooperatives/${cooperativeId}/reports/types`)
  const data = unwrapApiData(response.data)
  const list = Array.isArray(data)
    ? data
    : (data.types ?? data.content ?? [])
  return list.map(mapReportTypeInfo)
}

export async function exportReport(
  cooperativeId: string,
  payload: ReportExportRequest,
  fallbackFilename = 'report.pdf',
): Promise<{ filename: string }> {
  const response = await apiClient.post(
    `/cooperatives/${cooperativeId}/reports/export`,
    toExportBody(payload),
    {
      responseType: 'blob',
      timeout: 120000,
      headers: {
        Accept: 'application/pdf, application/json',
      },
    },
  )
  const blob = response.data as Blob
  await throwIfBlobError(blob, 'Report export failed')
  const filename = parseContentDispositionFilename(
    response.headers['content-disposition'] as string | undefined,
    fallbackFilename,
  )
  const pdfBlob =
    blob.type === 'application/pdf' ? blob : new Blob([blob], { type: 'application/pdf' })
  triggerBlobDownload(pdfBlob, filename.endsWith('.pdf') ? filename : `${filename}.pdf`)
  return { filename }
}

export async function fetchWhatsAppStatus(cooperativeId: string): Promise<ReportWhatsAppStatus> {
  const response = await apiClient.get<ApiResponse<ReportWhatsAppStatus>>(
    `/cooperatives/${cooperativeId}/reports/whatsapp-status`,
  )
  const data = unwrapApiData(response.data)
  return { configured: Boolean(data?.configured) }
}

export async function shareReportViaWhatsApp(
  cooperativeId: string,
  payload: ReportWhatsAppShareRequest,
): Promise<ReportWhatsAppShareResult> {
  const response = await apiClient.post<ApiResponse<ReportWhatsAppShareResult>>(
    `/cooperatives/${cooperativeId}/reports/share-whatsapp`,
    {
      ...toExportBody(payload),
      recipientPhone: payload.recipientPhone,
    },
    { timeout: 120000 },
  )
  return unwrapApiData(response.data)
}
