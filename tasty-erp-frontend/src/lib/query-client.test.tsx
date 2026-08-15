/** BOR-82 finding O-7: a failed mutation with no local handler must become visible. */
import * as React from 'react'
import { QueryClientProvider, useMutation } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { queryClient } from './query-client'
import { useNotifications } from './notifications'
import { ApiError } from './api-client'

function Wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('global MutationCache.onError', () => {
  beforeEach(() => {
    useNotifications.setState({ items: [] })
  })

  it('raises a destructive toast carrying the server message when the mutation has no onError', async () => {
    const { result } = renderHook(
      () =>
        useMutation({
          mutationFn: async () => {
            throw new ApiError(500, 'Internal Server Error', { message: 'Failed to write audit_mappings/x' })
          },
        }),
      { wrapper: Wrapper }
    )
    await act(async () => {
      await result.current.mutateAsync().catch(() => undefined)
    })
    const items = useNotifications.getState().items
    expect(items).toHaveLength(1)
    expect(items[0]).toMatchObject({ variant: 'destructive', title: 'Save failed' })
    expect(items[0].description).toContain('Failed to write audit_mappings/x')
  })

  it('stays quiet when the mutation handles its own error', async () => {
    let handled = false
    const { result } = renderHook(
      () =>
        useMutation({
          mutationFn: async () => {
            throw new Error('boom')
          },
          onError: () => {
            handled = true
          },
        }),
      { wrapper: Wrapper }
    )
    await act(async () => {
      await result.current.mutateAsync().catch(() => undefined)
    })
    expect(handled).toBe(true)
    expect(useNotifications.getState().items).toHaveLength(0)
  })
})
