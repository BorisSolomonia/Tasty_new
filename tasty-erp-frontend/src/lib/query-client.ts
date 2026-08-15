import { MutationCache, QueryClient } from '@tanstack/react-query'
import { apiErrorMessage } from '@/lib/api-client'
import { notify } from '@/lib/notifications'
import { recordClientError } from '@/lib/client-errors'

/**
 * One place where a failed mutation becomes visible.
 *
 * Eighteen `useMutation` calls across the audit pages declared no `onError`
 * (BOR-82 finding O-7): an operator marked a reconciliation row paid or edited
 * a write-off rate, the PUT 500'd, `onSuccess` never fired, and the UI simply
 * did not change — read as "slow refresh", so the ledger silently diverged from
 * what the operator believed they saved. Mutations that handle their own error
 * (they pass `onError`) are left alone; everything else gets a destructive
 * toast with the server's real message.
 */
const mutationCache = new MutationCache({
  onError: (error, _variables, _context, mutation) => {
    recordClientError('mutation', error, { mutationKey: mutation.options.mutationKey })
    if (mutation.options.onError) return
    notify({
      variant: 'destructive',
      title: 'Save failed',
      description: apiErrorMessage(error),
    })
  },
})

export const queryClient = new QueryClient({
  mutationCache,
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      gcTime: 1000 * 60 * 30, // 30 minutes (formerly cacheTime)
      retry: 1,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 1,
    },
  },
})
