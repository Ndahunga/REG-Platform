import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import {
  mapContributionImportPreview,
  mapContributionImportSummary,
  type ContributionImportPreview,
  type ContributionImportSummary,
} from '@/shared/types/contributionImport'
import {
  parseContentDispositionFilename,
  throwIfBlobError,
  triggerBlobDownload,
} from '@/shared/utils/download'

export async function downloadContributionImportTemplate(
  cooperativeId: string,
  fallbackFilename = 'contribution-import-template.xlsx',
): Promise<{ filename: string }> {
  const response = await apiClient.get(
    `/cooperatives/${cooperativeId}/contributions/import/template`,
    { responseType: 'blob' },
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

export async function previewContributionImport(
  cooperativeId: string,
  file: File,
  year: number,
  month: number,
): Promise<ContributionImportPreview> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('year', String(year))
  formData.append('month', String(month))
  const response = await apiClient.post<ApiResponse<ContributionImportPreview>>(
    `/cooperatives/${cooperativeId}/contributions/import/preview`,
    formData,
    {
      headers: { 'Content-Type': 'multipart/form-data' },
      params: { year, month },
    },
  )
  return mapContributionImportPreview(unwrapApiData(response.data))
}

export async function confirmContributionImport(
  cooperativeId: string,
  importId: string,
): Promise<ContributionImportSummary> {
  const response = await apiClient.post<ApiResponse<ContributionImportSummary>>(
    `/cooperatives/${cooperativeId}/contributions/import/${importId}/confirm`,
  )
  return mapContributionImportSummary(unwrapApiData(response.data))
}

export async function cancelContributionImport(
  cooperativeId: string,
  importId: string,
): Promise<ContributionImportSummary> {
  const response = await apiClient.post<ApiResponse<ContributionImportSummary>>(
    `/cooperatives/${cooperativeId}/contributions/import/${importId}/cancel`,
  )
  return mapContributionImportSummary(unwrapApiData(response.data))
}

export async function fetchContributionImport(
  cooperativeId: string,
  importId: string,
): Promise<ContributionImportSummary> {
  const response = await apiClient.get<ApiResponse<ContributionImportSummary>>(
    `/cooperatives/${cooperativeId}/contributions/import/${importId}`,
  )
  return mapContributionImportSummary(unwrapApiData(response.data))
}

export async function fetchContributionImportHistory(
  cooperativeId: string,
  page = 0,
  size = 20,
): Promise<PageResponse<ContributionImportSummary>> {
  const response = await apiClient.get<
    ApiResponse<PageResponse<ContributionImportSummary> | ContributionImportSummary[]>
  >(`/cooperatives/${cooperativeId}/contributions/import/history`, {
    params: { page, size, sort: 'createdAt,desc' },
  })
  const data = unwrapApiData(response.data)
  if (Array.isArray(data)) {
    const content = data.map(mapContributionImportSummary)
    return {
      content,
      page,
      size,
      totalElements: content.length,
      totalPages: 1,
      first: true,
      last: true,
    }
  }
  return {
    ...data,
    content: (data.content ?? []).map(mapContributionImportSummary),
  }
}
