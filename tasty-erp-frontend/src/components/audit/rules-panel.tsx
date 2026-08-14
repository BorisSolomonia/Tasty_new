/**
 * BOR-91 — the saved mapping rules, and what they have done.
 *
 * A rule that maps rows on its own has to be answerable for them, so every rule
 * here carries the count and value it has applied, the criterion in words and
 * the operator who created it.
 *
 * Revoking is safe by design and the screen says why: it un-maps every mapping
 * the rule created. That is the property that makes it reasonable to be
 * generous with rules in the first place, so it is stated on the button rather
 * than buried in a tooltip.
 *
 * Editing is limited to what the rule *asserts* (category, counterparty, note).
 * What "similar" means — the criterion and its keys — is deliberately not
 * editable: changing it silently redefines the set of rows already mapped under
 * the old meaning. To change that, revoke and create a new rule from a row.
 */
import * as React from 'react'
import { Ban, Pencil, Save, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Badge } from '@/components/ui/badge'
import { apiErrorMessage } from '@/lib/api-client'
import { cn } from '@/lib/cn'
import { CRITERION_LABEL, type AuditMappingRule } from '@/lib/audit-api'
import {
  useMappingRules,
  useRevokeMappingRule,
  useSaveMappingRule,
} from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { CollapsiblePanel } from './collapsible-panel'
import { useOperatorGuard } from './operator-picker'
import { fmtCount, fmtDate, fmtGel, fmtText } from './format'

export function RulesPanel() {
  const rulesQuery = useMappingRules()
  const rules = rulesQuery.data ?? []
  const active = rules.filter((rule) => rule.active)
  const revoked = rules.filter((rule) => !rule.active)

  const applied = rules.reduce((sum, rule) => sum + (rule.appliedCount ?? 0), 0)
  const appliedAmount = rules.reduce((sum, rule) => sum + (rule.appliedAmount ?? 0), 0)

  return (
    <CollapsiblePanel
      title="Saved mapping rules"
      summary={`${fmtCount(active.length)} active · ${fmtCount(
        revoked.length
      )} revoked · ${fmtCount(applied)} mappings, ${fmtGel(appliedAmount)}`}
      subtitle="Classifications that apply beyond the transaction they were created on. Each one names what it matches, what it asserts and how much it has done."
    >
      {rulesQuery.isLoading ? (
        <Skeleton className="h-32 w-full" />
      ) : rulesQuery.isError ? (
        <p className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm">
          The saved-rules endpoint did not answer:{' '}
          <span className="font-medium">{apiErrorMessage(rulesQuery.error)}</span>. Read this as
          unknown, not as “no rules exist” — rows mapped by a rule still show that they were.
        </p>
      ) : rules.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No rule has been created. Every mapping so far applies to its own transaction only.
        </p>
      ) : (
        <div className="space-y-3">
          {active.map((rule) => (
            <RuleCard key={rule.id ?? Math.random()} rule={rule} />
          ))}

          {revoked.length > 0 ? (
            <div className="pt-2">
              <div className="mb-2 text-xs font-semibold text-muted-foreground">
                Revoked ({fmtCount(revoked.length)}) — kept so past mappings still resolve
              </div>
              <div className="space-y-2">
                {revoked.map((rule) => (
                  <RuleCard key={rule.id ?? Math.random()} rule={rule} />
                ))}
              </div>
            </div>
          ) : null}
        </div>
      )}
    </CollapsiblePanel>
  )
}

function RuleCard({ rule }: { rule: AuditMappingRule }) {
  const { operator, categories, filters } = useAudit()
  const { ready, message } = useOperatorGuard()
  // Saving re-applies the rule, and it re-applies over the period on screen.
  const saveRule = useSaveMappingRule(operator, {
    startDate: filters.startDate,
    endDate: filters.endDate,
  })
  const revokeRule = useRevokeMappingRule(operator)

  const [editing, setEditing] = React.useState(false)
  const [categoryCode, setCategoryCode] = React.useState(rule.categoryCode ?? '')
  const [note, setNote] = React.useState(rule.note ?? '')
  const [revoking, setRevoking] = React.useState(false)
  const [reason, setReason] = React.useState('')
  const [error, setError] = React.useState<string | null>(null)

  const criterion = rule.criterion ? CRITERION_LABEL[rule.criterion] : 'Criterion not stated'
  const category = categories.find((entry) => entry.code === rule.categoryCode) ?? null

  const matchKey = ((): string => {
    switch (rule.criterion) {
      case 'COUNTERPARTY':
        return fmtText(rule.counterpartyName ?? rule.counterpartyTin)
      case 'COUNTERPARTY_AND_DESCRIPTION':
        return `${fmtText(rule.counterpartyName ?? rule.counterpartyTin)} · ${fmtText(rule.description)}`
      case 'DESCRIPTION':
        return fmtText(rule.description)
      case 'TRANSACTION_TYPE':
        return fmtText(rule.transactionType)
      default:
        return '—'
    }
  })()

  const handleSave = async () => {
    setError(null)
    try {
      await saveRule.mutateAsync({
        ...rule,
        categoryCode: categoryCode || null,
        note: note.trim() ? note.trim() : null,
      })
      setEditing(false)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  const handleRevoke = async () => {
    if (!rule.id || !reason.trim()) return
    setError(null)
    try {
      await revokeRule.mutateAsync({ id: rule.id, reason: reason.trim() })
      setRevoking(false)
      setReason('')
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <div
      className={cn(
        'rounded-md border p-3',
        rule.active ? 'border-border' : 'border-border/60 bg-muted/30'
      )}
    >
      <div className="flex flex-wrap items-start gap-2">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={rule.active ? 'secondary' : 'outline'} className="whitespace-nowrap">
              {rule.direction ?? 'ANY DIRECTION'}
            </Badge>
            <span className="text-sm font-medium">{criterion}</span>
          </div>
          <p className="mt-0.5 break-words text-xs text-muted-foreground">Matches: {matchKey}</p>
          <p className="mt-1 text-xs">
            Asserts:{' '}
            <span className="font-medium">
              {category?.label ?? fmtText(rule.categoryCode)}
            </span>
            {rule.mappedCounterpartyName ? ` · ${rule.mappedCounterpartyName}` : ''}
          </p>
          {rule.note ? <p className="mt-1 text-xs italic text-muted-foreground">{rule.note}</p> : null}
        </div>

        <div className="text-right text-xs">
          <div className="font-semibold tabular-nums">{fmtCount(rule.appliedCount)} mappings</div>
          <div className="tabular-nums text-muted-foreground">{fmtGel(rule.appliedAmount)}</div>
          <div className="mt-1 text-muted-foreground">
            {fmtText(rule.createdBy)} · {fmtDate(rule.createdAt)}
          </div>
        </div>
      </div>

      {editing ? (
        <div className="mt-3 grid gap-2 border-t border-border pt-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto]">
          <div>
            <Label className="text-[11px] text-muted-foreground">Category</Label>
            <select
              className="mt-1 h-8 w-full rounded-md border border-input bg-background px-2 text-sm"
              value={categoryCode}
              onChange={(event) => setCategoryCode(event.target.value)}
            >
              <option value="">Select category…</option>
              {categories.map((entry) => (
                <option key={entry.code} value={entry.code}>
                  {entry.label ?? entry.code}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label className="text-[11px] text-muted-foreground">Note</Label>
            <Input
              value={note}
              className="mt-1 h-8"
              onChange={(event) => setNote(event.target.value)}
            />
          </div>
          <div className="flex items-end gap-2">
            <Button
              type="button"
              size="sm"
              disabled={!ready || !categoryCode || saveRule.isPending}
              onClick={() => void handleSave()}
            >
              <Save className="mr-1 h-3.5 w-3.5" />
              {saveRule.isPending ? 'Saving…' : 'Save'}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(false)}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
          <p className="text-[11px] text-muted-foreground sm:col-span-3">
            Saving re-applies the rule across the period selected at the top of the page. Rows a
            person classified by hand are never overwritten.
          </p>
        </div>
      ) : null}

      {revoking ? (
        <div className="mt-3 border-t border-border pt-3">
          <p className="text-xs font-semibold text-destructive">
            Revoking un-maps every mapping this rule created — {fmtCount(rule.appliedCount)} rows,{' '}
            {fmtGel(rule.appliedAmount)}. Those rows return to unmapped.
          </p>
          <Label htmlFor={`revoke-${rule.id}`} className="mt-2 block text-xs text-muted-foreground">
            Reason — kept permanently in the change log
          </Label>
          <Input
            id={`revoke-${rule.id}`}
            value={reason}
            className="mt-1 h-8"
            onChange={(event) => setReason(event.target.value)}
          />
          <div className="mt-2 flex gap-2">
            <Button
              type="button"
              size="sm"
              variant="destructive"
              disabled={!ready || !reason.trim() || revokeRule.isPending}
              onClick={() => void handleRevoke()}
            >
              {revokeRule.isPending ? 'Revoking…' : 'Confirm revoke'}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setRevoking(false)}>
              Cancel
            </Button>
          </div>
        </div>
      ) : null}

      {error ? <p className="mt-2 text-xs text-destructive">{error}</p> : null}
      {!ready && (editing || revoking) ? (
        <p className="mt-2 text-xs text-muted-foreground">{message}</p>
      ) : null}

      {rule.active && !editing && !revoking ? (
        <div className="mt-2 flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="outline" className="h-7" onClick={() => setEditing(true)}>
            <Pencil className="mr-1 h-3.5 w-3.5" />
            Edit what it asserts
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-7 text-destructive"
            onClick={() => setRevoking(true)}
          >
            <Ban className="mr-1 h-3.5 w-3.5" />
            Revoke and un-map its {fmtCount(rule.appliedCount)} rows
          </Button>
        </div>
      ) : null}
    </div>
  )
}
