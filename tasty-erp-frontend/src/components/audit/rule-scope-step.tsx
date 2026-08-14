/**
 * BOR-91 — the scope step: "only this transaction, or also the ones like it?"
 *
 * Nothing generalises on its own. The step is interposed between building a
 * mapping and committing it, and it opens on **Only this transaction** every
 * time. A criterion is never pre-selected, however obvious it looks: choosing
 * to classify a hundred rows at once is a decision a person makes, and a
 * default that makes it for them is the failure this screen exists to prevent.
 *
 * Each criterion the backend says applies is shown with its own plain-language
 * explanation, what it would catch (count and value) and a sample of the actual
 * rows, so "similar" is legible before it is committed rather than discovered
 * afterwards.
 *
 * Order of writes on confirm is deliberate: the single-row mapping is saved
 * first, then the rule. The user's decision about the row in front of them is
 * committed even if the rule call fails, and never the other way round.
 */
import * as React from 'react'
import { AlertTriangle, ArrowLeft, Check, Loader2, Wand2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api-client'
import { cn } from '@/lib/cn'
import {
  CRITERION_LABEL,
  type AuditMapping,
  type AuditMappingRule,
  type AuditSourceRow,
  type MappingRuleCriterion,
} from '@/lib/audit-api'
import {
  useMappingRulePreview,
  useSaveMapping,
  useSaveMappingRule,
} from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { SourceRowTable } from './source-row-table'
import { fmtCount, fmtGel, fmtText } from './format'

/** The row in hand, always the default and always first. */
const ONLY_THIS = '__ONLY_THIS__'

export function RuleScopeStep({
  row,
  payload,
  onBack,
  onDone,
}: {
  row: AuditSourceRow
  /** The mapping the editor built. Committed as-is for this row either way. */
  payload: AuditMapping
  onBack: () => void
  onDone: () => void
}) {
  const { filters, operator, categories } = useAudit()
  const [choice, setChoice] = React.useState<string>(ONLY_THIS)
  const [ruleNote, setRuleNote] = React.useState<string>(payload.note ?? '')
  const [error, setError] = React.useState<string | null>(null)
  const [expandedSample, setExpandedSample] = React.useState<string | null>(null)

  const saveMapping = useSaveMapping(operator)
  const saveRule = useSaveMappingRule(operator, {
    startDate: filters.startDate,
    endDate: filters.endDate,
  })

  const splits = payload.splits ?? []
  // A rule asserts exactly one category, so it can only be built from a mapping
  // that makes exactly one claim. A split row is a statement about this row
  // alone and is not generalisable — said out loud rather than silently hidden.
  const singleSplit = splits.length === 1 ? splits[0] : null

  const previewQuery = useMappingRulePreview(
    {
      sourceRowId: row.sourceRowId ?? '',
      startDate: filters.startDate,
      endDate: filters.endDate,
    },
    Boolean(singleSplit)
  )

  const previews = React.useMemo(
    () => (previewQuery.data ?? []).filter((preview) => Boolean(preview.criterion)),
    [previewQuery.data]
  )

  const selected =
    choice === ONLY_THIS ? null : previews.find((preview) => preview.criterion === choice) ?? null

  const category = singleSplit?.categoryCode
    ? categories.find((entry) => entry.code === singleSplit.categoryCode) ?? null
    : null

  const busy = saveMapping.isPending || saveRule.isPending

  const handleConfirm = async () => {
    setError(null)
    try {
      // 1 — the row in front of the user, always.
      await saveMapping.mutateAsync(payload)

      // 2 — the rule, only if one was explicitly chosen.
      if (selected?.criterion && singleSplit) {
        await saveRule.mutateAsync(
          buildRule(row, selected.criterion, singleSplit.categoryCode, {
            counterpartyName: singleSplit.counterpartyName,
            counterpartyTin: singleSplit.counterpartyTin,
            note: ruleNote.trim() ? ruleNote.trim() : null,
          })
        )
      }
      onDone()
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={onBack}>
          <ArrowLeft className="mr-1 h-4 w-4" />
          Back to the mapping
        </Button>
      </div>

      {/* What is about to be asserted, restated in one line. ---------------- */}
      <div className="rounded-md border border-border bg-muted/40 p-3 text-sm">
        <div className="font-medium">
          {splits.length === 0
            ? 'This save removes every allocation from the row.'
            : `Classifying ${fmtGel(row.amount)} as ${
                category?.label ?? singleSplit?.categoryCode ?? `${splits.length} splits`
              }`}
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">
          {fmtText(row.counterpartyName)} · {fmtText(row.description)}
        </p>
      </div>

      <fieldset className="space-y-2">
        <legend className="text-sm font-semibold">How far should this apply?</legend>

        <ScopeOption
          id={ONLY_THIS}
          selected={choice === ONLY_THIS}
          onSelect={() => setChoice(ONLY_THIS)}
          title="Only this transaction"
          explanation="Classifies the row in front of you and nothing else. Nothing is generalised."
        />

        {!singleSplit ? (
          <p className="rounded-md border border-border bg-muted/40 p-3 text-xs text-muted-foreground">
            {splits.length === 0
              ? 'A mapping with no allocation cannot be turned into a rule — there is no claim to repeat.'
              : `This mapping splits the row across ${fmtCount(
                  splits.length
                )} categories. A rule asserts exactly one category, so a split mapping applies to this transaction only.`}
          </p>
        ) : previewQuery.isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : previewQuery.isError ? (
          <p className="rounded-md border border-warning/60 bg-warning/10 p-3 text-xs">
            The similar-transaction preview did not answer:{' '}
            <span className="font-medium">{apiErrorMessage(previewQuery.error)}</span>. Only the
            single-transaction option is offered — widening a mapping without seeing what it would
            catch is not something this screen will do.
          </p>
        ) : previews.length === 0 ? (
          <p className="rounded-md border border-border bg-muted/40 p-3 text-xs text-muted-foreground">
            The backend found no criterion that matches another transaction in this period, so there
            is nothing to widen to.
          </p>
        ) : (
          previews.map((preview) => (
            <ScopeOption
              key={preview.criterion as string}
              id={preview.criterion as string}
              selected={choice === preview.criterion}
              onSelect={() => setChoice(preview.criterion as string)}
              title={
                preview.explanation?.trim() ||
                CRITERION_LABEL[preview.criterion as MappingRuleCriterion]
              }
              explanation={`Also maps ${fmtCount(Math.max(preview.matchCount - 1, 0))} other ${
                preview.matchCount - 1 === 1 ? 'transaction' : 'transactions'
              }, ${fmtGel(preview.matchAmount)} across all ${fmtCount(preview.matchCount)} matches.`}
              badge={CRITERION_LABEL[preview.criterion as MappingRuleCriterion]}
              warning={
                preview.alreadyMappedByPersonCount > 0
                  ? `${fmtCount(
                      preview.alreadyMappedByPersonCount
                    )} of these were classified by a person. Those keep their existing mapping — this rule will not overwrite them.`
                  : null
              }
              sample={preview.sample ?? []}
              sampleOpen={expandedSample === preview.criterion}
              onToggleSample={() =>
                setExpandedSample((current) =>
                  current === preview.criterion ? null : (preview.criterion as string)
                )
              }
            />
          ))
        )}
      </fieldset>

      {selected ? (
        <div className="rounded-md border border-primary/40 bg-primary/5 p-3">
          <Label htmlFor="rule-note" className="text-xs text-muted-foreground">
            Why this rule exists — shown on every row it maps
          </Label>
          <Input
            id="rule-note"
            value={ruleNote}
            className="mt-1 h-9"
            placeholder="e.g. all card withdrawals at the market are supplier settlement"
            onChange={(event) => setRuleNote(event.target.value)}
          />
          <p className="mt-2 text-xs text-muted-foreground">
            The rule is applied across the period selected at the top of the page ({filters.startDate}{' '}
            → {filters.endDate}) and stays live afterwards: a statement row imported later that
            matches is mapped automatically and tagged with this rule. Rows a person already
            classified are left alone, and revoking the rule un-maps everything it created.
          </p>
        </div>
      ) : null}

      {error ? <p className="text-sm text-destructive">{error}</p> : null}

      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" disabled={busy} onClick={() => void handleConfirm()}>
          {busy ? (
            <Loader2 className="mr-1 h-4 w-4 animate-spin" />
          ) : selected ? (
            <Wand2 className="mr-1 h-4 w-4" />
          ) : (
            <Check className="mr-1 h-4 w-4" />
          )}
          {busy
            ? 'Saving…'
            : selected
              ? `Map this and save the rule (${fmtCount(selected.matchCount)} rows)`
              : 'Map this transaction only'}
        </Button>
        <span className="text-xs text-muted-foreground">
          {selected
            ? 'This transaction is mapped first, then the rule is saved and applied to the rest.'
            : 'No rule is created.'}
        </span>
      </div>
    </div>
  )
}

function ScopeOption({
  id,
  selected,
  onSelect,
  title,
  explanation,
  badge,
  warning,
  sample,
  sampleOpen,
  onToggleSample,
}: {
  id: string
  selected: boolean
  onSelect: () => void
  title: string
  explanation: string
  badge?: string
  warning?: string | null
  sample?: AuditSourceRow[]
  sampleOpen?: boolean
  onToggleSample?: () => void
}) {
  return (
    <div
      className={cn(
        'rounded-md border p-3 transition-colors',
        selected ? 'border-primary bg-primary/5' : 'border-border'
      )}
    >
      <label className="flex cursor-pointer items-start gap-2">
        <input
          type="radio"
          name="mapping-scope"
          className="mt-1"
          value={id}
          checked={selected}
          onChange={onSelect}
        />
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-medium">{title}</span>
          <span className="mt-0.5 block text-xs text-muted-foreground">{explanation}</span>
          {badge ? (
            <span className="mt-1 block text-[11px] uppercase tracking-wide text-muted-foreground">
              {badge}
            </span>
          ) : null}
        </span>
      </label>

      {warning ? (
        <p className="mt-2 flex items-start gap-1.5 rounded-md border border-warning/60 bg-warning/10 p-2 text-xs">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" />
          <span>{warning}</span>
        </p>
      ) : null}

      {sample && sample.length > 0 && onToggleSample ? (
        <div className="mt-2">
          <button
            type="button"
            className="text-xs text-primary hover:underline"
            onClick={onToggleSample}
          >
            {sampleOpen ? 'Hide' : 'Show'} {fmtCount(sample.length)} example rows this would catch
          </button>
          {sampleOpen ? (
            <div className="mt-2 max-h-64 overflow-auto rounded-md border border-border">
              <SourceRowTable rows={sample} emptyMessage="The preview carried no sample rows." />
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

/**
 * The rule to save.
 *
 * The keys come from the row the user is looking at — `AuditMappingRuleService`
 * matches on the rule's own fields and resolves nothing from a row id, so every
 * key a criterion can use must be carried here. That includes the bank's own
 * `transactionType`, which is why the source-row type exposes it.
 *
 * `resolvedCounterpartyTin` is preferred over the raw one for what the mapping
 * *asserts*: it is the backend's own identification, and it is what the rule
 * writes onto each split.
 */
function buildRule(
  row: AuditSourceRow,
  criterion: MappingRuleCriterion,
  categoryCode: string | null,
  assertion: {
    counterpartyName: string | null
    counterpartyTin: string | null
    note: string | null
  }
): AuditMappingRule {
  return {
    id: null,
    criterion,
    direction: row.direction ?? null,
    counterpartyTin: row.counterpartyTin ?? null,
    counterpartyName: row.counterpartyName ?? null,
    description: row.description ?? null,
    transactionType: row.transactionType ?? null,
    categoryCode,
    mappedCounterpartyName: assertion.counterpartyName ?? row.counterpartyName ?? null,
    mappedCounterpartyTin:
      assertion.counterpartyTin ?? row.resolvedCounterpartyTin ?? row.counterpartyTin ?? null,
    note: assertion.note,
    active: true,
    appliedCount: 0,
    appliedAmount: null,
    createdBy: null,
    createdAt: null,
    updatedBy: null,
    updatedAt: null,
  }
}
