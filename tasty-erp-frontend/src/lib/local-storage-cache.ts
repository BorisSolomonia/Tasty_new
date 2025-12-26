/**
 * Local Storage Cache Utility
 *
 * Provides persistent caching for static/infrequent data to reduce Firebase reads.
 * Each cache entry has a TTL (time-to-live) to ensure data freshness.
 */

interface CacheEntry<T> {
  data: T
  timestamp: number
  ttl: number // milliseconds
}

const CACHE_PREFIX = 'tasty_erp_'

/**
 * Get data from localStorage cache.
 * Returns null if cache is missing or expired.
 */
export function getCached<T>(key: string): T | null {
  try {
    const cacheKey = CACHE_PREFIX + key
    const cached = localStorage.getItem(cacheKey)

    if (!cached) {
      return null
    }

    const entry: CacheEntry<T> = JSON.parse(cached)
    const now = Date.now()

    // Check if cache has expired
    if (now - entry.timestamp > entry.ttl) {
      localStorage.removeItem(cacheKey)
      return null
    }

    return entry.data
  } catch (error) {
    console.error('Error reading from localStorage cache:', error)
    return null
  }
}

/**
 * Store data in localStorage cache with TTL.
 * @param key - Cache key
 * @param data - Data to cache
 * @param ttl - Time-to-live in milliseconds (default: 24 hours)
 */
export function setCached<T>(key: string, data: T, ttl: number = 24 * 60 * 60 * 1000): void {
  try {
    const cacheKey = CACHE_PREFIX + key
    const entry: CacheEntry<T> = {
      data,
      timestamp: Date.now(),
      ttl,
    }

    localStorage.setItem(cacheKey, JSON.stringify(entry))
  } catch (error) {
    console.error('Error writing to localStorage cache:', error)
    // If localStorage is full, clear old caches
    if (error instanceof DOMException && error.name === 'QuotaExceededError') {
      clearOldCaches()
      // Try again
      try {
        const cacheKey = CACHE_PREFIX + key
        const entry: CacheEntry<T> = {
          data,
          timestamp: Date.now(),
          ttl,
        }
        localStorage.setItem(cacheKey, JSON.stringify(entry))
      } catch {
        console.error('Failed to cache after clearing old entries')
      }
    }
  }
}

/**
 * Invalidate (remove) a cache entry.
 */
export function invalidateCache(key: string): void {
  try {
    const cacheKey = CACHE_PREFIX + key
    localStorage.removeItem(cacheKey)
  } catch (error) {
    console.error('Error invalidating cache:', error)
  }
}

/**
 * Clear all expired cache entries.
 */
export function clearExpiredCaches(): void {
  try {
    const now = Date.now()
    const keys = Object.keys(localStorage)

    for (const key of keys) {
      if (!key.startsWith(CACHE_PREFIX)) {
        continue
      }

      try {
        const cached = localStorage.getItem(key)
        if (!cached) continue

        const entry: CacheEntry<unknown> = JSON.parse(cached)
        if (now - entry.timestamp > entry.ttl) {
          localStorage.removeItem(key)
        }
      } catch {
        // Invalid cache entry, remove it
        localStorage.removeItem(key)
      }
    }
  } catch (error) {
    console.error('Error clearing expired caches:', error)
  }
}

/**
 * Clear the oldest cache entries (useful when quota exceeded).
 */
function clearOldCaches(): void {
  try {
    const keys = Object.keys(localStorage)
    const cacheKeys = keys.filter(k => k.startsWith(CACHE_PREFIX))

    const entries = cacheKeys
      .map(key => {
        try {
          const cached = localStorage.getItem(key)
          if (!cached) return null
          const entry = JSON.parse(cached) as CacheEntry<unknown>
          return { key, timestamp: entry.timestamp }
        } catch {
          return null
        }
      })
      .filter((e): e is { key: string; timestamp: number } => e !== null)
      .sort((a, b) => a.timestamp - b.timestamp)

    // Remove oldest 50% of cache entries
    const toRemove = entries.slice(0, Math.ceil(entries.length / 2))
    toRemove.forEach(e => localStorage.removeItem(e.key))
  } catch (error) {
    console.error('Error clearing old caches:', error)
  }
}

/**
 * Clear all Tasty ERP caches.
 */
export function clearAllCaches(): void {
  try {
    const keys = Object.keys(localStorage)
    keys.forEach(key => {
      if (key.startsWith(CACHE_PREFIX)) {
        localStorage.removeItem(key)
      }
    })
  } catch (error) {
    console.error('Error clearing all caches:', error)
  }
}

// Clear expired caches on load
clearExpiredCaches()
