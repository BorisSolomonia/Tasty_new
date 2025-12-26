export type ApiErrorDetails = {
  code?: string
  message?: string
  field?: string
  details?: unknown
}

export type ApiResponse<T> = {
  success: boolean
  message?: string
  data: T
  error?: ApiErrorDetails
  timestamp?: string
}

