export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
  timestamp: string
  path?: string
}

export interface ApiErrorBody {
  success?: boolean
  message?: string
  code?: string
  details?: unknown
  path?: string
  timestamp?: string
  fieldErrors?: Array<{ field?: string; message?: string }>
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface PageQuery {
  q?: string
  status?: string
  page?: number
  size?: number
  sort?: string
}

export interface HealthResponse {
  status: string
  service?: string
  version?: string
  timestamp?: string
}
