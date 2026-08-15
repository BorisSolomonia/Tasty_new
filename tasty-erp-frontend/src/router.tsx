import { createRootRoute, createRoute, createRouter, lazyRouteComponent, Outlet } from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { AppShell } from '@/components/layout/app-shell'
import { ErrorBoundary, ErrorFallback, NotFound } from '@/components/error-boundary'
import { Toaster } from '@/components/ui/toaster'

const rootRoute = createRootRoute({
  component: RootLayout,
})

// Each page is code-split into its own chunk, fetched on navigation
// (or prefetched on link-hover via defaultPreload: 'intent').
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: lazyRouteComponent(() => import('@/pages/dashboard-page'), 'DashboardPage'),
})

const waybillsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'waybills',
  component: lazyRouteComponent(() => import('@/pages/waybills-page'), 'WaybillsPage'),
})

const paymentsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'payments',
  component: lazyRouteComponent(() => import('@/pages/payments-page'), 'PaymentsPage'),
})

const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'settings',
  component: lazyRouteComponent(() => import('@/pages/settings-page'), 'SettingsPage'),
})

const productSalesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'product-sales',
  component: lazyRouteComponent(() => import('@/pages/product-sales-page'), 'ProductSalesPage'),
})

const auditControlRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'audit-control',
  component: lazyRouteComponent(() => import('@/pages/audit-control-page'), 'AuditControlPage'),
})

// BOR-89 audit layer — eight UX variants over one payload.
const auditRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'audit',
  component: lazyRouteComponent(() => import('@/pages/audit-page'), 'AuditPage'),
})

const productCategoriesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'product-categories',
  component: lazyRouteComponent(() => import('@/pages/product-categories-page'), 'ProductCategoriesPage'),
})

const routeTree = rootRoute.addChildren([indexRoute, waybillsRoute, paymentsRoute, settingsRoute, productSalesRoute, auditControlRoute, auditRoute, productCategoriesRoute])

export const router = createRouter({
  routeTree,
  defaultPreload: 'intent',
  defaultPreloadStaleTime: 30_000,
  // A route that throws (including a failed lazy-chunk fetch after a deploy
  // pruned old assets) renders this instead of unmounting the whole app.
  defaultErrorComponent: ({ error, reset }) => <ErrorFallback error={error} reset={reset} />,
  defaultNotFoundComponent: NotFound,
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

function RootLayout() {
  return (
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary>
        <AppShell>
          <Outlet />
        </AppShell>
      </ErrorBoundary>
      <Toaster />
    </QueryClientProvider>
  )
}
