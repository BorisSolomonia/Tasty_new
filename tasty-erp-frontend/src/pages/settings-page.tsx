import { Card } from '@/components/ui/card'

export function SettingsPage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight md:text-2xl">Settings</h1>
        <p className="text-sm text-muted-foreground">Theme / language / account preferences.</p>
      </div>
      <Card className="p-3 md:p-4">
        <div className="text-sm font-medium">Coming soon</div>
        <div className="mt-1 text-sm text-muted-foreground">
          We’ll wire this into `useThemeStore` and i18n.
        </div>
      </Card>
    </div>
  )
}
