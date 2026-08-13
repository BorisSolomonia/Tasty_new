/**
 * The self-declared operator name (decision D-4).
 *
 * The audit API has no authentication, so this is a recorded claim and nothing
 * more. The disclaimer below is not decoration — it is the only thing standing
 * between a change log and a reader who mistakes it for an audit trail of
 * authenticated users.
 */
import * as React from 'react'
import { UserRound } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/cn'
import { useAudit } from './audit-context'

export function OperatorPicker({ className }: { className?: string }) {
  const { operator, setOperator, operatorHistory } = useAudit()
  const [draft, setDraft] = React.useState(operator)

  React.useEffect(() => {
    setDraft(operator)
  }, [operator])

  /**
   * Commit as the operator types, not only on blur. Committing on blur alone
   * left the name uncommitted whenever the next thing clicked was a disabled
   * control — which never takes focus, so no blur ever fires — and the operator
   * could not tell why nothing unlocked.
   */
  const commit = (next: string) => {
    if (next.trim() !== operator) setOperator(next)
  }

  return (
    <div className={cn('min-w-[13rem]', className)}>
      <div className="relative">
        <UserRound className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={draft}
          list="audit-operator-history"
          placeholder="Operator name"
          aria-label="Operator name recorded on every change"
          className={cn('h-9 pl-8', !operator && 'border-warning')}
          onChange={(event) => {
            setDraft(event.target.value)
            commit(event.target.value)
          }}
          onBlur={() => commit(draft)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.currentTarget.blur()
            }
          }}
        />
        <datalist id="audit-operator-history">
          {operatorHistory.map((name) => (
            <option key={name} value={name} />
          ))}
        </datalist>
      </div>
      <p className="mt-1 text-[11px] leading-snug text-muted-foreground">
        Self-declared name, not an authenticated identity. It is recorded with every change you make.
      </p>
    </div>
  )
}

/** Guard text shown wherever a mutation is blocked for want of a name. */
export function useOperatorGuard() {
  const { operator } = useAudit()
  return {
    operator,
    ready: operator.trim().length > 0,
    message: 'Enter an operator name in the page header before saving.',
  }
}
