import type * as React from 'react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import type { AuditMappingStatus } from '@/lib/audit-api'

type Variant = React.ComponentProps<typeof Badge>['variant']

const STATUS_META: Record<AuditMappingStatus, { label: string; variant: Variant; title: string }> = {
  UNMAPPED: {
    label: 'Unmapped',
    variant: 'destructive',
    title: 'No mapping exists. The row’s full amount is unresolved.',
  },
  SUGGESTED: {
    label: 'Suggested',
    variant: 'outline',
    title: 'The engine proposed a mapping that nobody has accepted. Counted in no total.',
  },
  AUTO_MAPPED: {
    label: 'Auto-mapped',
    variant: 'secondary',
    title: 'A high-confidence rule mapped this row automatically. Counted.',
  },
  PARTIALLY_MAPPED: {
    label: 'Partially mapped',
    variant: 'warning',
    title: 'Splits exist but do not cover the full source amount.',
  },
  MANUALLY_MAPPED: {
    label: 'Manually mapped',
    variant: 'success',
    title: 'A person mapped this row by hand.',
  },
  OVERRIDDEN: {
    label: 'Overridden',
    variant: 'default',
    title: 'A person replaced an automatic mapping. The prior value stays in the log.',
  },
  VOIDED: {
    label: 'Voided',
    variant: 'outline',
    title: 'The mapping was withdrawn. Retained for history; excluded from totals.',
  },
}

export function StatusBadge({
  status,
  className,
}: {
  status: AuditMappingStatus | null | undefined
  className?: string
}) {
  if (!status || !(status in STATUS_META)) {
    return (
      <Badge variant="outline" className={cn('font-medium text-muted-foreground', className)}>
        Unknown
      </Badge>
    )
  }
  const meta = STATUS_META[status]
  return (
    <Badge
      variant={meta.variant}
      title={meta.title}
      className={cn(
        'whitespace-nowrap font-medium',
        status === 'SUGGESTED' && 'border-warning text-warning',
        status === 'VOIDED' && 'text-muted-foreground line-through',
        className
      )}
    >
      {meta.label}
    </Badge>
  )
}

export function SeverityBadge({
  severity,
  className,
}: {
  severity: string | null | undefined
  className?: string
}) {
  const upper = (severity ?? '').toUpperCase()
  const variant: Variant =
    upper === 'CRITICAL' || upper === 'HIGH' ? 'destructive' : upper === 'MEDIUM' ? 'warning' : 'secondary'
  return (
    <Badge
      variant={variant}
      className={cn('whitespace-nowrap font-semibold tracking-wide', className)}
    >
      {upper || 'UNRATED'}
    </Badge>
  )
}
