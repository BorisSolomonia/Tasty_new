import type { Payment, Waybill, WaybillVatSummary } from '@/types/domain'

const API_URL = import.meta.env.VITE_API_URL || '/api'

type WaybillFetchResponse = {
  success: boolean
  message?: string
  totalCount: number
  afterCutoffCount: number
  waybills: Waybill[]
}

interface FetchOptions extends RequestInit {
  params?: Record<string, string | number | boolean | undefined>
}

type ApiResponse<T> = {
  success: boolean
  message?: string
  data: T
  error?: { code?: string; message?: string; field?: string; details?: unknown }
  timestamp?: string
}

class ApiError extends Error {
  constructor(
    public status: number,
    public statusText: string,
    public data?: unknown
  ) {
    super(`API Error: ${status} ${statusText}`)
    this.name = 'ApiError'
  }
}

async function fetchWithAuth(endpoint: string, options: FetchOptions = {}): Promise<Response> {
  const { params, ...fetchOptions } = options

  let url = `${API_URL}${endpoint}`

  if (params) {
    const searchParams = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined) {
        searchParams.append(key, String(value))
      }
    })
    const queryString = searchParams.toString()
    if (queryString) {
      url += `?${queryString}`
    }
  }

  const response = await fetch(url, {
    ...fetchOptions,
    headers: {
      'Content-Type': 'application/json',
      ...fetchOptions.headers,
    },
  })

  if (!response.ok) {
    const data = await response.json().catch(() => null)
    throw new ApiError(response.status, response.statusText, data)
  }

  return response
}

async function jsonData<T>(response: Response): Promise<T> {
  const body = (await response.json()) as ApiResponse<T> | T
  if (body && typeof body === 'object' && 'success' in body && 'data' in body) {
    const wrapped = body as ApiResponse<T>
    if (wrapped.success === false) {
      throw new ApiError(400, wrapped.message ?? 'Request failed', wrapped.error ?? wrapped)
    }
    return wrapped.data
  }
  return body as T
}

// Waybills API
export const waybillsApi = {
  getAll: async (params?: {
    customerId?: string
    startDate?: string
    endDate?: string
    afterCutoffOnly?: boolean
    type?: 'SALE' | 'PURCHASE'
  }) => {
    const response = await fetchWithAuth('/waybills', {
      params,
    })
    return jsonData<Waybill[]>(response)
  },

  fetch: async (startDate: string, endDate: string) => {
    const response = await fetchWithAuth('/waybills/fetch', {
      method: 'POST',
      body: JSON.stringify({ startDate, endDate }),
    })
    return jsonData<WaybillFetchResponse>(response)
  },

  fetchPurchases: async (startDate: string, endDate: string) => {
    const response = await fetchWithAuth('/waybills/purchase/fetch', {
      method: 'POST',
      body: JSON.stringify({ startDate, endDate }),
    })
    return jsonData<WaybillFetchResponse>(response)
  },

  getById: async (id: string) => {
    const response = await fetchWithAuth(`/waybills/${id}`)
    return jsonData<Waybill>(response)
  },

  getByCustomer: async (customerId: string, afterCutoffOnly: boolean = true) => {
    const response = await fetchWithAuth(`/waybills/customer/${customerId}`, {
      params: { afterCutoffOnly },
    })
    return jsonData<Waybill[]>(response)
  },

  getStats: async () => {
    const response = await fetchWithAuth('/waybills/stats')
    return jsonData<Record<string, unknown>>(response)
  },

  getVatSummary: async (params: { startDate: string; endDate: string; afterCutoffOnly?: boolean }) => {
    const response = await fetchWithAuth('/waybills/vat', { params })
    return jsonData<WaybillVatSummary>(response)
  },
}

// Payments API
export const paymentsApi = {
  getAll: async (startDate?: string, endDate?: string, customerId?: string, source?: string) => {
    const response = await fetchWithAuth('/payments', {
      params: { startDate, endDate, customerId, source },
    })
    return jsonData<Payment[]>(response)
  },

  uploadExcel: async (file: File, bank: 'tbc' | 'bog') => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('bank', bank)

    const response = await fetch(`${API_URL}/payments/excel/upload`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      const data = await response.json().catch(() => null)
      throw new ApiError(response.status, response.statusText, data)
    }

    return jsonData<Record<string, unknown>>(response)
  },

  validateExcel: async (file: File, bank: 'tbc' | 'bog') => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('bank', bank)

    const response = await fetch(`${API_URL}/payments/excel/validate`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      const data = await response.json().catch(() => null)
      throw new ApiError(response.status, response.statusText, data)
    }

    return jsonData<Record<string, unknown>>(response)
  },

  getByCustomer: async (customerId: string) => {
    const response = await fetchWithAuth(`/payments/customer/${customerId}`)
    return jsonData<Record<string, unknown>>(response)
  },

  getCustomerPayments: async (customerId: string) => {
    const response = await fetchWithAuth(`/payments/customer/${customerId}/payments`)
    return jsonData<Payment[]>(response)
  },

  addManualPayment: async (payment: { customerId: string; amount: number; paymentDate: string; description?: string }) => {
    const response = await fetchWithAuth('/payments/manual', {
      method: 'POST',
      body: JSON.stringify(payment),
    })
    return jsonData<Payment>(response)
  },

  deletePayment: async (id: string) => {
    await fetchWithAuth(`/payments/${id}`, { method: 'DELETE' })
  },

  getStats: async () => {
    const response = await fetchWithAuth('/payments/stats')
    return jsonData<Record<string, unknown>>(response)
  },

  // Customer Analysis
  getCustomerAnalysis: async () => {
    const response = await fetchWithAuth('/payments/analysis')
    return jsonData<Array<import('@/types/domain').CustomerAnalysis>>(response)
  },

  getCustomerAnalysisById: async (customerId: string) => {
    const response = await fetchWithAuth(`/payments/analysis/${customerId}`)
    return jsonData<import('@/types/domain').CustomerAnalysis>(response)
  },

  // Manual Cash Payments
  getAllManualCashPayments: async () => {
    const response = await fetchWithAuth('/payments/manual')
    return jsonData<Payment[]>(response)
  },

  getManualCashPaymentsByCustomer: async (customerId: string) => {
    const response = await fetchWithAuth(`/payments/manual/customer/${customerId}`)
    return jsonData<Payment[]>(response)
  },

  addManualCashPayment: async (payment: { customerId: string; amount: number; paymentDate: string; description?: string; customerName?: string }) => {
    const response = await fetchWithAuth('/payments/manual', {
      method: 'POST',
      body: JSON.stringify(payment),
    })
    return jsonData<Payment>(response)
  },

  uploadManualExcel: async (file: File) => {
    const formData = new FormData()
    formData.append('file', file)

    const response = await fetch(`${API_URL}/payments/manual/excel/upload`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      const data = await response.json().catch(() => null)
      throw new ApiError(response.status, response.statusText, data)
    }

    return jsonData<Record<string, unknown>>(response)
  },

  deleteManualCashPayment: async (id: string) => {
    await fetchWithAuth(`/payments/manual/${id}`, { method: 'DELETE' })
  },

  // Aggregation Job Status
  getAggregationJobStatus: async (jobId: string) => {
    const response = await fetchWithAuth(`/payments/aggregation/job/${jobId}`)
    return jsonData<import('@/types/domain').AggregationJob>(response)
  },

  // Delete all bank payments (TBC/BOG)
  deleteBankPayments: async () => {
    const response = await fetchWithAuth('/payments/bank', { method: 'DELETE' })
    return jsonData<{ deleted: number; aggregationJobId?: string }>(response)
  },

  // Deduplication
  analyzeDuplicates: async () => {
    const response = await fetchWithAuth('/payments/deduplicate/analyze')
    return jsonData<{
      totalPayments: number
      duplicateGroups: number
      paymentsDeleted: number
      amountRecovered: number
    }>(response)
  },

  removeDuplicates: async () => {
    const response = await fetchWithAuth('/payments/deduplicate/remove', { method: 'POST' })
    return jsonData<{
      totalPayments: number
      duplicateGroups: number
      paymentsDeleted: number
      amountRecovered: number
    }>(response)
  },
}

// Config API
export const configApi = {
  // Settings
  getSettings: async () => {
    const response = await fetchWithAuth('/config/settings')
    return jsonData(response)
  },

  getSetting: async (key: string) => {
    const response = await fetchWithAuth(`/config/settings/${key}`)
    return jsonData(response)
  },

  updateSetting: async (key: string, value: unknown) => {
    const response = await fetchWithAuth(`/config/settings/${key}`, {
      method: 'PUT',
      body: JSON.stringify(value),
    })
    return jsonData(response)
  },

  // Initial Debts
  getInitialDebts: async () => {
    const response = await fetchWithAuth('/config/debts')
    return jsonData(response)
  },

  getInitialDebt: async (customerId: string) => {
    const response = await fetchWithAuth(`/config/debts/${customerId}`)
    return jsonData(response)
  },

  addInitialDebt: async (data: { customerId: string; name: string; debt: number; date: string }) => {
    const response = await fetchWithAuth('/config/debts', {
      method: 'POST',
      body: JSON.stringify(data),
    })
    return jsonData(response)
  },

  updateInitialDebt: async (customerId: string, data: { name: string; debt: number; date: string }) => {
    const response = await fetchWithAuth(`/config/debts/${customerId}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
    return jsonData(response)
  },

  deleteInitialDebt: async (customerId: string) => {
    await fetchWithAuth(`/config/debts/${customerId}`, {
      method: 'DELETE',
    })
  },

  bulkImportDebts: async (debts: Array<{ customerId: string; name: string; debt: number; date: string }>) => {
    const response = await fetchWithAuth('/config/debts/bulk', {
      method: 'POST',
      body: JSON.stringify(debts),
    })
    return jsonData(response)
  },

  // Customers
  getCustomers: async () => {
    const response = await fetchWithAuth('/config/customers')
    return jsonData<Array<{ identification: string; customerName: string; contactInfo?: string }>>(response)
  },

  getCustomer: async (identification: string) => {
    const response = await fetchWithAuth(`/config/customers/${identification}`)
    return jsonData<{ identification: string; customerName: string; contactInfo?: string }>(response)
  },
}

export { ApiError }
