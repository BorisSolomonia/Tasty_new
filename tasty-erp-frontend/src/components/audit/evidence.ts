/**
 * Where a drill-down's rows belong on the page, and how that survives a reload.
 *
 * A problem's evidence used to open in a dialog on top of the page. A dialog is
 * a dead end: the reader sees the rows, closes it, and is back where they
 * started with no idea which part of the page owns them. So evidence is now
 * rendered *in* the section that browses that kind of row, and the button
 * scrolls there — the reader ends up somewhere they can keep working.
 *
 * The mapping is by row kind, not by which flow raised the alert:
 *
 *   documentation.*  → the documentation section, which lists RS.ge rows
 *   inventory.*      → the inventory section, which owns the product matrix
 *   cash.* and rest  → the workbench, the page's general source-row browser
 *
 * `cash.paperCash` expands into document rows and still routes to the
 * workbench: the workbench browses every source row, so nothing is shown
 * somewhere it cannot be read.
 */
import { isKnownDrilldownKey, type DrilldownKey } from './drilldown-keys'

/** Section ids from `AUDIT_SECTIONS` that can host an evidence panel. */
export const EVIDENCE_SECTIONS = ['workbench', 'documentation', 'inventory'] as const
export type EvidenceSection = (typeof EVIDENCE_SECTIONS)[number]

export function sectionForDrilldownKey(key: string): EvidenceSection {
  if (key.startsWith('documentation.')) return 'documentation'
  if (key.startsWith('inventory.')) return 'inventory'
  return 'workbench'
}

const HASH_PREFIX = 'evidence='

/**
 * The active evidence in the URL, so a reload lands on the same rows.
 *
 * Only the key and subject are carried. The human label is not: on a reload the
 * drill-down response supplies its own `label`, and a stale title pasted from a
 * URL could name a different finding than the rows underneath it.
 */
export function evidenceHash(key: string, subject?: string): string {
  const parts = [`${HASH_PREFIX}${encodeURIComponent(key)}`]
  if (subject) parts.push(`subject=${encodeURIComponent(subject)}`)
  return `#${parts.join('&')}`
}

export function parseEvidenceHash(
  hash: string
): { key: DrilldownKey; subject?: string } | null {
  const raw = hash.replace(/^#/, '')
  if (!raw.startsWith(HASH_PREFIX)) return null

  const params = new URLSearchParams(raw)
  const key = params.get('evidence')
  // An unknown key would fail server-side as "Unknown drill-down key"; a
  // hand-edited URL therefore restores nothing rather than a broken panel.
  if (!isKnownDrilldownKey(key)) return null

  const subject = params.get('subject')
  return { key, subject: subject ?? undefined }
}
