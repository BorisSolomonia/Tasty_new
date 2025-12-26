import { useQuery, type UseQueryOptions, type UseQueryResult } from '@tanstack/react-query'
import { getCached, setCached } from './local-storage-cache'

/**
 * Enhanced useQuery hook with localStorage caching.
 *
 * This hook:
 * 1. Checks localStorage first for cached data
 * 2. If found and valid, uses it as initialData
 * 3. Fetches fresh data from API in background
 * 4. Updates localStorage when fresh data arrives
 *
 * Benefits:
 * - Instant UI rendering with cached data
 * - Reduced Firebase reads (only fetches when cache expires)
 * - Persistent across page reloads
 */

interface UseCachedQueryOptions<T> extends Omit<UseQueryOptions<T>, 'initialData'> {
  queryKey: readonly unknown[]
  queryFn: () => Promise<T>
  cacheKey?: string // Optional: custom localStorage key (defaults to queryKey)
  cacheTTL?: number // Optional: cache time-to-live in ms (default: 24 hours)
}

export function useCachedQuery<T>({
  queryKey,
  queryFn,
  cacheKey,
  cacheTTL = 24 * 60 * 60 * 1000, // 24 hours default
  ...options
}: UseCachedQueryOptions<T>): UseQueryResult<T> {
  // Generate cache key from queryKey if not provided
  const storageKey = cacheKey || queryKey.join('_')

  // Try to get cached data
  const cached = getCached<T>(storageKey)

  // Use React Query with cached data as initialData
  const result = useQuery({
    queryKey,
    queryFn: async () => {
      // Fetch fresh data
      const freshData = await queryFn()

      // Update localStorage cache
      setCached(storageKey, freshData, cacheTTL)

      return freshData
    },
    initialData: cached || undefined,
    ...options,
  })

  return result
}
