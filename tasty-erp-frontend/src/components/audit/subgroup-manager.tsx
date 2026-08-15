/**
 * Document statuses — the second level of a cash-outflow mapping (BOR-92).
 *
 * Level 1 is the category (group): what the money is. Level 2 is the document
 * status: what paper is still owed for it — "purchase act needed", "check
 * needed", "got check", or anything the operator adds. A status changes no
 * total; it only sorts the same money under a different heading of the
 * cash-outflow tree, so adding one is safe. Deleting a custom status is refused
 * by the server while any mapping still uses it.
 */
import * as React from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { apiErrorMessage } from '@/lib/api-client'
import { useAuditSubgroups, useCreateSubgroup, useDeleteSubgroup } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { useOperatorGuard } from './operator-picker'
import { CollapsiblePanel } from './collapsible-panel'
import { fmtText } from './format'

export function SubgroupManager() {
  const { operator } = useAudit()
  const { ready, message } = useOperatorGuard()
  const query = useAuditSubgroups()
  const subgroups = query.data ?? []
  const create = useCreateSubgroup(operator)
  const remove = useDeleteSubgroup(operator)

  const [open, setOpen] = React.useState(false)
  const [code, setCode] = React.useState('')
  const [label, setLabel] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [error, setError] = React.useState<string | null>(null)
  const [notice, setNotice] = React.useState<string | null>(null)

  const normalized = code.trim().toUpperCase().replace(/[^A-Z0-9_]/g, '_')
  const taken = subgroups.some((s) => s.code === normalized)
  const blocked = !ready ? message : !normalized ? 'A status needs a code.' : taken ? 'That code already exists.' : null

  const handleCreate = async () => {
    if (blocked) return
    setError(null)
    try {
      const saved = await create.mutateAsync({
        code: normalized,
        label: label.trim() || normalized,
        description: description.trim() || null,
        builtIn: false,
      })
      setNotice(`Status ${saved.code} created and available on every split.`)
      setCode('')
      setLabel('')
      setDescription('')
      setOpen(false)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  const handleDelete = async (c: string) => {
    if (!ready) return
    setError(null)
    setNotice(null)
    try {
      await remove.mutateAsync(c)
      setNotice(`Status ${c} deleted.`)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <CollapsiblePanel
      title="Document statuses"
      summary={`${subgroups.length} statuses · ${subgroups.filter((s) => !s.builtIn).length} custom`}
      subtitle="Level 2 of a cash-outflow mapping: what paper is still owed. Changes no total — only where in the outflow tree the money sits."
    >
      <Button type="button" size="sm" variant="outline" className="mb-2 h-7" onClick={() => setOpen((v) => !v)}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        {open ? 'Cancel' : 'New status'}
      </Button>

      {open ? (
        <div className="mb-4 rounded-md border border-primary/40 bg-primary/5 p-3">
          <div className="grid gap-2 sm:grid-cols-3">
            <div>
              <Label htmlFor="subgroup-code" className="text-[11px] text-muted-foreground">
                Code — stored on the split, cannot be changed later
              </Label>
              <Input id="subgroup-code" value={code} className="mt-1 h-8" placeholder="INVOICE_NEEDED" onChange={(e) => setCode(e.target.value)} />
              {code && normalized !== code.trim() ? <p className="mt-0.5 text-[10px] text-muted-foreground">Will be saved as {normalized}</p> : null}
            </div>
            <div>
              <Label htmlFor="subgroup-label" className="text-[11px] text-muted-foreground">
                Label — what operators see
              </Label>
              <Input id="subgroup-label" value={label} className="mt-1 h-8" onChange={(e) => setLabel(e.target.value)} />
            </div>
            <div>
              <Label htmlFor="subgroup-description" className="text-[11px] text-muted-foreground">
                Description (optional)
              </Label>
              <Input id="subgroup-description" value={description} className="mt-1 h-8" onChange={(e) => setDescription(e.target.value)} />
            </div>
          </div>
          {error ? <p className="mt-2 text-xs text-destructive">{error}</p> : null}
          {blocked ? <p className="mt-2 text-xs text-muted-foreground">{blocked}</p> : null}
          <Button type="button" size="sm" className="mt-3" disabled={Boolean(blocked) || create.isPending} onClick={() => void handleCreate()}>
            {create.isPending ? 'Creating…' : 'Create status'}
          </Button>
        </div>
      ) : null}

      {notice ? <p className="mb-3 text-xs text-success">{notice}</p> : null}
      {error && !open ? <p className="mb-3 text-xs text-destructive">{error}</p> : null}

      {query.isError ? (
        <p className="text-sm text-destructive">The status list did not load: {apiErrorMessage(query.error)}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[11px]">
            <thead>
              <tr className="border-b border-border text-left text-muted-foreground">
                <th className="py-1 pr-2 font-semibold">Status</th>
                <th className="py-1 pr-2 font-semibold">Code</th>
                <th className="py-1 pr-2 font-semibold">Meaning</th>
                <th className="py-1 pr-2 font-semibold">Origin</th>
                <th className="py-1 font-semibold"></th>
              </tr>
            </thead>
            <tbody>
              {subgroups.map((s) => (
                <tr key={s.code} className="border-b border-border/70 align-top">
                  <td className="py-1 pr-2 font-medium">{fmtText(s.label)}</td>
                  <td className="py-1 pr-2 font-mono text-[11px]">{s.code}</td>
                  <td className="py-1 pr-2 text-muted-foreground">{fmtText(s.description)}</td>
                  <td className="py-1 pr-2">{s.builtIn ? <span className="text-muted-foreground">built in</span> : <Badge variant="secondary">custom</Badge>}</td>
                  <td className="py-1">
                    {s.builtIn ? null : (
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        className="h-6 px-1 text-destructive"
                        aria-label={`Delete status ${s.code}`}
                        disabled={!ready || remove.isPending}
                        onClick={() => void handleDelete(s.code)}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </CollapsiblePanel>
  )
}
