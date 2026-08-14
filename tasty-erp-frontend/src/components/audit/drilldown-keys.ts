/**
 * The drill-down keys the backend actually resolves.
 *
 * `AuditDrilldownService.expand` switches on this exact set and throws
 * `ValidationException("Unknown drill-down key")` on anything else, so an
 * invented key is a runtime error, not a graceful miss. Typing the metric
 * components against this union turns that runtime error into a compile error.
 *
 * The rule for attaching one: a drill-down must expand the figure it is on. A
 * metric with no matching key gets no drill-down affordance at all, because
 * offering to expand "unsupported supplier documents" into "every document row"
 * would quietly change the scope under the reader — which is the precise
 * failure this module exists to make impossible.
 *
 * Keep in sync with
 * payment-service/src/main/java/ge/tastyerp/payment/audit/AuditDrilldownService.java
 */

export const DRILLDOWN_KEYS = [
  /** Money-out rows whose splits do not cover the row amount. */
  'cash.unresolvedWithdrawals',
  /** Money-in rows whose splits do not cover the row amount. */
  'cash.unmappedInflows',
  /** Bank rows carrying a split in a supplier-settlement category. */
  'cash.supplierSettlement',
  /**
   * Every imported statement row in the period, mapped or not.
   *
   * Deliberately broad, so it hangs off an explicit "browse everything" action
   * only. A problem must never open this: an alert about six rows that expands
   * into every statement row has changed the scope under the reader.
   */
  'cash.bankRows',
  /** BOR-91 — one dedicated key per alert, so every problem expands itself. */
  'cash.paidWithoutDocumentation',
  'cash.outflowWithoutCounterparty',
  'cash.supplierNeverPaid',
  'cash.checksUnclassified',
  'cash.uncoveredPurchase',
  /** Document rows in any paper-only category. */
  'cash.paperCash',
  /** Check and receipt evidence held for counterparties. */
  'cash.unsupportedChecks',
  'documentation.paperOnlySales',
  'documentation.paperOnlyReceipts',
  /** Document rows nobody has classified yet. */
  'documentation.unmapped',
  /** Every document line in the period. Broad, like `cash.bankRows`: explicit browse only. */
  'documentation.rows',
  /** Documents behind a positive gap. Accepts a product name as `subject`. */
  'inventory.positiveGap',
  /** Documents behind a negative gap. Accepts a product name as `subject`. */
  'inventory.negativeGap',
] as const

export type DrilldownKey = (typeof DRILLDOWN_KEYS)[number]

export function isKnownDrilldownKey(key: string | null | undefined): key is DrilldownKey {
  return typeof key === 'string' && (DRILLDOWN_KEYS as readonly string[]).includes(key)
}

/**
 * The gap drill-downs are split by sign, so the key follows the figure. An
 * absent gap gets no key — there is nothing to expand and nothing to guess.
 */
export function inventoryGapKey(gapKg: number | null | undefined): DrilldownKey | undefined {
  if (typeof gapKg !== 'number' || !Number.isFinite(gapKg)) return undefined
  return gapKg < 0 ? 'inventory.negativeGap' : 'inventory.positiveGap'
}
