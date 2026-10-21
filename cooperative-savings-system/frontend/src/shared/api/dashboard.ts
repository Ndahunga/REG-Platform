import { apiClient, isNotFoundError } from './client'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type {
  DashboardAdvancedInsights,
  DashboardInsights,
  DashboardMemberInsights,
  DashboardSummary,
  InvestmentsByMonthPoint,
  LoansDisbursedByMonthPoint,
  MonthlyContributionChartPoint,
  PlatformOverview,
} from '@/shared/types/dashboard'
import {
  mapDashboardAdvancedInsights,
  mapDashboardInsights,
  mapDashboardMemberInsights,
  mapDashboardSummary,
  mapInvestmentsByMonthPoint,
  mapLoansDisbursedByMonthPoint,
  mapMonthlyContributionChartPoint,
  mapPlatformOverview,
} from '@/shared/types/dashboard'

export async function fetchDashboardSummary(
  cooperativeId: string,
): Promise<DashboardSummary> {
  const response = await apiClient.get<ApiResponse<DashboardSummary>>(
    `/cooperatives/${cooperativeId}/dashboard/summary`,
  )
  return mapDashboardSummary(unwrapApiData(response.data))
}

export async function fetchDashboardInsights(
  cooperativeId: string,
): Promise<DashboardInsights> {
  const response = await apiClient.get<ApiResponse<DashboardInsights>>(
    `/cooperatives/${cooperativeId}/dashboard/insights`,
  )
  return mapDashboardInsights(unwrapApiData(response.data))
}

export async function fetchDashboardMemberInsights(
  cooperativeId: string,
): Promise<DashboardMemberInsights> {
  const response = await apiClient.get<ApiResponse<DashboardMemberInsights>>(
    `/cooperatives/${cooperativeId}/dashboard/member-insights`,
  )
  return mapDashboardMemberInsights(unwrapApiData(response.data))
}

export async function fetchMonthlyContributionsChart(
  cooperativeId: string,
  year: number,
): Promise<MonthlyContributionChartPoint[]> {
  const response = await apiClient.get<ApiResponse<MonthlyContributionChartPoint[]>>(
    `/cooperatives/${cooperativeId}/dashboard/charts/monthly-contributions`,
    { params: { year } },
  )
  return (unwrapApiData(response.data) ?? []).map(mapMonthlyContributionChartPoint)
}

export async function fetchLoansDisbursedByMonthChart(
  cooperativeId: string,
  year: number,
): Promise<LoansDisbursedByMonthPoint[]> {
  const response = await apiClient.get<ApiResponse<LoansDisbursedByMonthPoint[]>>(
    `/cooperatives/${cooperativeId}/dashboard/charts/loans-disbursed-by-month`,
    { params: { year } },
  )
  return (unwrapApiData(response.data) ?? []).map(mapLoansDisbursedByMonthPoint)
}

export async function fetchInvestmentsByMonthChart(
  cooperativeId: string,
  year: number,
): Promise<InvestmentsByMonthPoint[]> {
  const response = await apiClient.get<ApiResponse<InvestmentsByMonthPoint[]>>(
    `/cooperatives/${cooperativeId}/dashboard/charts/investments-by-month`,
    { params: { year } },
  )
  return (unwrapApiData(response.data) ?? []).map(mapInvestmentsByMonthPoint)
}

export async function fetchDashboardAdvancedInsights(
  cooperativeId: string,
): Promise<DashboardAdvancedInsights> {
  const response = await apiClient.get<ApiResponse<DashboardAdvancedInsights>>(
    `/cooperatives/${cooperativeId}/dashboard/advanced-insights`,
  )
  return mapDashboardAdvancedInsights(unwrapApiData(response.data))
}

export async function fetchPlatformOverview(): Promise<PlatformOverview> {
  const response = await apiClient.get<ApiResponse<PlatformOverview>>(
    '/platform/dashboard/overview',
  )
  return mapPlatformOverview(unwrapApiData(response.data))
}

/** `null` means the running backend does not expose the platform overview route yet. */
export async function fetchPlatformOverviewIfAvailable(): Promise<PlatformOverview | null> {
  try {
    return await fetchPlatformOverview()
  } catch (error) {
    if (isNotFoundError(error)) return null
    throw error
  }
}
