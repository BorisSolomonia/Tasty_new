# /audit-control — analysis and redesign study (BOR-87, 2026-08-15)

Companion files (standalone, mock data, open directly in a browser, no backend):

- `BOR-87-audit-control-concept-1-executive.html` — **Executive control tower** · [view](https://claude.ai/code/artifact/7d2f229d-79ab-4948-af55-85194ceda0a9)
- `BOR-87-audit-control-concept-2-workspace.html` — **Analytical workspace** · [view](https://claude.ai/code/artifact/7a30fb27-072f-40ab-adc2-4a188a0a20c2)
- `BOR-87-audit-control-concept-3-control-center.html` — **Alert-first control center** · [view](https://claude.ai/code/artifact/ef9d23a4-83d0-4aff-a402-1819f64d6e69)

Chosen by the owner: page `/audit-control`, **3** concepts.

---

## 1. Page and application type

**Application.** Tasty ERP — a meat-distribution back office for the Georgian
market. Three Spring Boot services over Firestore; RS.ge (the tax authority's
e-waybill service) is the documented source of truth for purchases and sales;
bank statements (Excel/TBC DBI) are the money source; operators add "reality"
by hand (write-off %, real prices/kg, real vs unreal customers, formal
commission customers).

**Page.** `/audit-control` (`tasty-erp-frontend/src/pages/audit-control-page.tsx`,
1 453 lines, 26 components in one file; backend `AuditControlService`,
`DualLedgerService`, `WriteOffCalculator`). It is the **dual-ledger** view:
*documented* (what RS.ge says) against *real* (what the operator asserts), per
meat category, for a chosen period — plus VAT, supplies, and per-customer debt
reconciliation.

**Users.** The owner/auditor (Boris) and at most a couple of finance operators.
Used to answer, for a month or a quarter:

1. How much did documented purchases and sales diverge from real ones, per
   category, in kg and in ₾ — and what does that do to VAT?
2. Is inventory conserved — does what we bought minus write-off cover what we
   sold, day by day (an *overage* day is a day we sold more than we could have
   had)?
3. Who owes us real money vs. paper money (unreal / formal customers)?
4. What is the actual VAT liability and what would it be at a different
   write-off rate?

**Decisions it supports.** Set the write-off % per category; mark a customer
unreal/formal/paid; enter real purchase kg/price and real sale price; decide
which VAT figure to file; chase specific debtors; investigate specific overage
days.

## 2. Information inventory — what is on the page today, and how

| # | Item | Where | Presented as | Assessment |
|---|---|---|---|---|
| 1 | Period + product filter, Apply, Export ledger | FilterBar | 2 date inputs, select, buttons | Fine. "Apply" staging is right for a 5–20 s query. Missing: presets (this month / last month / quarter), and the range is not echoed in the page title. |
| 2 | Real Total Sales / Real Total Purchases / Real Debt / Exception Debt | 4 StatCards | number + one-line hint | Headline is right; **no context** — no prior period, no documented counterpart, no share. "Excluded" figures are hidden in 11 px hints. |
| 3 | Per-category card header: purchased kg, sold kg, VAT diff, on-hand kg | accordion header, 7 collapsed rows | inline text | The seven headers are the *only* cross-category comparison on the page and they are text, so nothing can be compared by eye. |
| 4 | Purchase window (10 rows) | inside card | label/value list, 3 editable | Correct data, but doc and real figures are interleaved vertically, so the *gap* — the whole point — must be computed in the head. |
| 5 | Sales window (8 rows) | inside card | label/value list | Same. "Sales Real kg (−unreal −formal)" is a small breakdown crammed into a value cell. |
| 6 | On-hand footer | inside card | formula sentence + number | Honest formula text (good). But `startingInventoryKg` is **hard-coded 0** in `DualLedgerService`, so "On hand" is really the period's *net movement*, not stock — the label misleads. |
| 7 | Daily ledger rows | inside card, behind "Show daily rows" | 8-column table, red rows for overage | The richest dataset on the page (a daily time series per category) is a hidden table. Overage — the alarm — is one word in the last column. |
| 8 | Reconciliation | tab 2, table | 9 columns, 3 toggles per row, capped at 200 rows | Right data, but no ranking cue, no concentration, and the cap is undisclosed. Real vs exception debt cannot be compared without reading two columns. |
| 9 | Formal commission | tab 2, table | table + add form | Fine as a management table. Commission AR is the number that matters and it is the 5th column. |
| 10 | Targeted expense | tab 2 | hero number + 50-row table | 50 of N shown under a total of N (rows visibly don't sum). |
| 11 | Exceptions | tab 2 | list + add form | Fine. |
| 12 | VAT | tab 3, table | 9–10 columns, total footer | The decision — actual vs projected payable — sits in columns 6 and 10 of a wide table. Input vs output is a bridge, shown as two columns. |
| 13 | Supplies | tab 4, table | 4 columns | Fine. Should say it is *excluded* from the meat math right at the top (it does, in prose). |
| — | `purchaseShortages` / `saleSurpluses` cash gaps, `totalPurchaseShortage`, `totalSaleSurplus` | in the `DualLedger` payload | **not rendered** | The backend computes the per-category ₾ gap (doc − real) and the page dropped it in the BOR-79 consolidation. This is the single most decision-relevant number and it is invisible. |

**Charts today: zero.** Every dataset is a table or a label/value list.

### 2.1 Deep data analysis — what each dataset really is

| Dataset | Shape | What a reader must do with it | Implication for form |
|---|---|---|---|
| Doc vs real, per category (kg, price, ₾, VAT diff) | 7 categories × 2 states × 3 measures | compare two values per item and read the gap | *before → after per item* → **dumbbell / connected dot**, one hue two shades; the gap labelled |
| Daily inventory ledger | 7 categories × 31 days × (start, +purchases, −sold, −write-off, end, overage flag) | see whether stock ever goes negative and on which days | *change over time* → **step-area / line per category as small multiples**; overage days as marks; alternatively a category × day **heatmap** of write-off % |
| Inventory identity (opening + purchases − write-off − sales = end) | one additive chain per category | understand where kg went | **waterfall / bridge** (IBCS) |
| VAT (output − input = payable; projected at write-off) | 7 × 3 | decide which figure to file and see the delta | **waterfall per category** or dumbbell actual→projected; the total is a **hero figure** |
| Reconciliation debt | up to 200 customers × (sales, payments, real debt, exception debt) | find who to chase; see concentration | ranked **bar-in-table** + **Pareto** (cumulative share) |
| Real totals | 4 numbers | headline | **stat tiles**, each with a bullet against its documented counterpart |
| Supplies, commissions, exceptions | small lists | manage | tables (right as they are) |

## 3. UX and visual-design review

- **Hierarchy.** Four equal tiles, then seven identical collapsed rows. Nothing says "look here first"; the alarms (overage days, negative on-hand, VAT difference) are the same 12 px muted text as everything else. Few's rule for a monitoring display — the most important thing must be visible in one glance — is not met.
- **Comparison.** Doc and real are never side by side; the seven categories are never on one axis. The page asks the reader to do subtraction and to remember values across accordions.
- **Alarms.** Overage days appear only after two clicks (open card → show daily rows). Negative on-hand is a red number in a collapsed header.
- **Truthfulness.** "On hand" ignores opening stock (hard-coded 0). The reconciliation table silently caps at 200; targeted-expense shows 50 of N.
- **Density and a11y.** Editable numbers are 28 px inputs; three checkboxes per reconciliation row have `aria-label`s (good) but no visible label; column headers are not sortable; the wide VAT table is fine on desktop and unreadable on a phone (it scrolls in its own container — the CLAUDE.md rule holds).
- **Consistency.** This page and `/audit` were built by different tickets and share little visual language (metric rows vs label/value lists; two different `EditableNumber`s).

## 4. Evidence-based information priorities

Sources used (they directly shape the recommendations):

- Few, *Information Dashboard Design* — dashboards are for at-a-glance
  monitoring; bullet graphs replace gauges; sparklines carry trend in small
  space; hierarchy by importance, alarms first.
- Cleveland & McGill (1984), *Graphical Perception* — position on a common
  scale > length > angle/slope > area > shading. This is why the study uses
  dumbbells and bars, not gauges or pies, and why the doc/real gap is shown as
  two positions on one axis.
- IBCS / ISO 24896 (SUCCESS rules; chart templates for AC vs PL and variance
  bridges) — solid = actual, outline = plan/paper; variances as bridges
  (waterfalls); one notation across every chart.
- The bundled data-viz method: one axis (never dual), sequential = one hue,
  status colours reserved and never colour-alone, ≥ 2 series → legend, thin
  marks, table view always available.
- WCAG 2.2 1.4.1 (use of colour), 1.4.3 (contrast), 2.5.8 (target size).
- The project's own binding rules (CLAUDE.md): never present a figure whose
  inputs are missing as if it were zero risk; say how a value was
  established; drill-downs show exactly the causing records.

Priorities that follow:

| Priority | Information | Why |
|---|---|---|
| **Primary** | Doc vs real gap in ₾ per category (`totalPurchaseShortage`, `totalSaleSurplus`, `vatDifference`) | It is the purpose of a dual ledger and it is currently invisible |
| **Primary** | Inventory conservation: overage days and negative net position, per category | The only hard alarm the data can raise |
| **Primary** | VAT payable actual vs projected (headline) | A filing decision with a number attached |
| **Secondary** | Real sales / real purchases / real debt with documented counterparts and share excluded | Headline context, not the decision |
| **Secondary** | Reconciliation ranked by real debt, with concentration | Who to chase |
| **Drill-down** | Daily ledger, purchase/sales windows, VAT columns, supplies lines, commission table, targeted expense matches | Evidence for the figures above |
| **Merge** | The seven accordion headers → one comparative chart; Purchase/Sales windows → one doc\|real table with a gap column | Removes head-arithmetic |
| **Change** | "On hand" → "Net movement (no opening stock recorded)" until a physical-stock source exists | Truthfulness |
| **Add** | Period presets; disclosure of caps ("200 of 312 shown"); alarm strip; cash-gap series | — |
| **Remove** | Nothing — every current figure remains reachable one level down | — |

## 5. Chart study — every important dataset, current vs recommended

| Dataset | Current | Appropriate? | Recommended | Alternatives and trade-offs |
|---|---|---|---|---|
| Real totals (4 KPIs) | number tiles | partly — a number is right, context is missing | **Stat tile + bullet**: bar = real, tick = documented, faint band = prior period | Sparkline of monthly real sales (needs history the API doesn't return yet — mark illustrative) |
| Doc vs real per category (kg, ₾) | interleaved label/value rows | no — gap requires arithmetic | **Dumbbell** per category on a common ₾ axis; hollow = documented, solid = real; gap labelled | Paired bars (more ink, weaker for the *difference*); slopegraph doc→real (best when many items move in different directions — used in Concept 3) |
| Daily ledger | hidden table | no — the alarm is buried | **Small-multiple step-area** of ending kg per category, overage days as marks, hover for the row | Category × day **heatmap** of write-off % (Concept 3) — better for spotting *which days* across all categories at once, worse for magnitude |
| Inventory identity | formula sentence + number | honest but not visual | **Waterfall**: purchases → −write-off → −sales → net; opening shown as a dashed "not recorded" step | Stacked bar (loses the sequence); keep the sentence as the tooltip |
| VAT | 10-column table | no — the decision is two cells | **Hero figure** (total payable) + per-category **bridge** output − input = payable with a marker for projected | Dumbbell actual→projected only; keep the table as drill-down |
| Reconciliation | table | partly | Table kept, plus **bar-in-cell** for real debt (sortable, `aria-sort`) and a **Pareto** of cumulative debt share above it | Treemap (area is the least accurate encoding — rejected) |
| Cash gap (unrendered) | — | — | **Diverging bar** per category, zero at centre: purchase shortage vs sales surplus | Two ranked bars |
| Commission / supplies / exceptions | tables | yes | keep; commission AR first column | — |

## 6. Improved information architecture (common to all three concepts)

1. **Header**: period (with presets), product filter, Apply, Export — plus a one-line data-honesty note when inputs are missing (opening stock, unmapped supplies).
2. **Alarm strip**: overage days, negative net position, VAT differences, unresolved exceptions — each a chip with a count and a jump link (Concept 3 makes this the whole top).
3. **Headline**: real sales / purchases / debt with documented counterparts (bullets), and total VAT payable actual → projected.
4. **The dual-ledger picture**: one comparative chart across categories (dumbbell / slopegraph / diverging gap).
5. **Inventory conservation**: small multiples or heatmap of the daily ledger; waterfall on demand per category.
6. **Money**: reconciliation ranked with concentration; commission; targeted expense with disclosed caps.
7. **Drill-downs**: the existing purchase/sales windows, daily table, VAT table, supplies — each reachable from the chart element that summarises it.

## 7. The three concepts

| | Concept 1 — Executive control tower | Concept 2 — Analytical workspace | Concept 3 — Alert-first control center |
|---|---|---|---|
| Shell | single column, summary → detail | two columns: category rail + deep panel for the selected category | ranked queue on top, evidence below |
| Chart strategy | bullets (KPIs) · **dumbbell** doc↔real per category · **small-multiple sparklines** of daily net kg with overage marks · **Pareto** of receivables | **waterfall** inventory identity · **step-area** daily ledger with overage band · doc\|real **table with inline delta bars** · **VAT bridge** waterfall · bar-in-table reconciliation | **category × day heatmap** of write-off % / overage · **slopegraph** doc→real (kg and ₾) · **diverging cash-gap bars** · ranked debtor bars |
| Best for | the monthly owner review — one screen, what changed, where the money is | the operator working one category at a time — enter reality, watch the ledger respond | the auditor hunting problems — start from the exception, go to the evidence |
| Weakest at | editing (edits are one level down) | cross-category overview (rail shows only mini bars) | headline totals (deliberately demoted) |

## 8. Recommendation

Adopt **Concept 3's alarm strip and heatmap** as the top of the page, **Concept 1's
dumbbell** as the one cross-category chart, and **Concept 2's per-category
panel** (waterfall + step-area + doc|real table with delta bars) as the
drill-down that opens when a category is selected. Keep every existing table
one level down as evidence. Concretely, in the current codebase:

1. Split `audit-control-page.tsx` by section (`features/audit-control/…`) — the
   BOR-82 audit already flags the file (26 components).
2. Render `purchaseShortages`/`saleSurpluses` (already in the payload) as the
   diverging cash-gap bars; relabel "On hand" until opening stock exists.
3. Charts as inline SVG components (no chart library needed at this size; the
   concepts are the proof), colours from a validated palette, table view kept
   for every chart.
4. Add period presets and cap disclosures.

Everything in the concepts is mock data; where the API has no history (prior
period, monthly trend) the concept says "illustrative" on the element itself.

### Notes on the build

- Charts are inline SVG generated from one deterministic mock dataset (seeded
  PRNG), so all three concepts show the same August: Beef with two overage
  days and real purchases above documented, Sheep with one overage day, the
  rest conserved.
- Colour: documented = hollow/light blue, real = solid/dark blue (one hue, two
  shades). Light-mode pair validated with the data-viz palette script
  (ΔE 28.5 normal, 28.8 protan; the light shade is under 3:1 on the light
  surface, so every chart ships a table view and direct labels — the "relief"
  rule). Dark-mode pair `#86b6ef` / `#3987e5` passes separation and contrast;
  the light shade sits just above the dark lightness band, accepted because
  *documented* is form-encoded (ring/outline), never colour-alone. Status
  colours (critical / warning / good) are reserved and always paired with a
  word or icon.
- Verified in jsdom: no script errors, every SVG populated, table views present.
  Visual review in a browser is still the last step before adopting a concept.
