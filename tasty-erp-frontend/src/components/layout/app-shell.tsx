import * as React from 'react'
import { Link, useRouterState } from '@tanstack/react-router'
import { LayoutDashboard, FileText, CreditCard, Settings, Package } from 'lucide-react'
import { cn } from '@/lib/cn'

type NavItem = {
  to: '/' | '/waybills' | '/payments' | '/settings' | '/product-sales'
  label: string
  Icon: React.ComponentType<{ className?: string }>
}

const navItems: NavItem[] = [
  { to: '/', label: 'Dashboard', Icon: LayoutDashboard },
  { to: '/waybills', label: 'Waybills', Icon: FileText },
  { to: '/payments', label: 'Payments', Icon: CreditCard },
  { to: '/settings', label: 'Settings', Icon: Settings },
  { to: '/product-sales', label: 'გაყიდვები', Icon: Package },
]

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-dvh bg-background">
      <DesktopSidebar />

      <div className="flex min-h-dvh flex-col md:pl-64">
        <TopBar />
        <main className="flex-1 pb-[calc(4rem+env(safe-area-inset-bottom))] md:pb-0">
          <div className="mx-auto w-full max-w-screen-2xl p-4 md:p-6">{children}</div>
        </main>
      </div>

      <MobileBottomNav />
    </div>
  )
}

function DesktopSidebar() {
  return (
    <aside className="fixed inset-y-0 left-0 z-40 hidden w-64 flex-col border-r bg-card md:flex">
      <div className="flex h-14 items-center px-4">
        <div className="leading-tight">
          <div className="text-sm font-semibold tracking-tight">Tasty ERP</div>
          <div className="text-xs text-muted-foreground">Meat Distribution</div>
        </div>
      </div>
      <div className="flex-1 overflow-y-auto px-3 py-2">
        <nav className="space-y-1" aria-label="Primary">
          {navItems.map((item) => (
            <SidebarLink key={item.to} item={item} />
          ))}
        </nav>
      </div>
      <div className="border-t px-4 py-3 text-xs text-muted-foreground">
        Fast mobile-first UI
      </div>
    </aside>
  )
}

function SidebarLink({ item }: { item: NavItem }) {
  const { Icon } = item
  const base =
    'flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground'
  const active = 'bg-accent text-accent-foreground'

  return (
    <Link
      to={item.to}
      className={base}
      activeProps={{ className: cn(base, active) }}
      activeOptions={{ exact: item.to === '/' }}
      preload="intent"
    >
      <Icon className="h-4 w-4" />
      <span>{item.label}</span>
    </Link>
  )
}

function MobileBottomNav() {
  return (
    <nav
      className={cn(
        'fixed inset-x-0 bottom-0 z-40 border-t bg-background/85 backdrop-blur md:hidden',
        'pb-[env(safe-area-inset-bottom)]'
      )}
      aria-label="Primary"
    >
      <div className="grid h-16 grid-cols-5">
        {navItems.map((item) => (
          <BottomNavLink key={item.to} item={item} />
        ))}
      </div>
    </nav>
  )
}

function BottomNavLink({ item }: { item: NavItem }) {
  const { Icon } = item
  const base =
    'flex h-full flex-col items-center justify-center gap-1 text-xs text-muted-foreground hover:text-foreground'
  const active = 'text-primary'

  return (
    <Link
      to={item.to}
      className={base}
      activeProps={{ className: cn(base, active) }}
      activeOptions={{ exact: item.to === '/' }}
      preload="intent"
    >
      <Icon className="h-5 w-5" />
      <span className="leading-none">{item.label}</span>
    </Link>
  )
}

function TopBar() {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const title = React.useMemo(() => {
    const found = navItems.find((n) => n.to === (pathname as NavItem['to']))
    return found?.label ?? 'Tasty ERP'
  }, [pathname])

  return (
    <header className="sticky top-0 z-30 border-b bg-background/85 backdrop-blur">
      <div className="mx-auto flex h-14 w-full max-w-screen-2xl items-center justify-between px-4 md:px-6">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium md:text-base">{title}</div>
          <div className="truncate text-xs text-muted-foreground md:hidden">
            Tap bottom navigation for modules
          </div>
        </div>
        <div className="text-xs text-muted-foreground">GE</div>
      </div>
    </header>
  )
}

