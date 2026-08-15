/**
 * App-wide notification store (BOR-82 findings O-7/O-8).
 *
 * The Radix toast primitives existed in `components/ui/toast.tsx` but nothing
 * mounted a viewport or exposed a way to raise one, so errors from *actions*
 * (as opposed to loads) had nowhere to render — eighteen mutations across the
 * audit pages declared no `onError` and a failed save simply left the UI
 * unchanged. This store is what `Toaster` renders and what the global
 * `MutationCache.onError` writes to.
 */
import { create } from 'zustand'

export type NotificationVariant = 'default' | 'destructive'

export interface Notification {
  id: number
  title: string
  description?: string
  variant: NotificationVariant
  /** ms; 0 keeps it until dismissed */
  duration: number
}

interface NotificationState {
  items: Notification[]
  notify: (n: Omit<Notification, 'id' | 'duration' | 'variant'> & Partial<Pick<Notification, 'duration' | 'variant'>>) => number
  dismiss: (id: number) => void
}

let nextId = 1
const MAX_VISIBLE = 4

export const useNotifications = create<NotificationState>((set) => ({
  items: [],
  notify: (n) => {
    const id = nextId++
    const item: Notification = {
      id,
      title: n.title,
      description: n.description,
      variant: n.variant ?? 'default',
      duration: n.duration ?? (n.variant === 'destructive' ? 8000 : 4000),
    }
    set((state) => ({ items: [...state.items, item].slice(-MAX_VISIBLE) }))
    return id
  },
  dismiss: (id) => set((state) => ({ items: state.items.filter((i) => i.id !== id) })),
}))

/** Imperative entry point for non-React code (query client, error listeners). */
export function notify(n: Parameters<NotificationState['notify']>[0]): number {
  return useNotifications.getState().notify(n)
}
