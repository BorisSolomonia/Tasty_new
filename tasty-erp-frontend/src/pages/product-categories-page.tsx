import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { startOfMonth } from 'date-fns'
import { auditApi, configApi } from '@/lib/api-client'
import { formatDateISO } from '@/lib/utils'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { ProductCatalog, ProductCatalogRow, ProductCategoryCode } from '@/types/domain'

const CATEGORY_OPTIONS: { value: ProductCategoryCode; label: string }[] = [
  { value: 'BEEF', label: '🐄 Beef' },
  { value: 'PORK', label: '🐷 Pork' },
  { value: 'FAT', label: 'Fat' },
  { value: 'OTHER', label: 'Other' },
]

export function ProductCategoriesPage() {
  const queryClient = useQueryClient()
  const [startDate, setStartDate] = React.useState(formatDateISO(startOfMonth(new Date())))
  const [endDate, setEndDate] = React.useState(formatDateISO(new Date()))
  const [applied, setApplied] = React.useState<{ startDate: string; endDate: string } | null>(null)
  const [savingName, setSavingName] = React.useState<string | null>(null)

  const catalogKey = React.useMemo(
    () => ['product-catalog', applied?.startDate, applied?.endDate] as const,
    [applied]
  )

  const catalogQuery = useQuery({
    queryKey: catalogKey,
    queryFn: () => auditApi.getProductCatalog({ startDate: applied!.startDate, endDate: applied!.endDate }),
    enabled: applied !== null,
    staleTime: 5 * 60 * 1000,
    retry: 1,
  })

  // Apply the new category to every row with the same name in the local cache
  // (one category per name -> reflect it in both purchased and sold lists) and
  // mark the audit dashboard stale so it picks up the change.
  const patchLocalCatalog = (name: string, category: ProductCategoryCode, overridden: boolean) => {
    queryClient.setQueryData<ProductCatalog>(catalogKey, (old) => {
      if (!old) return old
      const patch = (rows: ProductCatalogRow[]) =>
        rows.map((r) => (r.name === name ? { ...r, category, overridden } : r))
      return { purchased: patch(old.purchased), sold: patch(old.sold) }
    })
    queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] })
  }

  const setCategory = useMutation({
    mutationFn: (entry: { name: string; category: ProductCategoryCode }) =>
      configApi.setProductCategory(entry),
    onMutate: ({ name }) => setSavingName(name),
    onSuccess: (_data, entry) => patchLocalCatalog(entry.name, entry.category, true),
    onSettled: () => setSavingName(null),
  })

  const revert = useMutation({
    mutationFn: (name: string) => configApi.deleteProductCategory(name),
    onMutate: (name) => setSavingName(name),
    // Auto category is server-derived; refetch the catalog to show the reverted value.
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: catalogKey })
      queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] })
    },
    onSettled: () => setSavingName(null),
  })

  const catalog = catalogQuery.data

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight md:text-2xl">Product categories</h1>
        <p className="text-sm text-muted-foreground">
          Assign each purchased and sold product to a category. Changes drive the Audit Control
          inventory (one category per product name — editing it here applies everywhere).
        </p>
      </div>

      <Card className="p-3 md:p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">From</label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="rounded-md border bg-background px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">To</label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              className="rounded-md border bg-background px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <Button onClick={() => setApplied({ startDate, endDate })} disabled={catalogQuery.isFetching} className="shrink-0">
            {catalogQuery.isFetching ? 'Loading…' : 'Load products'}
          </Button>
        </div>
      </Card>

      {applied === null && (
        <Card className="p-8 text-center text-sm text-muted-foreground">
          Pick a date range and load the products seen on waybills.
        </Card>
      )}

      {catalogQuery.isError && (
        <Card className="border-destructive p-4">
          <div className="text-sm font-medium text-destructive">Failed to load products</div>
          <div className="mt-1 text-xs text-muted-foreground">
            {catalogQuery.error instanceof Error ? catalogQuery.error.message : 'Unknown error'}
          </div>
        </Card>
      )}

      {applied !== null && !catalogQuery.isError && (
        <div className="grid gap-4 lg:grid-cols-2">
          <CategoryTable
            title="Purchased products"
            rows={catalog?.purchased}
            loading={catalogQuery.isFetching}
            savingName={savingName}
            onSetCategory={(name, category) => setCategory.mutate({ name, category })}
            onRevert={(name) => revert.mutate(name)}
          />
          <CategoryTable
            title="Sold products"
            rows={catalog?.sold}
            loading={catalogQuery.isFetching}
            savingName={savingName}
            onSetCategory={(name, category) => setCategory.mutate({ name, category })}
            onRevert={(name) => revert.mutate(name)}
          />
        </div>
      )}
    </div>
  )
}

function CategoryTable({
  title,
  rows,
  loading,
  savingName,
  onSetCategory,
  onRevert,
}: {
  title: string
  rows: ProductCatalogRow[] | undefined
  loading: boolean
  savingName: string | null
  onSetCategory: (name: string, category: ProductCategoryCode) => void
  onRevert: (name: string) => void
}) {
  return (
    <Card className="overflow-hidden">
      <div className="flex items-center justify-between border-b px-3 py-2">
        <span className="text-sm font-medium">{title}</span>
        <span className="text-xs text-muted-foreground">{rows?.length ?? 0} unique</span>
      </div>
      <div className="max-h-[70vh] overflow-auto">
        <table className="w-full text-sm">
          <thead className="sticky top-0 border-b bg-muted/40">
            <tr>
              <th className="w-8 px-3 py-2 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">#</th>
              <th className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">Product</th>
              <th className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">Category</th>
            </tr>
          </thead>
          <tbody>
            {loading
              ? Array.from({ length: 8 }).map((_, i) => (
                  <tr key={i} className="border-b">
                    <td className="px-3 py-2 text-right text-muted-foreground">{i + 1}</td>
                    <td className="px-3 py-2"><Skeleton className="h-4 w-48" /></td>
                    <td className="px-3 py-2"><Skeleton className="h-7 w-32" /></td>
                  </tr>
                ))
              : (rows ?? []).map((row, i) => (
                  <tr key={row.name} className="border-b last:border-0 hover:bg-muted/30">
                    <td className="px-3 py-2 text-right text-muted-foreground">{i + 1}</td>
                    <td className="px-3 py-2 max-w-[280px] truncate" title={row.name}>
                      {row.name}
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex items-center gap-2">
                        <select
                          value={row.category}
                          disabled={savingName === row.name}
                          onChange={(e) => onSetCategory(row.name, e.target.value as ProductCategoryCode)}
                          className="rounded-md border bg-background px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
                        >
                          {CATEGORY_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                          ))}
                        </select>
                        {row.overridden ? (
                          <button
                            type="button"
                            disabled={savingName === row.name}
                            onClick={() => onRevert(row.name)}
                            title="Revert to automatic category"
                            className="rounded px-1.5 py-0.5 text-xs text-amber-600 hover:bg-amber-500/10 disabled:opacity-50"
                          >
                            custom ✕
                          </button>
                        ) : (
                          <span className="text-xs text-muted-foreground">auto</span>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
            {!loading && (rows?.length ?? 0) === 0 && (
              <tr>
                <td colSpan={3} className="px-3 py-8 text-center text-sm text-muted-foreground">
                  No products in this range.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
