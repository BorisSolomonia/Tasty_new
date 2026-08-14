/**
 * Categories, and the behaviour flags that decide what they feed (BOR-91).
 *
 * A category is not a label — each flag routes the amounts mapped to it into a
 * different total, so creating one without understanding the flags silently
 * moves money between the audit's headline figures. Every flag therefore
 * carries a one-line statement of which total it feeds, next to the checkbox
 * rather than in documentation nobody opens.
 *
 * A category with no flag set is legitimate and means "counted nowhere in
 * particular" — so the form says that too, instead of forcing a choice.
 */
import * as React from 'react'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { apiErrorMessage } from '@/lib/api-client'
import type { AuditCategory } from '@/lib/audit-api'
import { useCreateCategory } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { useOperatorGuard } from './operator-picker'
import { CollapsiblePanel } from './collapsible-panel'
import { fmtText } from './format'

type FlagKey =
  | 'supplierSettlement'
  | 'customerReceipt'
  | 'nonSupplierExpense'
  | 'cashReturn'
  | 'paperOnly'
  | 'unresolved'

/** The flag, and the total it feeds. One line, because that is the whole rule. */
const FLAGS: { key: FlagKey; label: string; feeds: string }[] = [
  {
    key: 'supplierSettlement',
    label: 'Supplier settlement',
    feeds: 'Feeds supplier-allocated cash settlements and the purchase coverage control.',
  },
  {
    key: 'customerReceipt',
    label: 'Customer receipt',
    feeds: 'Feeds customer receipts — money in against a sale.',
  },
  {
    key: 'nonSupplierExpense',
    label: 'Non-supplier expense',
    feeds: 'Feeds other spending. Money out that settled no supplier.',
  },
  {
    key: 'cashReturn',
    label: 'Cash return',
    feeds: 'Feeds returned / redeposited cash — money that came back.',
  },
  {
    key: 'paperOnly',
    label: 'Paper only',
    feeds: 'Moves paper cash, not real cash. Feeds the paper-cash bridge.',
  },
  {
    key: 'unresolved',
    label: 'Unresolved',
    feeds: 'Keeps the amount counted as unexplained instead of claiming it is accounted for.',
  },
]

/** Flag update kept in one typed place — a computed key would widen the draft. */
function withFlag(category: AuditCategory, key: FlagKey, value: boolean): AuditCategory {
  return Object.assign({}, category, { [key]: value })
}

function emptyDraft(): AuditCategory {
  return {
    code: '',
    label: '',
    builtIn: false,
    supplierSettlement: false,
    customerReceipt: false,
    nonSupplierExpense: false,
    cashReturn: false,
    paperOnly: false,
    unresolved: false,
    description: '',
  }
}

export function CategoryManager() {
  const { categories, categoriesQuery, operator } = useAudit()
  const { ready, message } = useOperatorGuard()
  const createCategory = useCreateCategory(operator)

  const [open, setOpen] = React.useState(false)
  const [draft, setDraft] = React.useState<AuditCategory>(emptyDraft)
  const [error, setError] = React.useState<string | null>(null)
  const [created, setCreated] = React.useState<string | null>(null)

  const codeTaken = categories.some(
    (category) => category.code.trim().toLowerCase() === draft.code.trim().toLowerCase()
  )
  const blocked = !ready
    ? message
    : !draft.code.trim()
      ? 'A category needs a code.'
      : codeTaken
        ? 'That code already exists.'
        : null

  const handleCreate = async () => {
    if (blocked) return
    setError(null)
    try {
      const saved = await createCategory.mutateAsync({
        ...draft,
        code: draft.code.trim(),
        label: draft.label?.trim() ? draft.label.trim() : draft.code.trim(),
        description: draft.description?.trim() ? draft.description.trim() : null,
      })
      setCreated(saved.code)
      setDraft(emptyDraft())
      setOpen(false)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <CollapsiblePanel
      title="Categories"
      summary={`${categories.length} categories · ${
        categories.filter((category) => !category.builtIn).length
      } custom`}
      subtitle="What a mapping can say. The flags on a category decide which totals its amounts feed."
    >
      {/*
        In the body, not the header: the form it opens renders in the body, and
        a button that appears to do nothing while the panel is shut is worse
        than one extra click.
      */}
      <Button
        type="button"
        size="sm"
        variant="outline"
        className="mb-2 h-7"
        onClick={() => setOpen((v) => !v)}
      >
        <Plus className="mr-1 h-3.5 w-3.5" />
        {open ? 'Cancel' : 'New category'}
      </Button>

      {open ? (
        <div className="mb-4 rounded-md border border-primary/40 bg-primary/5 p-3">
          <div className="grid gap-2 sm:grid-cols-3">
            <div>
              <Label htmlFor="category-code" className="text-[11px] text-muted-foreground">
                Code — used in the payload, cannot be changed later
              </Label>
              <Input
                id="category-code"
                value={draft.code}
                className="mt-1 h-8"
                placeholder="SUPPLIER_CASH"
                onChange={(event) => setDraft((current) => ({ ...current, code: event.target.value }))}
              />
            </div>
            <div>
              <Label htmlFor="category-label" className="text-[11px] text-muted-foreground">
                Label — what operators see
              </Label>
              <Input
                id="category-label"
                value={draft.label ?? ''}
                className="mt-1 h-8"
                onChange={(event) => setDraft((current) => ({ ...current, label: event.target.value }))}
              />
            </div>
            <div>
              <Label htmlFor="category-description" className="text-[11px] text-muted-foreground">
                Description (optional)
              </Label>
              <Input
                id="category-description"
                value={draft.description ?? ''}
                className="mt-1 h-8"
                onChange={(event) =>
                  setDraft((current) => ({ ...current, description: event.target.value }))
                }
              />
            </div>
          </div>

          <div className="mt-3 space-y-1.5">
            <div className="text-xs font-semibold">Behaviour — what amounts in this category do</div>
            {FLAGS.map((flag) => (
              <label key={flag.key} className="flex cursor-pointer items-start gap-2 text-xs">
                <input
                  type="checkbox"
                  className="mt-0.5"
                  checked={Boolean(draft[flag.key])}
                  onChange={(event) =>
                    setDraft((current) => withFlag(current, flag.key, event.target.checked))
                  }
                />
                <span>
                  <span className="font-medium">{flag.label}</span>{' '}
                  <span className="text-muted-foreground">{flag.feeds}</span>
                </span>
              </label>
            ))}
            <p className="pt-1 text-[11px] text-muted-foreground">
              No flag set is allowed: the category then feeds no headline total, and amounts mapped
              to it are counted as classified but not attributed.
            </p>
          </div>

          {error ? <p className="mt-2 text-xs text-destructive">{error}</p> : null}
          {blocked ? <p className="mt-2 text-xs text-muted-foreground">{blocked}</p> : null}

          <Button
            type="button"
            size="sm"
            className="mt-3"
            disabled={Boolean(blocked) || createCategory.isPending}
            onClick={() => void handleCreate()}
          >
            {createCategory.isPending ? 'Creating…' : 'Create category'}
          </Button>
        </div>
      ) : null}

      {created ? (
        <p className="mb-3 text-xs text-success">
          Category {created} created and available in every mapping editor.
        </p>
      ) : null}

      {categoriesQuery.isError ? (
        <p className="text-sm text-destructive">
          The category list did not load: {apiErrorMessage(categoriesQuery.error)}
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[11px]">
            <thead>
              <tr className="border-b border-border text-left text-muted-foreground">
                <th className="py-1 pr-2 font-semibold">Category</th>
                <th className="py-1 pr-2 font-semibold">Code</th>
                <th className="py-1 pr-2 font-semibold">Feeds</th>
                <th className="py-1 font-semibold">Origin</th>
              </tr>
            </thead>
            <tbody>
              {categories.map((category) => {
                const set = FLAGS.filter((flag) => Boolean(category[flag.key]))
                return (
                  <tr key={category.code} className="border-b border-border/70 align-top">
                    <td className="py-1 pr-2 font-medium">{fmtText(category.label)}</td>
                    <td className="py-1 pr-2 font-mono text-[11px]">{category.code}</td>
                    <td className="py-1 pr-2">
                      {set.length === 0 ? (
                        <span className="text-muted-foreground">no headline total</span>
                      ) : (
                        <span className="flex flex-wrap gap-1">
                          {set.map((flag) => (
                            <Badge key={flag.key} variant="outline" title={flag.feeds}>
                              {flag.label}
                            </Badge>
                          ))}
                        </span>
                      )}
                    </td>
                    <td className="py-1">
                      {category.builtIn ? (
                        <span className="text-muted-foreground">built in</span>
                      ) : (
                        <Badge variant="secondary">custom</Badge>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </CollapsiblePanel>
  )
}
