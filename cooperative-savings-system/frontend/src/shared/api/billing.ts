import axios from 'axios'
import { apiClient } from './client'
import { unwrapApiData } from './auth'
import type { ApiErrorBody, ApiResponse, PageQuery, PageResponse } from '@/shared/types/api'
import type {
  BillingCheckoutRequest,
  BillingCheckoutResponse,
  BillingPlanQuote,
  BillingPlansResponse,
  SubscriptionPaymentRecord,
} from '@/shared/types/billing'
import { mapBillingPlan, mapSubscriptionPayment } from '@/shared/types/billing'

export const billingPlansQueryKey = (cooperativeId: string) =>
  ['cooperatives', cooperativeId, 'billing', 'plans'] as const

export const billingPaymentsQueryKey = (cooperativeId: string, query: PageQuery = {}) =>
  ['cooperatives', cooperativeId, 'billing', 'payments', query] as const

export const billingPaymentQueryKey = (cooperativeId: string, paymentId: string) =>
  ['cooperatives', cooperativeId, 'billing', 'payments', paymentId] as const

export async function fetchBillingPlans(cooperativeId: string): Promise<BillingPlansResponse> {
  const response = await apiClient.get<ApiResponse<BillingPlansResponse>>(
    `/cooperatives/${cooperativeId}/billing/plans`,
  )
  const data = unwrapApiData(response.data)
  return {
    currency: data.currency || 'RWF',
    trialMonths: data.trialMonths,
    mtnCheckoutAvailable: Boolean(data.mtnCheckoutAvailable),
    cardCheckoutAvailable: Boolean(data.cardCheckoutAvailable),
    plans: (data.plans ?? []).map(mapBillingPlan),
  }
}

export async function fetchSubscriptionPayments(
  cooperativeId: string,
  query: PageQuery = {},
): Promise<PageResponse<SubscriptionPaymentRecord>> {
  const params: Record<string, string | number> = {}
  if (query.page != null) params.page = query.page
  if (query.size != null) params.size = query.size
  if (query.sort) params.sort = query.sort
  const response = await apiClient.get<ApiResponse<PageResponse<SubscriptionPaymentRecord>>>(
    `/cooperatives/${cooperativeId}/billing/payments`,
    { params },
  )
  const page = unwrapApiData(response.data)
  return {
    ...page,
    content: (page.content ?? []).map(mapSubscriptionPayment),
  }
}

export async function fetchSubscriptionPayment(
  cooperativeId: string,
  paymentId: string,
): Promise<SubscriptionPaymentRecord> {
  const response = await apiClient.get<ApiResponse<SubscriptionPaymentRecord>>(
    `/cooperatives/${cooperativeId}/billing/payments/${paymentId}`,
  )
  return mapSubscriptionPayment(unwrapApiData(response.data))
}

/**
 * Starts provider checkout. Amount is never sent; the server prices the plan.
 * Initiated payments stay PENDING until the provider confirms success.
 */
export async function startBillingCheckout(
  cooperativeId: string,
  payload: BillingCheckoutRequest,
): Promise<BillingCheckoutResponse> {
  const response = await apiClient.post<ApiResponse<BillingCheckoutResponse>>(
    `/cooperatives/${cooperativeId}/billing/checkout`,
    payload,
  )
  return unwrapApiData(response.data)
}

export function isPaymentIntegrationUnavailableError(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  const data = error.response?.data as ApiErrorBody | undefined
  return error.response?.status === 501 || data?.code === 'PAYMENT_INTEGRATION_UNAVAILABLE'
}

export function planByCycle(
  plans: BillingPlanQuote[] | undefined,
  cycle: BillingPlanQuote['billingCycle'],
): BillingPlanQuote | undefined {
  return plans?.find((plan) => plan.billingCycle === cycle)
}

export const BILLING_RETURN_STORAGE_KEY = 'ouwealth.billing.return'
const TX_REF_PREFIX = 'ouwealth-sub-'
const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export function storeBillingReturnContext(cooperativeId: string, paymentId: string): void {
  sessionStorage.setItem(BILLING_RETURN_STORAGE_KEY, JSON.stringify({ cooperativeId, paymentId }))
}

export function readBillingReturnContext(): { cooperativeId?: string; paymentId?: string } {
  try {
    const raw = sessionStorage.getItem(BILLING_RETURN_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as { cooperativeId?: string; paymentId?: string }
    return {
      cooperativeId: typeof parsed.cooperativeId === 'string' ? parsed.cooperativeId : undefined,
      paymentId: typeof parsed.paymentId === 'string' ? parsed.paymentId : undefined,
    }
  } catch {
    return {}
  }
}

export function clearBillingReturnContext(): void {
  sessionStorage.removeItem(BILLING_RETURN_STORAGE_KEY)
}

/** Untrusted hint from Flutterwave tx_ref. Backend verification is still required. */
export function paymentIdFromTxRef(txRef: string | null | undefined): string | null {
  if (!txRef?.startsWith(TX_REF_PREFIX)) return null
  const id = txRef.slice(TX_REF_PREFIX.length)
  return UUID_RE.test(id) ? id : null
}
