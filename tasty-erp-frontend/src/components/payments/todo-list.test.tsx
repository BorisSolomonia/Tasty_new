/**
 * The shared to-do list: adding sends the text with the remembered name,
 * ticking flips at once, and deleting asks first — nothing is removed until
 * the confirmation is accepted.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TodoItem } from '@/lib/api-client'

const list = vi.fn<() => Promise<TodoItem[]>>()
const add = vi.fn()
const setDone = vi.fn()
const remove = vi.fn()

vi.mock('@/lib/api-client', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api-client')>('@/lib/api-client')
  return {
    ...actual,
    todosApi: {
      list: () => list(),
      add: (text: string, author: string | null) => add(text, author),
      setDone: (id: string, done: boolean, by: string | null) => setDone(id, done, by),
      remove: (id: string) => remove(id),
    },
  }
})

import { TodoList } from './todo-list'

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <TodoList />
    </QueryClientProvider>
  )
}

const items: TodoItem[] = [
  { id: 'a', text: 'Call ვაჟა პაპა about the check', done: false, createdBy: 'Boris', createdAt: '2026-08-19T09:00:00', doneBy: null, doneAt: null },
  { id: 'b', text: 'Upload TBC statement', done: true, createdBy: 'Nino', createdAt: '2026-08-18T09:00:00', doneBy: 'Nino', doneAt: '2026-08-18T12:00:00' },
]

describe('TodoList', () => {
  beforeEach(() => {
    list.mockReset().mockResolvedValue(items)
    add.mockReset().mockResolvedValue({ ...items[0], id: 'c', text: 'new' })
    setDone.mockReset().mockResolvedValue({ ...items[0], done: true })
    remove.mockReset().mockResolvedValue(undefined)
    window.localStorage.clear()
  })

  it('lists open items, keeps done ones behind a disclosure, and adds with the remembered name', async () => {
    renderList()
    expect(await screen.findByText('Call ვაჟა პაპა about the check')).toBeInTheDocument()
    expect(screen.getByText(/1 open · 1 done/)).toBeInTheDocument()
    expect(screen.getByText('Done (1)')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Boris' } })
    fireEvent.change(screen.getByLabelText('New to-do'), { target: { value: '  Pay შპს არგო  ' } })
    fireEvent.submit(screen.getByLabelText('New to-do').closest('form') as HTMLFormElement)
    await waitFor(() => expect(add).toHaveBeenCalledWith('Pay შპს არგო', 'Boris'))
    expect(window.localStorage.getItem('tasty.todo.name')).toBe('Boris')
  })

  it('ticks an item optimistically and tells the server who did it', async () => {
    renderList()
    const box = (await screen.findByLabelText('Tick Call ვაჟა პაპა about the check')) as HTMLInputElement
    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Nino' } })
    await act(async () => {
      fireEvent.click(box)
    })
    await waitFor(() => expect(setDone).toHaveBeenCalledWith('a', true, 'Nino'))
  })

  it('asks before deleting and removes only on confirmation', async () => {
    renderList()
    await screen.findByText('Call ვაჟა პაპა about the check')
    fireEvent.click(screen.getByRole('button', { name: 'Delete Call ვაჟა პაპა about the check' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Delete this item?')).toBeInTheDocument()
    expect(remove).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep it' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(remove).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Delete Call ვაჟა პაპა about the check' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(remove).toHaveBeenCalledWith('a'))
  })
})
