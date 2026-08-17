/**
 * "Map all rows…" on one counterparty of a bank-row statement window (BOR-92
 * v4): every DEBIT row whose own counterparty is this party, in the period,
 * without opening the party first. The rows are fetched and listed by count
 * and money in the confirmation; the write is the same explicit bulk map.
 */
import * as React from 'react'
import { Layers } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { StatementParty, StatementRowKey } from '@/lib/audit-api'
import { useStatementTransactions } from '@/hooks/use-audit-flows'
import { fmtCount } from '../format'
import { BulkMapDialog } from './bulk-map-dialog'

export function PartyBulkMap({
  row,
  startDate,
  endDate,
  party,
  canEdit,
}: {
  row: StatementRowKey
  startDate: string
  endDate: string
  party: StatementParty
  canEdit: boolean
}) {
  const [open, setOpen] = React.useState(false)
  const query = useStatementTransactions({ row, startDate, endDate, tin: party.tin, attribution: 'DIRECT' }, open)
  return (
    <>
      <Button size="sm" variant="ghost" className="h-6 px-2 text-[11px]" disabled={!canEdit} onClick={() => setOpen(true)} aria-label={`Map all rows of ${party.name}`} title="Map every row of this counterparty in the period at once">
        <Layers className="mr-1 h-3 w-3" /> Map all {fmtCount(party.directCount)}…
      </Button>
      {open && query.data ? (
        <BulkMapDialog rows={query.data} startDate={startDate} endDate={endDate} title={`all rows of ${party.name}`} onClose={() => setOpen(false)} />
      ) : null}
    </>
  )
}
