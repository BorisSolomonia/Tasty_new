/**
 * The reality anchor: manually confirmed real stock for one product (§4A).
 *
 * In this business real stock is almost always 0 kg — but 0 that somebody
 * confirmed and 0 that nobody has looked at are different claims, so the cell
 * says which one it is showing.
 */
import { useSaveRealInventory } from '@/hooks/use-audit-flows'
import type { AuditProductRow } from '@/lib/audit-api'
import { useAudit } from './audit-context'
import { EditableNumber } from './editable-number'

export function RealStockCell({ product }: { product: AuditProductRow }) {
  const { operator, filters } = useAudit()
  const mutation = useSaveRealInventory(operator)

  return (
    <EditableNumber
      value={product.realStockKg}
      confirmed={product.realStockConfirmed}
      suffix="kg"
      label={`Real stock for ${product.productName ?? 'product'} as of ${filters.endDate}`}
      disabled={!product.productName}
      onSave={(realKg) =>
        mutation.mutateAsync({
          id: null,
          productName: product.productName,
          asOfDate: filters.endDate,
          realKg,
          note: null,
          updatedBy: null,
          updatedAt: null,
        })
      }
    />
  )
}
