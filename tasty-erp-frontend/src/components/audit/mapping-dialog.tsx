/**
 * The mapping editor in a dialog, for the views whose primary object is a list
 * of rows rather than a working surface (Cash-First, Documentation Ledger).
 */
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import type { AuditSourceRow } from '@/lib/audit-api'
import { MappingEditor } from './mapping-editor'

export function MappingDialog({
  row,
  onClose,
}: {
  row: AuditSourceRow | null
  onClose: () => void
}) {
  return (
    <Dialog open={row !== null} onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogContent className="w-[min(52rem,96vw)]">
        <DialogHeader>
          <DialogTitle>Map source row</DialogTitle>
          <DialogDescription>
            The source record is never modified. This writes a separate audit-layer classification.
          </DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {row ? <MappingEditor row={row} /> : null}
        </div>
      </DialogContent>
    </Dialog>
  )
}
