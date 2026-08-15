/** BOR-82 finding FE-3: RuleBadge is presentational; the rule lookup is a passed-in index. */
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RuleBadge } from './rule-badge'
import type { AuditMapping, AuditMappingRule } from '@/lib/audit-api'

const mapping = { appliedByRuleId: 'rule-abcdef123' } as unknown as AuditMapping

describe('RuleBadge', () => {
  it('renders nothing for a mapping no rule produced', () => {
    const { container } = render(<RuleBadge mapping={{} as AuditMapping} rulesById={new Map()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('names the rule from the index (note wins over criterion)', () => {
    const rules = new Map<string, AuditMappingRule>([
      ['rule-abcdef123', { id: 'rule-abcdef123', criterion: 'COUNTERPARTY', note: 'Meat suppliers' } as AuditMappingRule],
    ])
    render(<RuleBadge mapping={mapping} rulesById={rules} />)
    expect(screen.getByText(/by rule: Meat suppliers/)).toBeInTheDocument()
  })

  it('falls back to the bare id when the rule list is unavailable, and says so', () => {
    render(<RuleBadge mapping={mapping} rulesById={null} />)
    expect(screen.getByText(/by rule: rule rule-abc/)).toBeInTheDocument()
    expect(screen.getByTitle(/could not be loaded/)).toBeInTheDocument()
  })
})
