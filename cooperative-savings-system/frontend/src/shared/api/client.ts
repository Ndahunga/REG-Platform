import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { queryClient } from '@/app/queryClient'
import { clearAuth, setCredentials } from '@/app/store/authSlice'
import { store } from '@/app/store/store'
import type { ApiErrorBody, ApiResponse } from '@/shared/types/api'
import type { LoginResponse } from '@/shared/types/auth'

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

export const apiClient = axios.create({
  baseURL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
  withCredentials: true,
})

interface RetryConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

function createRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `req_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

function isLoginUrl(url?: string): boolean {
  return Boolean(
    url?.includes('/auth/login') ||
      url?.includes('/auth/signup') ||
      url?.includes('/onboarding/signup'),
  )
}

function isRefreshUrl(url?: string): boolean {
  return Boolean(url?.includes('/auth/refresh'))
}

/** Shared refresh so parallel 401s wait on a single `/auth/refresh` call. */
let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = apiClient
      .post<ApiResponse<LoginResponse>>('/auth/refresh')
      .then((response) => {
        const body = response.data
        const data = body?.data
        if (!body?.success || !data?.accessToken || !data.user) {
          throw new Error(body?.message || 'Token refresh failed')
        }
        store.dispatch(
          setCredentials({
            user: data.user,
            accessToken: data.accessToken,
          }),
        )
        return data.accessToken
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

const MUTATING_METHODS = new Set(['post', 'put', 'patch', 'delete'])

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const method = (config.method ?? 'get').toLowerCase()
  // Block all financial / mutating API calls while offline — never queue money movement.
  if (
    typeof navigator !== 'undefined' &&
    !navigator.onLine &&
    MUTATING_METHODS.has(method)
  ) {
    return Promise.reject(
      new Error(
        'You are offline. Financial actions require an internet connection.',
      ),
    )
  }

  const token = store.getState().auth.accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  config.headers['X-Request-Id'] = createRequestId()
  return config
})

/** When responseType is blob, error bodies arrive as Blob — parse JSON so callers see the API message. */
async function hydrateBlobErrorBody(error: AxiosError): Promise<void> {
  const data = error.response?.data
  if (typeof Blob === 'undefined' || !(data instanceof Blob)) {
    return
  }
  const text = await data.text()
  if (!text) {
    return
  }
  try {
    error.response!.data = JSON.parse(text)
  } catch {
    error.response!.data = { message: text }
  }
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    await hydrateBlobErrorBody(error)
    const originalRequest = error.config as RetryConfig | undefined
    const status = error.response?.status

    if (isSubscriptionInactiveError(error)) {
      const cooperativeId = subscriptionCooperativeId(error, originalRequest?.url)
      if (cooperativeId) {
        void queryClient.invalidateQueries({
          queryKey: ['cooperatives', cooperativeId, 'subscription'],
        })
      }
      return Promise.reject(error)
    }

    if (status !== 401 || !originalRequest) {
      return Promise.reject(error)
    }

    // Wrong password / login failure — do not clear an existing session.
    if (isLoginUrl(originalRequest.url)) {
      return Promise.reject(error)
    }

    // Refresh itself failed — clear session and reject.
    if (isRefreshUrl(originalRequest.url)) {
      store.dispatch(clearAuth())
      return Promise.reject(error)
    }

    // Already retried once after refresh.
    if (originalRequest._retry) {
      store.dispatch(clearAuth())
      return Promise.reject(error)
    }

    originalRequest._retry = true

    try {
      const accessToken = await refreshAccessToken()
      originalRequest.headers.Authorization = `Bearer ${accessToken}`
      return apiClient(originalRequest)
    } catch (refreshError) {
      store.dispatch(clearAuth())
      return Promise.reject(refreshError)
    }
  },
)

export function isNotFoundError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404
}

export function isForbiddenError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403
}

export function isUnauthorizedError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401
}

export function isSubscriptionInactiveError(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  const data = error.response?.data as ApiErrorBody | undefined
  return error.response?.status === 402 || data?.code === 'SUBSCRIPTION_INACTIVE'
}

function subscriptionCooperativeId(error: AxiosError, url?: string): string | null {
  const data = error.response?.data as ApiErrorBody | undefined
  const details = data?.details as { cooperativeId?: string } | undefined
  if (details?.cooperativeId) return details.cooperativeId
  const match = url?.match(/cooperatives\/([0-9a-fA-F-]{36})/i)
  return match?.[1] ?? null
}

export function getErrorMessage(error: unknown, fallback = 'Something went wrong'): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiErrorBody | undefined
    const fieldMessage = data?.fieldErrors?.find((item) => item.message)?.message
    if (fieldMessage) return fieldMessage
    if (data?.message) return data.message
    if (error.response?.status === 501) {
      return 'This endpoint is not implemented yet.'
    }
    if (error.message) return error.message
  }
  if (error instanceof Error) return error.message
  return fallback
}
