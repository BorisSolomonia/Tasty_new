/**
 * BOR-82 findings FE-1 / FE-2 / FE-8 regressions for the audit data hooks.
 */
import * as React from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/audit-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/audit-api')>('@/lib/audit-api')
  return {
    ...actual,
    auditLayerApi: {
      ...actual.auditLayerApi,
      saveMapping: vi.fn(),
      createCategory: vi.fn(),
      saveRealInventory: vi.fn(),
    },
  }
})

import { auditLayerApi, type AuditMapping } from '@/lib/audit-api'
import {
  AUDIT_LAYER_KEY,
  MAPPING_SCOPES,
  useCreateCategory,
  useInvalidateAuditLayer,
  useSaveMapping,
  useSaveRealInventory,
} from './use-audit-flows'

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}

/** The distinct `queryKey` prefixes an invalidateQueries spy was called with. */
function invalidatedScopes(spy: { mock: { calls: unknown[][] } }): string[] {
  return spy.mock.calls
    .map((call) => (call[0] as { queryKey?: unknown[] } | undefined)?.queryKey ?? [])
    .map((key) => key.slice(0, 2).join('/'))
}

const savedMapping: AuditMapping = {
  id: 'BANK__row-1',
  sourceType: 'BANK',
  sourceRowId: 'row-1',
  sourceAmount: 100,
  status: 'MANUALLY_MAPPED',
  splits: [],
  linkedSourceRows: [],
  unresolvedAmount: 0,
} as unknown as AuditMapping

describe('useInvalidateAuditLayer', () => {
  it('returns the same function across re-renders (FE-1: context memo can hit)', () => {
    const client = newClient()
    const { result, rerender } = renderHook(() => useInvalidateAuditLayer(), { wrapper: wrapperFor(client) })
    const first = result.current
    rerender()
    rerender()
    expect(result.current).toBe(first)
  })
})

describe('mutation invalidation scope (FE-2)', () => {
  beforeEach(() => {
    vi.mocked(auditLayerApi.saveMapping).mockResolvedValue(savedMapping)
    vi.mocked(auditLayerApi.createCategory).mockResolvedValue({ code: 'X', label: 'X' } as never)
    vi.mocked(auditLayerApi.saveRealInventory).mockResolvedValue({ id: 'ri' } as never)
  })

  it('saving a mapping invalidates the mapping scopes only — never categories, rules or supplier debt', async () => {
    const client = newClient()
    const spy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useSaveMapping('boris'), { wrapper: wrapperFor(client) })

    await act(async () => {
      await result.current.mutateAsync(savedMapping)
    })
    await waitFor(() => expect(spy).toHaveBeenCalled())

    const scopes = invalidatedScopes(spy)
    for (const scope of MAPPING_SCOPES) {
      expect(scopes).toContain(`${AUDIT_LAYER_KEY}/${scope}`)
    }
    expect(scopes).not.toContain(`${AUDIT_LAYER_KEY}/categories`)
    expect(scopes).not.toContain(`${AUDIT_LAYER_KEY}/mapping-rules`)
    expect(scopes).not.toContain(`${AUDIT_LAYER_KEY}/supplier-debt`)
    // and never the blanket prefix alone
    expect(spy.mock.calls.some((c) => ((c[0] as { queryKey?: unknown[] })?.queryKey ?? []).length === 1)).toBe(false)
  })

  it('creating a category invalidates categories only', async () => {
    const client = newClient()
    const spy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateCategory('boris'), { wrapper: wrapperFor(client) })
    await act(async () => {
      await result.current.mutateAsync({ code: 'X', label: 'X' } as never)
    })
    await waitFor(() => expect(spy).toHaveBeenCalled())
    expect(invalidatedScopes(spy)).toEqual([`${AUDIT_LAYER_KEY}/categories`])
  })

  it('saving real inventory invalidates real-inventory, flows and the overview strip', async () => {
    const client = newClient()
    const spy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useSaveRealInventory('boris'), { wrapper: wrapperFor(client) })
    await act(async () => {
      await result.current.mutateAsync({ id: 'ri' } as never)
    })
    await waitFor(() => expect(spy).toHaveBeenCalled())
    expect(invalidatedScopes(spy).sort()).toEqual([
      `${AUDIT_LAYER_KEY}/flows`,
      `${AUDIT_LAYER_KEY}/overview`,
      `${AUDIT_LAYER_KEY}/real-inventory`,
    ])
  })
})

describe('useSaveMapping optimistic patch (FE-8)', () => {
  it('cancels in-flight source-rows refetches before patching, and patches the saved row in place', async () => {
    vi.mocked(auditLayerApi.saveMapping).mockResolvedValue(savedMapping)
    const client = newClient()
    const rowsKey = [AUDIT_LAYER_KEY, 'source-rows', '2026-01-01', '2026-01-31', '', '', '', 1000]
    client.setQueryData(rowsKey, {
      rows: [
        { sourceType: 'BANK', sourceRowId: 'row-1', status: 'UNMAPPED', unresolvedAmount: 100 },
        { sourceType: 'BANK', sourceRowId: 'row-2', status: 'UNMAPPED', unresolvedAmount: 50 },
      ],
      truncated: false,
    })
    const cancel = vi.spyOn(client, 'cancelQueries')

    const { result } = renderHook(() => useSaveMapping('boris'), { wrapper: wrapperFor(client) })
    await act(async () => {
      await result.current.mutateAsync(savedMapping)
    })

    expect(cancel).toHaveBeenCalledWith({ queryKey: [AUDIT_LAYER_KEY, 'source-rows'] })
    const page = client.getQueryData<{ rows: Array<{ sourceRowId: string; status: string; unresolvedAmount: number }> }>(rowsKey)
    expect(page?.rows.find((r) => r.sourceRowId === 'row-1')).toMatchObject({ status: 'MANUALLY_MAPPED', unresolvedAmount: 0 })
    expect(page?.rows.find((r) => r.sourceRowId === 'row-2')).toMatchObject({ status: 'UNMAPPED', unresolvedAmount: 50 })
  })
})
