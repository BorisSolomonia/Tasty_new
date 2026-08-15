/**
 * Client-side error capture (BOR-82 finding O-6).
 *
 * Before this the frontend had no ErrorBoundary, no `window.onerror`, no
 * `unhandledrejection` handler and no sink: a render crash was a white page
 * whose only trace was the user's own console. This module keeps a bounded
 * ring buffer of the last errors — readable as `window.__tastyErrors` from the
 * console when a user reports "the page went blank" — and installs the two
 * global listeners. There is no reporting backend yet; when one exists,
 * `recordClientError` is the single place to POST from.
 */

export interface ClientErrorRecord {
  at: string
  kind: 'render' | 'window' | 'unhandledrejection' | 'mutation' | 'query'
  message: string
  stack?: string
  context?: Record<string, unknown>
}

const MAX_RECORDS = 50
const records: ClientErrorRecord[] = []

declare global {
  interface Window {
    __tastyErrors?: readonly ClientErrorRecord[]
  }
}

function toRecord(kind: ClientErrorRecord['kind'], error: unknown, context?: Record<string, unknown>): ClientErrorRecord {
  const err = error instanceof Error ? error : null
  return {
    at: new Date().toISOString(),
    kind,
    message: err ? err.message : String(error),
    stack: err?.stack,
    context,
  }
}

export function recordClientError(
  kind: ClientErrorRecord['kind'],
  error: unknown,
  context?: Record<string, unknown>
): ClientErrorRecord {
  const record = toRecord(kind, error, context)
  records.push(record)
  if (records.length > MAX_RECORDS) records.shift()
  if (typeof window !== 'undefined') window.__tastyErrors = records
  console.error(`[tasty:${kind}]`, record.message, error)
  return record
}

export function clientErrors(): readonly ClientErrorRecord[] {
  return records
}

let installed = false

/** Idempotent. Call once from main.tsx. */
export function installGlobalErrorCapture(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  window.addEventListener('error', (event) => {
    recordClientError('window', event.error ?? event.message, {
      source: event.filename,
      line: event.lineno,
      column: event.colno,
    })
  })
  window.addEventListener('unhandledrejection', (event) => {
    recordClientError('unhandledrejection', event.reason)
  })
}
