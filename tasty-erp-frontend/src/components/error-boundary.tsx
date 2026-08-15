/**
 * Render-crash containment (BOR-82 finding O-6).
 *
 * Wrapping RootLayout means a thrown render in any lazily-loaded page unmounts
 * that page, not the whole tree, and the operator sees what happened plus a
 * way out — instead of a blank window with the error only in their console.
 * The same component doubles as the router's `defaultErrorComponent`.
 */
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { recordClientError } from '@/lib/client-errors'

export function ErrorFallback({
  error,
  reset,
}: {
  error: unknown
  reset?: () => void
}) {
  const message = error instanceof Error ? error.message : String(error)
  return (
    <div role="alert" className="mx-auto my-12 max-w-xl rounded-lg border border-destructive/40 bg-destructive/5 p-6">
      <h1 className="text-lg font-semibold">Something went wrong on this page</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        The rest of the application is unaffected. The error has been recorded in this browser
        (open the console and read <code>window.__tastyErrors</code> to copy it into a report).
      </p>
      <pre className="mt-3 max-h-40 overflow-auto rounded bg-muted p-3 text-xs">{message}</pre>
      <div className="mt-4 flex gap-2">
        {reset ? (
          <Button type="button" onClick={reset}>
            Try again
          </Button>
        ) : null}
        <Button type="button" variant="outline" onClick={() => window.location.reload()}>
          Reload page
        </Button>
      </div>
    </div>
  )
}

interface State {
  error: unknown
}

export class ErrorBoundary extends React.Component<React.PropsWithChildren, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: unknown): State {
    return { error }
  }

  componentDidCatch(error: unknown, info: React.ErrorInfo) {
    recordClientError('render', error, { componentStack: info.componentStack ?? undefined })
  }

  render() {
    if (this.state.error) {
      return <ErrorFallback error={this.state.error} reset={() => this.setState({ error: null })} />
    }
    return this.props.children
  }
}

export function NotFound() {
  return (
    <div className="mx-auto my-12 max-w-xl text-center">
      <h1 className="text-lg font-semibold">Page not found</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        This address does not match any page. Use the navigation to go back.
      </p>
    </div>
  )
}
