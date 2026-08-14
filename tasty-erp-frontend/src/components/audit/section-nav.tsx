/**
 * The sections of the single audit page, and the links between them.
 *
 * BOR-91 replaced eight view tabs with one scroll. The rule that makes that
 * work is that a metric appears in exactly one place: `netUnexplainedPaperCash`
 * lives in the paper-cash bridge and nowhere else, the inventory totals live in
 * the inventory matrix and nowhere else. Where a section needs to acknowledge
 * another flow, it links to the figure instead of reprinting it — a second copy
 * of a number is a second thing that can be read as a separate finding.
 */
import type { ReactNode } from 'react'
import { ArrowRight } from 'lucide-react'
import { cn } from '@/lib/cn'

export interface AuditSection {
  id: string
  label: string
  hint: string
}

export const AUDIT_SECTIONS: AuditSection[] = [
  { id: 'problems', label: 'Problems', hint: 'Every rule that fired, its anatomy and where it concentrates' },
  { id: 'cash', label: 'Cash', hint: 'Bank bridge, paper-cash bridge, supplier coverage control' },
  { id: 'inventory', label: 'Inventory', hint: 'Product reconciliation matrix and confirmed real stock' },
  { id: 'documentation', label: 'Documentation', hint: 'RS.ge rows with their stock and cash consequences' },
  { id: 'workbench', label: 'Workbench', hint: 'The mapping queue and the split editor' },
  { id: 'rules', label: 'Rules & categories', hint: 'Saved mapping rules and the categories they assert' },
]

/** Jump to another section rather than repeating the figure that lives there. */
export function JumpLink({
  to,
  children,
  className,
}: {
  to: string
  children: ReactNode
  className?: string
}) {
  return (
    <a
      href={`#${to}`}
      className={cn(
        'inline-flex items-center gap-1 text-xs text-primary hover:underline',
        className
      )}
    >
      {children}
      <ArrowRight className="h-3 w-3" />
    </a>
  )
}

/**
 * A page section. `scroll-mt-28` clears the app's sticky top bar plus this
 * page's own sticky section nav, so an anchor lands on the heading rather than
 * behind it.
 */
export function PageSection({
  id,
  title,
  description,
  children,
}: {
  id: string
  title: string
  description: string
  children: ReactNode
}) {
  return (
    <section id={id} aria-labelledby={`${id}-heading`} className="scroll-mt-28 space-y-2">
      <div className="border-l-2 border-primary/60 pl-2">
        <h2 id={`${id}-heading`} className="text-sm font-bold tracking-tight">
          {title}
        </h2>
        <p className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{description}</p>
      </div>
      {children}
    </section>
  )
}
