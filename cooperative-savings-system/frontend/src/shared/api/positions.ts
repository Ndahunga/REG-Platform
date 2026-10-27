import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'

export interface MemberPosition {
  memberUserId: string
  memberName: string
  shares: number
  openingCapital: number | string
  contributedCapital: number | string
  sharePurchases: number | string
  shareOutPaid: number | string
  capital: number | string
  incomeShare: number | string
  openingRotationPaid: number | string
  rotatedPaid: number | string
  openingRotationReceived: number | string
  rotationReceived: number | string
  rotationPosition: number | string
  contributionArrears: number | string
  loansOutstanding: number | string
  finesOutstanding: number | string
  owedToMember: number | string
  unclassifiedPaid: number | string
  netPosition: number | string
}

export interface SchemePosition {
  cooperativeId: string
  operatingModel?: string | null
  totalShares: number
  incomePool: number | string
  incomePerShare: number | string
  totalCapital: number | string
  totalRotationPosition: number | string
  totalLoansOutstanding: number | string
  totalFinesOutstanding: number | string
  totalOwedToMembers?: number | string
  availableFunds: number | string
  expectedAssets: number | string
  actualAssets: number | string
  unexplainedDifference: number | string
  members: MemberPosition[]
}

const base = (cooperativeId: string) => `/cooperatives/${cooperativeId}/positions`

export async function fetchSchemePositions(cooperativeId: string): Promise<SchemePosition> {
  const response = await apiClient.get<ApiResponse<SchemePosition>>(base(cooperativeId))
  return unwrapApiData(response.data)
}

export async function fetchMyPosition(cooperativeId: string): Promise<MemberPosition> {
  const response = await apiClient.get<ApiResponse<MemberPosition>>(`${base(cooperativeId)}/me`)
  return unwrapApiData(response.data)
}

export async function downloadPositionsCsv(cooperativeId: string): Promise<void> {
  const response = await apiClient.get<Blob>(`${base(cooperativeId)}/export.csv`, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = 'member-positions.csv'
  link.click()
  URL.revokeObjectURL(url)
}

export interface OpeningWorkbookResult {
  membersUpdated: number
  totalOwedToMembers: number | string
  unallocatedIncome: number | string
  rotationImported: boolean
  rotation?: {
    totalRotatedPaid: number | string
    totalReceived: number | string
    expectedRotationCash: number | string
    declaredRotationCash?: number | string | null
    difference?: number | string | null
    warnings: string[]
  } | null
  warnings: string[]
}

export async function downloadOpeningWorkbookTemplate(cooperativeId: string): Promise<void> {
  const response = await apiClient.get<Blob>(`${base(cooperativeId)}/opening-workbook/template`, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = 'ikimina-rotation-and-positions-template.xlsx'
  link.click()
  URL.revokeObjectURL(url)
}

export async function importOpeningWorkbook(
  cooperativeId: string,
  file: File,
  reason?: string,
): Promise<OpeningWorkbookResult> {
  const form = new FormData()
  form.append('file', file)
  if (reason) form.append('reason', reason)
  const response = await apiClient.post<ApiResponse<OpeningWorkbookResult>>(`${base(cooperativeId)}/opening-workbook`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return unwrapApiData(response.data)
}

/** One upload of the rotation and positions workbook: the current one, or one a correction replaced. */
export interface OpeningWorkbookUpload {
  id: string
  version: number
  status: 'CURRENT' | 'SUPERSEDED' | string
  originalFilename?: string | null
  uploadedBy?: string | null
  uploadedByName?: string | null
  uploadedAt?: string | null
  revisionReason?: string | null
  supersededAt?: string | null
  summary?: string | null
}

export async function fetchOpeningWorkbookHistory(cooperativeId: string): Promise<OpeningWorkbookUpload[]> {
  const response = await apiClient.get<ApiResponse<OpeningWorkbookUpload[]>>(`${base(cooperativeId)}/opening-workbook/history`)
  return unwrapApiData(response.data)
}
