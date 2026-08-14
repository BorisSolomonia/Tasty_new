/**
 * The mapping editor in a dialog, for the sections whose primary object is a
 * list of rows rather than a working surface (Cash, Documentation).
 *
 * For a bank row the dialog is two steps: build the mapping, then decide how
 * far it applies (BOR-91). The scope step is not skippable and always opens on
 * "only this transaction" — see `rule-scope-step.tsx`.
 *
 * RS.ge and check rows go straight to the save. Rules key on bank-statement
 * facts — direction, counterparty, description, the bank's transaction type —
 * so there is no criterion to offer for a document row, and inventing a scope
 * question with one answer would only train people to click through it.
 */
import * as React from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import type { AuditMapping, AuditSourceRow } from '@/lib/audit-api'
import { MappingEditor } from './mapping-editor'
import { RuleScopeStep } from './rule-scope-step'
import { sourceRowKey } from './source-row-table'

export function MappingDialog({
  row,
  onClose,
}: {
  row: AuditSourceRow | null
  onClose: () => void
}) {
  const [proposed, setProposed] = React.useState<AuditMapping | null>(null)
  const rowKey = row ? sourceRowKey(row) : null

  // A different row means a different decision: never carry a pending scope
  // choice across rows.
  React.useEffect(() => {
    setProposed(null)
  }, [rowKey])

  const scoped = row?.sourceType === 'BANK'

  return (
    <Dialog
      open={row !== null}
      onOpenChange={(next) => {
        if (!next) {
          setProposed(null)
          onClose()
        }
      }}
    >
      <DialogContent size="wide">
        <DialogHeader>
          <DialogTitle>{proposed ? 'How far does this apply?' : 'Map source row'}</DialogTitle>
          <DialogDescription>
            {proposed
              ? 'Nothing has been saved yet. Choose the scope, then confirm.'
              : 'The source record is never modified. This writes a separate audit-layer classification.'}
          </DialogDescription>
        </DialogHeader>
        <div className="min-h-0 min-w-0 flex-1 overflow-y-auto">
          {row && proposed ? (
            <RuleScopeStep
              row={row}
              payload={proposed}
              onBack={() => setProposed(null)}
              onDone={() => {
                setProposed(null)
                onClose()
              }}
            />
          ) : row ? (
            <MappingEditor
              row={row}
              onProposeMapping={scoped ? setProposed : undefined}
              submitLabel={scoped ? 'Continue — choose scope' : undefined}
            />
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  )
}
