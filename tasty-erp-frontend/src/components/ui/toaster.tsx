/**
 * Mounts the toast viewport once (in RootLayout) and renders whatever the
 * notification store holds. See `lib/notifications.ts` for why this exists.
 */
import {
  Toast,
  ToastClose,
  ToastDescription,
  ToastProvider,
  ToastTitle,
  ToastViewport,
} from '@/components/ui/toast'
import { useNotifications } from '@/lib/notifications'

export function Toaster() {
  const items = useNotifications((s) => s.items)
  const dismiss = useNotifications((s) => s.dismiss)
  return (
    <ToastProvider swipeDirection="right">
      {items.map((item) => (
        <Toast
          key={item.id}
          variant={item.variant}
          duration={item.duration || Infinity}
          onOpenChange={(open) => {
            if (!open) dismiss(item.id)
          }}
        >
          <div className="grid gap-1">
            <ToastTitle>{item.title}</ToastTitle>
            {item.description ? <ToastDescription>{item.description}</ToastDescription> : null}
          </div>
          <ToastClose aria-label="Dismiss" />
        </Toast>
      ))}
      <ToastViewport />
    </ToastProvider>
  )
}
