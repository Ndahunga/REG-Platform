import { apiClient } from './client'
import { unwrapApiData } from './auth'
import {
  parseContentDispositionFilename,
  throwIfBlobError,
  triggerBlobDownload,
} from '@/shared/utils/download'
import type { ReportWhatsAppShareResult, ReportWhatsAppStatus } from '@/shared/types/report'
import type { ApiResponse, PageResponse } from '@/shared/types/api'
import type {
  Loan,
  LoanApplicationForm,
  LoanApproveRequest,
  LoanCreateRequest,
  LoanEligibility,
  LoanGuarantor,
  LoanListQuery,
  LoanRejectRequest,
  LoanRepayment,
  LoanRepaymentCreateRequest,
  LoanRepaymentPreviewRequest,
  LoanSchedulePreview,
} from '@/shared/types/loan'
import { mapLoan, mapLoanRepayment } from '@/shared/types/loan'

function toListParams(query: LoanListQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.q?.trim()) params.q = query.q.trim()
  if (query.status) params.status = query.status
  if (query.memberUserId) params.memberUserId = query.memberUserId
  if (query.pendingApproval) params.pendingApproval = 'true'
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  return params
}

export async function fetchLoans(
  cooperativeId: string,
  query: LoanListQuery = {},
): Promise<PageResponse<Loan>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Loan>>>(
    `/cooperatives/${cooperativeId}/loans`,
    { params: toListParams(query) },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapLoan),
  }
}

export async function fetchMyLoans(
  cooperativeId: string,
  query: LoanListQuery = {},
): Promise<PageResponse<Loan>> {
  const response = await apiClient.get<ApiResponse<PageResponse<Loan> | Loan[]>>(
    `/cooperatives/${cooperativeId}/loans/my`,
    { params: toListParams(query) },
  )
  const data = unwrapApiData(response.data)
  if (Array.isArray(data)) {
    return {
      content: data.map(mapLoan),
      page: 0,
      size: data.length,
      totalElements: data.length,
      totalPages: 1,
      first: true,
      last: true,
    }
  }
  return {
    ...data,
    content: (data.content ?? []).map(mapLoan),
  }
}

export async function fetchLoanApplicationPreview(
  cooperativeId: string,
): Promise<LoanApplicationForm> {
  const response = await apiClient.get<ApiResponse<LoanApplicationForm>>(
    `/cooperatives/${cooperativeId}/loans/application-preview`,
  )
  return unwrapApiData(response.data)
}

export async function fetchLoanEligibility(
  cooperativeId: string,
  memberUserId?: string,
  amount?: string | number,
): Promise<LoanEligibility> {
  const params: Record<string, string | number> = {}
  if (memberUserId) params.memberUserId = memberUserId
  if (amount != null && String(amount).trim() !== '') params.amount = String(amount)
  const response = await apiClient.get<ApiResponse<LoanEligibility>>(
    `/cooperatives/${cooperativeId}/loans/eligibility`,
    { params },
  )
  return unwrapApiData(response.data)
}

export async function fetchGuarantorRequests(
  cooperativeId: string,
): Promise<LoanGuarantor[]> {
  const response = await apiClient.get<ApiResponse<LoanGuarantor[]>>(
    `/cooperatives/${cooperativeId}/loans/guarantor-requests`,
  )
  return unwrapApiData(response.data) ?? []
}

export async function respondToGuarantorRequest(
  cooperativeId: string,
  loanId: string,
  accepted: boolean,
  comment?: string,
): Promise<LoanGuarantor> {
  const response = await apiClient.post<ApiResponse<LoanGuarantor>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/guarantor/respond`,
    { accepted, comment },
  )
  return unwrapApiData(response.data)
}

export async function fetchLoan(cooperativeId: string, loanId: string): Promise<Loan> {
  const response = await apiClient.get<ApiResponse<Loan>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}`,
  )
  return mapLoan(unwrapApiData(response.data))
}

export async function previewLoanRepayment(
  cooperativeId: string,
  payload: LoanRepaymentPreviewRequest,
): Promise<LoanSchedulePreview> {
  const response = await apiClient.post<ApiResponse<LoanSchedulePreview>>(
    `/cooperatives/${cooperativeId}/loans/repayment-preview`,
    payload,
  )
  return unwrapApiData(response.data)
}

export async function createLoan(
  cooperativeId: string,
  payload: LoanCreateRequest,
): Promise<Loan> {
  const response = await apiClient.post<ApiResponse<Loan>>(
    `/cooperatives/${cooperativeId}/loans`,
    payload,
  )
  return mapLoan(unwrapApiData(response.data))
}

export async function approveLoan(
  cooperativeId: string,
  loanId: string,
  payload: LoanApproveRequest = {},
): Promise<Loan> {
  const response = await apiClient.post<ApiResponse<Loan>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/approve`,
    payload,
  )
  return mapLoan(unwrapApiData(response.data))
}

export async function rejectLoan(
  cooperativeId: string,
  loanId: string,
  payload: LoanRejectRequest,
): Promise<Loan> {
  const response = await apiClient.post<ApiResponse<Loan>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/reject`,
    payload,
  )
  return mapLoan(unwrapApiData(response.data))
}

export async function disburseLoan(cooperativeId: string, loanId: string): Promise<Loan> {
  const response = await apiClient.post<ApiResponse<Loan>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/disburse`,
  )
  return mapLoan(unwrapApiData(response.data))
}

export async function writeOffLoan(cooperativeId: string, loanId: string): Promise<Loan> {
  const response = await apiClient.post<ApiResponse<Loan>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/write-off`,
  )
  return mapLoan(unwrapApiData(response.data))
}

export async function fetchLoanRepayments(
  cooperativeId: string,
  loanId: string,
): Promise<LoanRepayment[]> {
  const response = await apiClient.get<
    ApiResponse<LoanRepayment[] | PageResponse<LoanRepayment>>
  >(`/cooperatives/${cooperativeId}/loans/${loanId}/repayments`)
  const data = unwrapApiData(response.data)
  const list = Array.isArray(data) ? data : (data.content ?? [])
  return list.map(mapLoanRepayment)
}

export async function createLoanRepayment(
  cooperativeId: string,
  loanId: string,
  payload: LoanRepaymentCreateRequest,
): Promise<LoanRepayment> {
  const response = await apiClient.post<ApiResponse<LoanRepayment>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/repayments`,
    payload,
  )
  return mapLoanRepayment(unwrapApiData(response.data))
}

export async function exportLoanSchedule(
  cooperativeId: string,
  loanId: string,
  format: 'pdf' | 'xlsx',
): Promise<{ filename: string }> {
  const response = await apiClient.get(
    `/cooperatives/${cooperativeId}/loans/${loanId}/schedule/export`,
    {
      params: { format },
      responseType: 'blob',
      timeout: 120000,
      headers:
        format === 'pdf'
          ? {
              Accept: 'application/pdf, application/json',
            }
          : undefined,
    },
  )
  const blob = response.data as Blob
  await throwIfBlobError(blob, 'Schedule export failed')
  const fallback = format === 'xlsx' ? 'repayment-schedule.xlsx' : 'repayment-schedule.pdf'
  const filename = parseContentDispositionFilename(
    response.headers['content-disposition'] as string | undefined,
    fallback,
  )
  const typed =
    format === 'xlsx'
      ? new Blob([blob], {
          type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        })
      : blob.type === 'application/pdf'
        ? blob
        : new Blob([blob], { type: 'application/pdf' })
  triggerBlobDownload(typed, filename)
  return { filename }
}

export async function fetchLoanScheduleWhatsAppStatus(
  cooperativeId: string,
  loanId: string,
): Promise<ReportWhatsAppStatus> {
  const response = await apiClient.get<ApiResponse<ReportWhatsAppStatus>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/schedule/whatsapp-status`,
  )
  const data = unwrapApiData(response.data)
  return { configured: Boolean(data?.configured) }
}

export async function shareLoanScheduleViaWhatsApp(
  cooperativeId: string,
  loanId: string,
  recipientPhone: string,
): Promise<ReportWhatsAppShareResult> {
  const response = await apiClient.post<ApiResponse<ReportWhatsAppShareResult>>(
    `/cooperatives/${cooperativeId}/loans/${loanId}/schedule/share-whatsapp`,
    { recipientPhone },
    { timeout: 120000 },
  )
  return unwrapApiData(response.data)
}
