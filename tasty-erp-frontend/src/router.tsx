import { createRootRoute, createRoute, createRouter, Outlet } from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { AppShell } from '@/components/layout/app-shell'
import { DashboardPage } from '@/pages/dashboard-page'
import { WaybillsPage } from '@/pages/waybills-page'
import { PaymentsPage } from '@/pages/payments-page'
import { SettingsPage } from '@/pages/settings-page'

const rootRoute = createRootRoute({
  component: RootLayout,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: DashboardPage,
})

const waybillsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'waybills',
  component: WaybillsPage,
})

const paymentsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'payments',
  component: PaymentsPage,
})

const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'settings',
  component: SettingsPage,
})

const routeTree = rootRoute.addChildren([indexRoute, waybillsRoute, paymentsRoute, settingsRoute])

export const router = createRouter({
  routeTree,
  defaultPreload: 'intent',
  defaultPreloadStaleTime: 30_000,
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

function RootLayout() {
  return (
    <QueryClientProvider client={queryClient}>
      <AppShell>
        <Outlet />
      </AppShell>
    </QueryClientProvider>
  )
}
