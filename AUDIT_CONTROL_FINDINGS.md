# BOR-89 — Current Implementation Findings

Mandatory pre-coding inspection note required by BOR-89 §2. Written **before** any
audit-layer code was added. Everything below was verified by reading the
repository, not inferred from the ticket.

Date: 2026-08-13 · Repo: `Tasty_erp_new` @ `master`

---

## 0. Headline corrections to the ticket

The ticket makes five assumptions the repository contradicts. They matter because
each one changes what gets built.

| Ticket says | Repository reality |
|---|---|
| Extend the "current `/audit-control` implementation" | It is already a substantial feature (BOR-74 / BOR-76 / BOR-79): 7 endpoints, `AuditControlService` (31 KB), `DualLedgerService` (33 KB), 16 DTOs, a 1,453-line page |
| "existing 33% write-off implementation" | The rate is **28%**, not 33% (`WriteOffCalculator.DEFAULT_WRITE_OFF_PERCENT = 28`), kg-based, editable **per category** |
| "database migrations/schema" | There is **no relational database and no migrations**. Persistence is **Firestore**; collections appear on first write |
| Decide sign convention from the statement | There is **no sign convention** — money in and money out are **two separate columns** |
| Bank rows exist and need mapping | **Outflows are not imported at all.** See §3 — this is the blocking gap |

---

## 1. Stack and module layout

- Spring Boot 3.2.1 / Java 17, Maven multi-module: `common`, `config-service`,
  `waybill-service`, `payment-service` (`api-gateway` exists but is not in the
  reactor `<modules>` list).
- Persistence: **Google Firestore** (`com.google.cloud.firestore`). No SQL, no
  Flyway, no schema migrations.
- Frontend: Vite + React, **TanStack Router** + **TanStack React Query**,
  Tailwind with shadcn-style primitives. Available UI primitives are only
  `badge, button, card, input, label, separator, skeleton, tabs, toast` — there
  is **no table component and no chart library**.
- Inter-service calls are plain HTTP via a `RestTemplate` (`waybillServiceUrl`,
  `configServiceUrl`), not a shared database.
- No Linux JVM on the dev box: Maven runs through `cmd.exe /c "mvn ..."`.

---

## 2. Existing `/audit-control` (BOR-74 / 76 / 79)

`payment-service/.../controller/AuditControlController.java` — base path
`/api/audit-control`:

| Method | Path | Returns |
|---|---|---|
| GET | `/dashboard` | `AuditDashboardDto` (startDate, endDate, product) |
| GET | `/dual-ledger` | `DualLedgerDto` (startDate, endDate, product) |
| GET | `/product-catalog` | `ProductCatalogDto` |
| GET | `/targeted-expense` | `TargetedExpenseDto` |
| GET | `/exceptions` | `List<AuditExceptionDto>` |
| POST | `/exceptions` | create/update an exception |
| DELETE | `/exceptions/{id}` | delete an exception |
| PUT | `/reconciliation/{key}/paid` | manual mark-paid override |

`DualLedgerDto` already models a **document-vs-real** ("shadow cash flow")
position: `categoryCards`, `purchaseShortages`, `saleSurpluses`,
`formalCommissions`, `vat`, `supplies`, plus grand totals. BOR-89's "paper vs
real" language is a **superset of this**, not a replacement — the new layer must
sit around it, not re-derive it.

`AuditControlService` reads:
- product movements over HTTP from waybill-service,
- real-entity flags, category overrides, write-off rates, unreal customers and
  per-product VAT from config-service,
- payments and overrides from Firestore.

### Write-off

`WriteOffCalculator` (kg-based, "possible write-off"):

```
base          = startingInventory + purchased
posibWriteOff = purchased * rate            (rate default 0.28, editable per category)
ending        = base - sold - posibWriteOff
overage       = sold > base || ending < 0
```

Plus a `passthroughDay(...)` for non-write-off categories (OTHER/Unclassified)
where `ending = starting + purchased - sold`. **Reuse this — do not write a second
write-off model.**

### Existing manual-override / audit infrastructure

Partial, and scattered rather than unified:
- `PaymentOverrideRepository` — manual mark-paid overrides with a note.
- `AuditExceptionRepository` — tracked reconciliation exceptions (CRUD).
- config-service holds editable per-category write-off %, per-product VAT,
  category overrides, unreal-customer flags, real-entity flags.

**There is no generic change log** (old value / new value / actor / timestamp)
anywhere. BOR-89 §12 requires one; it must be built.

---

## 3. Bank statement import — the blocking gap

`payment-service/.../service/ExcelProcessingService.java`.

- Accepts a `bank` parameter, validated against `List.of("tbc", "bog")`, so a
  two-bank strategy already exists. It differs **only by sheet index**:
  `"tbc" -> sheet 1, "bog" -> sheet 0`. The column map is shared.
- Fixed column indices:

```java
COL_DATE        = 0   // A
COL_DESCRIPTION = 1   // B
COL_AMOUNT      = 4   // E   <-- "Paid In" ONLY
COL_BALANCE     = 5   // F
COL_CUSTOMER_ID = 11  // L   <-- Partner's Tax Code
```

**`COL_AMOUNT = 4` is column E, "შემოსული თანხა / Paid In". Column D,
"გასული თანხა / Paid Out", is never read.**

Consequence: the ERP contains **only customer receipts**. Cash withdrawals,
supplier transfers, bank fees, refunds and reversals have never entered the
system. BOR-89's entire Cash flow — withdrawal allocation, supplier settlement
coverage, unresolved cash, paper-vs-real cash — has **no data source** until this
is fixed. It is therefore the first implementation step, not a late one.

A second parser exists for manual cash: `ManualCashExcelImportService`
(`/api/payments/manual-cash/upload`).

---

## 4. Verified real statement schema

Source: `example/example.xlsx` (a Bank of Georgia export). **Two header rows** —
row 1 Georgian, row 2 English — data begins at **row 3**. Dates are Excel
serials. 244 data rows.

| Col | Georgian | English |
|---|---|---|
| A | თარიღი | Date |
| B | დანიშნულება | Description |
| C | დამატებითი ინფორმაცია | Additional Information |
| **D** | **გასული თანხა** | **Paid Out** |
| **E** | **შემოსული თანხა** | **Paid In** |
| F | ნაშთი | Balance |
| G | ტრანზაქციის ტიპი | Type |
| H | საბუთის თარიღი | Document Date |
| I | საბუთის № | Document Number |
| J | პარტნიორის ანგარიში | Partner's Account |
| K | პარტნიორი | Partner's Name |
| **L** | **პარტნიორის საგადასახადო კოდი** | **Partner's Tax Code** |
| M / N | პარტნიორის ბანკის კოდი / ბანკი | Partner's Bank Code / Bank |
| O / P | შუამავალი ბანკის კოდი / ბანკი | Intermediary Bank Code / Bank |
| Q | ხარჯის ტიპი | Charge Details |
| R / S | გადასახადის გადამხდელის კოდი / დასახელება | Taxpayer Code / Name |
| T | სახაზინო კოდი | Treasury Code |
| U | ოპ. კოდი | Op. Code |
| V | დამატებითი დანიშნულება | Additional Description |
| W | ტრანზაქციის ID | Transaction ID |

**Direction is carried by which of D/E is populated — never by a sign.** In the
sample, no row populates both.

Observed value vocabulary in this file:

- `G` Type: `შემოსავალი` (income) — 244/244
- `U` Op. Code: `BULK` (209), `GIB` (35)
- `B` Description: `საქონლის ღირებულება` (232), `პროდუქციის საფასური` (12)
- `K` Partner: `შპს მაგსი` only
- `Q` Charge Details: empty throughout

### Sample limitation (important)

This file is **filtered to a single counterparty and contains zero outflows**. It
proves the column layout but **cannot** supply the vocabulary for cash
withdrawals, supplier transfers, fees, refunds or reversals.

Per decision D-2, classification is therefore built as **configurable rules with
no seeded guesses**: imported outflows start `UNMAPPED` and are mapped by hand
until an unfiltered statement containing withdrawals is added to the repo. No
invented keyword list ships.

---

## 5. Payments, dedup and identity

- `payments` (Firestore) is written by `PaymentRepository`; `DeduplicationService`
  and a `uniqueCode` guard against re-import.
- The TBC DBI API integration (separate, `payment-service/.../bank/tbc/`) already
  defines a canonical bank-row model and store:
  - `BankTransaction` record: `date, direction (CREDIT|DEBIT), amount, currency,
    accountNumber, counterparty, counterpartyInn, counterpartyAccount,
    description, reference`
  - Firestore collections `bankTransactions` and `bankSyncState`
    (`BankTransactionRepository`).
- **This is the natural canonical home for Excel-imported statement rows too**, so
  the audit layer sees one bank-row shape regardless of whether a row arrived by
  file upload or by API sync. Reused rather than duplicated.
- Counterparty identity is the Georgian TIN (`Partner's Tax Code`, column L),
  normalised by `TinValidator`.

---

## 6. RS.ge documentation flow

- `waybill-service/.../service/rsge/RsGeSoapClient.java` — SOAP client.
- `WaybillService` fetches sales and purchase waybills separately
  (`fetchWaybillsFromRsGe`, `fetchPurchaseWaybillsFromRsGe`).
- `WaybillDto`: `id, waybillId, type (SALE|PURCHASE), customerId (=BUYER_TIN),
  customerName, buyerTin, buyerName, sellerTin, sellerName, date, amount,
  status, isAfterCutoff, goods[]`.
- `WaybillGoodDto`: `name, quantity, unit, unitPrice, totalPrice`.
- `InventoryMovementService.getProductMovements(startDate, endDate)` already
  produces the per-product documented movement series the inventory flow needs;
  `AuditControlService` consumes it over HTTP. **Reused, not re-implemented.**

---

## 7. Frontend conventions

- Routes are declared in `src/router.tsx` with `lazyRouteComponent` code
  splitting; nav lives in `src/components/layout/app-shell.tsx` (`navItems`).
- Existing routes: `/`, `/waybills`, `/payments`, `/settings`, `/product-sales`,
  `/audit-control`, `/product-categories`.
- Labels are English apart from one Georgian nav entry. Dark mode is supported
  via Tailwind `dark:` classes.
- Per decision D-3 the new audit UI is **English**, with Georgian source values
  (partner names, descriptions) rendered verbatim and never translated.

---

## 8. What this means for the build

1. **Import outflows first.** Extend `ExcelProcessingService` to read column D and
   mirror every statement row into `bankTransactions`. Without this the Cash flow
   is empty and the other 90% of the ticket is decoration.
2. **Add a mapping layer, not an accounting layer.** Source collections
   (`payments`, `waybills`, `bankTransactions`) are read-only to the audit module.
   Mappings, splits, overrides, check evidence and the change log are new
   collections.
3. **Reuse** `WriteOffCalculator`, `InventoryMovementService` and
   `DualLedgerService` rather than re-deriving inventory, movements or the
   doc-vs-real value position.
4. **Build the 8 UX variants as presentation over one canonical payload**, so the
   data scope is provably identical across all of them.
5. **Note on the audit actor:** the API has no authentication. The logged actor is
   a self-declared operator name (decision D-4), which is a recorded claim, not an
   authenticated identity. This limitation is deliberate and must not be presented
   as stronger than it is.
