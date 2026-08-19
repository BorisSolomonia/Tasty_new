/**
 * The shared checklist at the bottom of /payments.
 *
 * One list for everyone: anyone adds, ticks, or deletes, and sees what the
 * others did (names are self-declared, remembered in this browser). Built
 * for a phone first — one column, full-width input, 44 px touch targets —
 * and it re-asks the server every half minute so two people working the same
 * list stay in step. Deleting always asks first; ticking is reversible so it
 * does not.
 */
import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { apiErrorMessage, todosApi, type TodoItem } from '@/lib/api-client'
import { cn } from '@/lib/cn'

const TODOS_KEY = ['payments', 'todos'] as const
const NAME_KEY = 'tasty.todo.name'
const REFRESH_MS = 30_000

function readName(): string {
  try {
    return window.localStorage.getItem(NAME_KEY) ?? window.localStorage.getItem('tasty.audit.operator') ?? ''
  } catch {
    return ''
  }
}

function when(iso: string | null): string {
  if (!iso) return ''
  return iso.replace('T', ' ').slice(0, 16)
}

export function TodoList() {
  const queryClient = useQueryClient()
  const [text, setText] = React.useState('')
  const [name, setName] = React.useState<string>(() => readName())
  const [pendingDelete, setPendingDelete] = React.useState<TodoItem | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const inputRef = React.useRef<HTMLInputElement>(null)

  const query = useQuery({
    queryKey: TODOS_KEY,
    queryFn: todosApi.list,
    staleTime: 10_000,
    refetchInterval: REFRESH_MS,
    refetchIntervalInBackground: false,
    retry: 1,
  })
  const invalidate = () => queryClient.invalidateQueries({ queryKey: TODOS_KEY })

  const addMutation = useMutation({
    mutationFn: (t: string) => todosApi.add(t, name.trim() || null),
    onSuccess: () => {
      setText('')
      setError(null)
      void invalidate()
      inputRef.current?.focus()
    },
    onError: (e) => setError(apiErrorMessage(e)),
  })
  const toggleMutation = useMutation({
    mutationFn: ({ id, done }: { id: string; done: boolean }) => todosApi.setDone(id, done, name.trim() || null),
    // Optimistic: the box flips at once; the server's answer replaces it.
    onMutate: async ({ id, done }) => {
      await queryClient.cancelQueries({ queryKey: TODOS_KEY })
      const previous = queryClient.getQueryData<TodoItem[]>(TODOS_KEY)
      queryClient.setQueryData<TodoItem[]>(TODOS_KEY, (items) => items?.map((i) => (i.id === id ? { ...i, done } : i)))
      return { previous }
    },
    onError: (e, _v, ctx) => {
      if (ctx?.previous) queryClient.setQueryData(TODOS_KEY, ctx.previous)
      setError(apiErrorMessage(e))
    },
    onSettled: () => void invalidate(),
  })
  const deleteMutation = useMutation({
    mutationFn: (id: string) => todosApi.remove(id),
    onSuccess: () => {
      setPendingDelete(null)
      setError(null)
      void invalidate()
    },
    onError: (e) => setError(apiErrorMessage(e)),
  })

  const items = query.data ?? []
  const open = items.filter((i) => !i.done)
  const done = items.filter((i) => i.done)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!text.trim() || addMutation.isPending) return
    addMutation.mutate(text.trim())
  }

  const rememberName = (v: string) => {
    setName(v)
    try {
      window.localStorage.setItem(NAME_KEY, v)
    } catch {
      /* storage unavailable — the name just is not remembered */
    }
  }

  return (
    <Card className="p-3 md:p-4" aria-labelledby="todo-h">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="todo-h" className="text-base font-semibold">
          გასაკეთებელი <span className="text-sm font-normal text-muted-foreground">· shared to-do list</span>
        </h2>
        <span className="text-xs text-muted-foreground">
          {query.isLoading ? 'loading…' : `${open.length} open · ${done.length} done`}
        </span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">Everyone sees the same list. Tick when done; deleting asks first.</p>

      <form onSubmit={submit} className="mt-3 flex flex-col gap-2 sm:flex-row">
        <input
          ref={inputRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="What needs doing?"
          aria-label="New to-do"
          maxLength={500}
          className="h-11 min-w-0 flex-1 rounded-md border border-input bg-background px-3 text-base focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        />
        <div className="flex gap-2">
          <input
            value={name}
            onChange={(e) => rememberName(e.target.value)}
            placeholder="Your name"
            aria-label="Your name"
            maxLength={40}
            className="h-11 w-32 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
          <Button type="submit" className="h-11 px-4" disabled={!text.trim() || addMutation.isPending}>
            <Plus className="mr-1 h-4 w-4" /> Add
          </Button>
        </div>
      </form>

      {error ? (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}
      {query.isError ? <p className="mt-2 text-sm text-destructive">The list did not load: {apiErrorMessage(query.error)}</p> : null}

      <ul className="mt-3 divide-y divide-border" aria-label="Open items">
        {open.map((item) => (
          <TodoRow key={item.id} item={item} onToggle={(d) => toggleMutation.mutate({ id: item.id, done: d })} onDelete={() => setPendingDelete(item)} />
        ))}
        {!query.isLoading && !open.length ? <li className="py-3 text-sm text-muted-foreground">Nothing open.</li> : null}
      </ul>

      {done.length ? (
        <details className="mt-2">
          <summary className="cursor-pointer select-none py-2 text-sm text-muted-foreground">Done ({done.length})</summary>
          <ul className="divide-y divide-border" aria-label="Done items">
            {done.map((item) => (
              <TodoRow key={item.id} item={item} onToggle={(d) => toggleMutation.mutate({ id: item.id, done: d })} onDelete={() => setPendingDelete(item)} />
            ))}
          </ul>
        </details>
      ) : null}

      {pendingDelete ? (
        <Dialog open onOpenChange={(o) => (!o ? setPendingDelete(null) : undefined)}>
          <DialogContent aria-describedby="todo-delete-desc">
            <DialogHeader>
              <DialogTitle>Delete this item?</DialogTitle>
              <DialogDescription id="todo-delete-desc">
                “{pendingDelete.text}” will be removed for everyone. This cannot be undone.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter className="gap-2">
              <Button variant="outline" className="h-11" onClick={() => setPendingDelete(null)} disabled={deleteMutation.isPending}>
                Keep it
              </Button>
              <Button variant="destructive" className="h-11" onClick={() => deleteMutation.mutate(pendingDelete.id)} disabled={deleteMutation.isPending}>
                {deleteMutation.isPending ? 'Deleting…' : 'Delete'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}
    </Card>
  )
}

function TodoRow({ item, onToggle, onDelete }: { item: TodoItem; onToggle: (done: boolean) => void; onDelete: () => void }) {
  const id = `todo-${item.id}`
  return (
    <li className="flex items-start gap-3 py-2">
      <input
        id={id}
        type="checkbox"
        checked={item.done}
        onChange={(e) => onToggle(e.target.checked)}
        className="mt-2.5 h-5 w-5 shrink-0 accent-primary"
        aria-label={`${item.done ? 'Untick' : 'Tick'} ${item.text}`}
      />
      <label htmlFor={id} className="min-h-11 flex-1 cursor-pointer py-2">
        <span className={cn('block break-words text-base leading-snug', item.done && 'text-muted-foreground line-through')}>{item.text}</span>
        <span className="block text-[11px] text-muted-foreground">
          {item.createdBy ? `${item.createdBy} · ` : ''}
          {when(item.createdAt)}
          {item.done ? ` · done${item.doneBy ? ` by ${item.doneBy}` : ''} ${when(item.doneAt)}` : ''}
        </span>
      </label>
      {item.done ? <Check className="mt-3 h-4 w-4 shrink-0 text-success" aria-hidden /> : null}
      <Button type="button" variant="ghost" size="sm" className="h-11 w-11 shrink-0 p-0 text-destructive" aria-label={`Delete ${item.text}`} onClick={onDelete}>
        <Trash2 className="h-4 w-4" />
      </Button>
    </li>
  )
}
