# TBC Bank Integration — Camora Benchmark Analysis & Migration Plan

> Pre-development deliverable for the TBC real-time banking integration ticket.
> Status: **awaiting product-owner review** — no code has been migrated yet.
> Benchmark: `C:\Users\Boris\Dell\Projects\APPS\Camora\camora_erp` · Target: this repo.

---

## 1. Headline finding — the ticket's mental model needs one correction

**There is no OAuth "one-time token" in Camora's TBC integration, and the API is not JSON.**

Camora talks to **TBC DBI** ("Direct Bank Integration", internally branded `mygemini`) — a **SOAP/XML** service at `https://secdbi.tbconline.ge/dbi/dbiService`, secured by two stacked mechanisms **on every request**:

1. **Mutual TLS** with a client **PKCS12 (.pfx) certificate** issued by TBC per legal entity.
2. **WS-Security UsernameToken** (DBI username + password + fresh nonce) inside each SOAP header.

Nothing is captured, stored, or refreshed between calls — no bearer token, no session. The *one-time* element the ticket refers to is TBC's **mandatory password-change handshake**: before first use (and whenever TBC returns fault `CREDENTIALS_MUST_BE_CHANGED`), the client must send a `ChangePassword` SOAP call whose WS-Security **nonce carries a Digipass/OTP code**. Camora implements this as an operator-triggered endpoint (`POST /bank-analysis/tbc/password-change`), holds the new password in a `volatile` in-memory override (lost on restart), and instructs the operator to persist it to the secret store before redeploy.

(The JSON/OAuth pattern the ticket describes *does* exist in Camora — but for **BOG**, not TBC: `BogBusinessOnlineClient` is OAuth2 client-credentials with a cached bearer token. The two must not be conflated.)

The parsing task is therefore **SOAP/XML parsing**, not JSON. Camora uses schema-tolerant DOM parsing (XXE-hardened) probing multiple candidate tag names per field.

## 2. Camora benchmark — architectural map

### 2.1 Client
`backend/.../module/bankanalysis/TbcDbiClient.java` (~554 lines) — hand-rolled SOAP over `java.net.http.HttpClient`, no JAX-WS/generated stubs.

- `buildSslContext()` (131–142): PKCS12 keystore from file path **or** base64 env; cert password unlocks keystore + key.
- `buildAccountMovementsEnvelope()` (144–196): WS-Security header (username, plaintext password, base64-random-UUID nonce); body carries pager, accountNumber, currency, periodFrom/To.
- `postSoap()` (94–129): POST with `SOAPAction: http://www.mygemini.com/schemas/mygemini/GetAccountMovements` (or `.../ChangePassword`).
- `getAccountMovements()` (51–76): pages from index 0, pageSize min(config, 700); stops when a page returns fewer rows than pageSize; hard ceiling 1000 pages.
- `changePassword(otp, newPassword, override)` (78–92): OTP goes in the nonce; success sets the in-memory `runtimePassword`.
- Errors: `TbcDbiException` with typed codes incl. `PASSWORD_CHANGE_REQUIRED`, `TBC_DBI_PAGE_LIMIT_EXCEEDED`. **No retry/backoff on TBC** (BOG has it; TBC doesn't).

### 2.2 Scheduler (continuous sync)
- `@EnableScheduling` on the application class; exactly **one** scheduled job:
  `SupplierDebtService.scheduledSourceSync()` — `@Scheduled(fixedDelay 1h, initialDelay 60s)`, gated by `camora.supplier-debt.scheduled-sync-enabled`.
- Each cycle fans out three sources in parallel (RSGE waybills, BOG, TBC); TBC via `sourceLedgerStore.syncBank(TBC, from, to, tbcDbiClient::getAccountMovements)`.
- **Watermark/dedup lives in `SourceLedgerStore`, not the client.** Persistence is **flat JSON files** (`~/.camora/bank-ledger-tbc.json`), not a database. Incremental window = newest stored date − 3-day overlap → today; dedup by **window replacement** (bank rows carry no time-of-day, so replacing the whole overlap window preserves duplicate-but-legit same-day payments). Min 120s between syncs; crash-safe atomic writes; corrupt-file quarantine; 60-min Caffeine cache on top.

### 2.3 Parsing & mapping
`toTransaction()` maps each XML movement node into the internal record `BankTransaction` (the bank-agnostic seam shared with BOG):
- **amount** — probes amount/sum/entryAmount/transactionAmount/operationAmount/creditAmount/debitAmount; sign preserved (negative = reversal); zero dropped.
- **direction** — debitCredit/entryType/movementType → CREDIT (income) / DEBIT (expense).
- **date** — docDate/date/operationDate/valueDate.
- **counterparty tax code (receiver ID)** — partnerTaxCode/partnerInn/counterpartyTaxCode/counterpartyInn/taxCode — the key supplier-matching field (TIN-first matching downstream).
- plus counterparty name, account, description, reference; currency/account fall back to config.
Wire-format ground truth: `TBC_API_CALLS.postman_collection.json`, `postman/tbc-dbi-*`, `docs/TBC_DBI_INTEGRATION_GUIDE.md` in the Camora repo.

### 2.4 Camora configuration (prefix `camora.tbc-dbi`, bound in `CamoraProperties`)
| Env var | Default | Purpose |
|---|---|---|
| `CAMORA_TBC_DBI_ENABLED` | false | master switch |
| `CAMORA_TBC_DBI_ENDPOINT` | `https://secdbi.tbconline.ge/dbi/dbiService` | SOAP endpoint |
| `CAMORA_TBC_DBI_USERNAME` | — | DBI username |
| `CAMORA_TBC_DBI_PASSWORD` | — | DBI password |
| `CAMORA_TBC_DBI_CERTIFICATE_PATH` | — | .pfx path (or…) |
| `CAMORA_TBC_DBI_CERTIFICATE_BASE64` | — | …inline cert |
| `CAMORA_TBC_DBI_CERTIFICATE_PASSWORD` | — | cert password |
| `CAMORA_TBC_DBI_ACCOUNT_NUMBER` | — | GE… IBAN |
| `CAMORA_TBC_DBI_CURRENCY` | GEL | |
| `CAMORA_TBC_DBI_PAGE_SIZE` | 700 (cap 700) | |
| `CAMORA_TBC_DBI_TIMEOUT_SECONDS` | 120 | |
| `CAMORA_TBC_DBI_LARGE_CREDIT_THRESHOLD` | 1000.00 | analysis flag |
Sync-side knobs (`camora.supplier-debt.*`): fixed delay 1h, initial delay 60s, overlap days 3, min interval 120s, cache TTL 60m.
(Do **not** confuse with `camora.parsers.tbc` — that's the legacy XLSX spreadsheet parser, unrelated to the API.)

## 3. Tasty ERP target — where it lands

**payment-service is the unambiguous home**, and it was pre-wired for this:
- `PaymentServiceApplication.java` already has `@EnableScheduling // For future Banking API scheduled sync`.
- `application.yml` already binds a `bank-api.tbc.*` block from `TBC_BANK_*` env vars plus `BANK_SYNC_INTERVAL` (default 60 min).
- `"bank-api"` is already a whitelisted payment source in `PaymentReconciliationService`.

Key structural differences vs Camora:

| Dimension | Camora | Tasty | Consequence |
|---|---|---|---|
| Persistence | flat JSON ledger files | **Firestore** `payments` collection | `SourceLedgerStore` cannot be copied as-is; replace with Firestore-backed store or map straight into `payments` |
| Dedup | window replacement over ledger file | doc ID = `uniqueCode` (`date\|amountCents\|customerId\|balanceCents`) | need a uniqueCode strategy for API rows (see risk R4) |
| Money direction | both incomes & expenses (supplier debt) | `payments` = incoming customer payments feeding debt rollup | expenses need a home (see Q2) |
| Counterparty identity | TIN-first matching (`partnerTaxCode`) | canonical TIN via `TinValidator.canonicalId()` | good news: DBI provides the TIN — direct join is possible |
| HTTP | java.net.http | `RestTemplate` (but SOAP client is transport-agnostic anyway) | port the hand-rolled SOAP client mostly verbatim |
| Config | Spring `@ConfigurationProperties` + .env | same pattern (`.env` via `spring.config.import`) | clean fit |
| Cutoff | org opening date 2025-01-01 | `isAfterCutoff` — only payments after **2025-04-29** count toward debt | sync start date must be decided (Q5) |

Routing needs **no Caddyfile change** — `/api/payments*` already forwards to payment-service; new endpoints (manual sync-now, sync status, password-change/OTP) live under that subtree. Amounts: BigDecimal in logic, persisted as double, rounded via `AmountUtils.round()` (existing convention). Dates: `DateUtils` already parses Georgian bank `DD/MM/YYYY`.

## 4. Risk assessment

- **R1 — Credentials are per-legal-entity.** Camora's cert (`Shps verapani.pfx`) and DBI username belong to Camora's company. Tasty needs its **own TBC DBI contract**: .pfx certificate, DBI username/password, and a Digipass device, issued by TBC for Tasty's account. Without them the flow can be built but not verified end-to-end.
- **R2 — Env stubs are the wrong shape.** Tasty's existing `TBC_BANK_CLIENT_ID`/`TBC_BANK_CLIENT_SECRET` anticipate OAuth; DBI needs username/password/cert triplet instead. The `.env` key set must be corrected (proposal in §5).
- **R3 — Password lifecycle is operational, not just code.** The mandatory first-use password change (OTP via Digipass), the volatile in-memory override, and the "persist new password to secret store before restart" runbook step must all be reproduced, plus a way for the operator to submit the OTP.
- **R4 — uniqueCode collision risk.** Tasty's dedup key includes the running statement `balance`. If DBI movements don't reliably expose a running balance, same-day/same-amount/same-customer rows collide. Mitigation: include the DBI movement reference/document ID in the uniqueCode for `source="tbc"` rows (keeps Excel-imported rows untouched).
- **R5 — Double-ingestion with Excel.** Bank-API rows and manually imported Excel rows for the same period will not share uniqueCodes (different balance/row provenance), risking double-counted payments. Needs an explicit policy (Q3).
- **R6 — Schema-tolerant parser hides the real contract.** Camora probes many candidate tag names; the actual mygemini field names must be validated against the Postman collection / a live response before trusting any single mapping.
- **R7 — No auth on service endpoints.** Tasty has no Spring Security; a password-change/OTP endpoint and sync triggers would be reachable by anyone who can reach the service. At minimum keep these off public Caddy routes or add a shared-secret header.
- **R8 — Income attribution requires TIN.** Debt rollup joins strictly on canonical TIN. If an incoming payment's `partnerTaxCode` is missing/foreign, the row saves but silently drops out of debt aggregation — needs a visible "unmatched" bucket rather than silent exclusion.

## 5. Proposed `.env` keys (blank, for product owner to populate)

Replace the OAuth-shaped stubs with the DBI set (final naming subject to Q1 answer):

```
# --- TBC DBI (Direct Bank Integration) ---
TBC_DBI_ENABLED=false
TBC_DBI_ENDPOINT=https://secdbi.tbconline.ge/dbi/dbiService
TBC_DBI_USERNAME=
TBC_DBI_PASSWORD=
TBC_DBI_CERTIFICATE_PATH=
TBC_DBI_CERTIFICATE_BASE64=
TBC_DBI_CERTIFICATE_PASSWORD=
TBC_DBI_ACCOUNT_NUMBER=
TBC_DBI_CURRENCY=GEL
TBC_DBI_PAGE_SIZE=700
TBC_DBI_TIMEOUT_SECONDS=120
# --- sync ---
BANK_SYNC_INTERVAL=60          # minutes (already existed)
BANK_SYNC_OVERLAP_DAYS=3
BANK_SYNC_START_DATE=          # see Q5
```

## 6. Open questions for the product owner (blocking)

1. **Q1 — Auth flow confirmation.** The benchmark's TBC auth is SOAP + mTLS cert + per-request WS-Security, with a one-time Digipass/OTP password-change handshake — not an OAuth token. Confirm this is the flow to replicate (and that "one-time token" meant the OTP password-change), or point me at a different TBC product (e.g. TBC Open Banking REST) if that's the actual intent.
2. **Q2 — Scope of data.** Tasty's `payments` collection models incoming customer payments for the debt rollup. Should the sync (a) ingest only CREDIT rows into `payments`, (b) also persist DEBIT rows (supplier payments/expenses) into a new collection (e.g. `bankTransactions`) for cash-flow tracking, or (c) full Camora-style both-directions analysis?
3. **Q3 — Excel coexistence.** Once API sync is live, does Excel import remain active for the same TBC account (dedup policy needed), or does API replace Excel for TBC going forward?
4. **Q4 — Credentials.** Does Tasty's company already have a TBC DBI contract (cert + DBI user + Digipass)? If not, that request to TBC should start now — it gates end-to-end verification.
5. **Q5 — Sync start date.** From which date should the initial backfill run (cutoff 2025-04-29? account opening? today)?
6. **Q6 — Workflow tracking.** Which tracker holds this ticket (Linear? GitHub Issues? other) and what's the issue ID, so statuses (Todo → In Progress → Code Review → QA → Done) can be updated automatically?

## 6.1 Product-owner decisions (2026-07-13)

- **Q1 → Replicate TBC DBI** exactly as benchmarked ("one-time token" = the Digipass/OTP password-change handshake).
- **Q2 → Credits + debits, two stores**: CREDIT rows with valid TINs → `payments`; ALL rows → new `bankTransactions` collection.
- **Q3 → API replaces Excel for TBC** after cutover; Excel stays for BOG/other.
- **Q6 → Linear** is the tracker (authorization pending; issue ID to be provided).
- Q4 (DBI credentials for Tasty's entity) and Q5 (backfill start date) remain open — defaults: backfill starts day after `PAYMENT_CUTOFF_DATE` (2025-04-30), overridable via `BANK_SYNC_START_DATE`.

## 6.2 Implementation record (2026-07-13)

Shipped in `payment-service`, package `ge.tastyerp.payment.bank[.tbc]`:

| File | Role |
|---|---|
| `bank/BankApiProperties.java` | `@ConfigurationProperties("bank-api")` — DBI creds + sync knobs |
| `bank/tbc/TbcDbiClient.java` | Near-verbatim port of the benchmark SOAP client (mTLS, WS-Security, paging, OTP password-change) |
| `bank/tbc/TbcDbiException.java`, `bank/tbc/BankTransaction.java` | Ported support types |
| `bank/tbc/TbcMovementMapper.java` | Movement → `PaymentDto` (credits w/ valid TIN) + `bankTransactions` doc (all rows) |
| `bank/tbc/BankTransactionRepository.java` | Firestore: `bankTransactions` window replacement + `bankSyncState` watermark |
| `bank/tbc/TbcSyncService.java` | `@Scheduled` fixed-delay sync (BANK_SYNC_INTERVAL min, initial 1 min), overlap window, throttle, unmatched-credit counting, `debtService.invalidate()` |
| `bank/tbc/TbcBankController.java` | `POST /api/payments/bank/tbc/sync`, `GET .../status`, `POST .../password-change` |

Config: `application.yml` `bank-api.*` block rewritten to DBI shape; `.env` + `.env.example` carry the blank `TBC_DBI_*` / `BANK_SYNC_*` keys (§5). Tests: 12 new (client envelope/parsing/password-change + mapper), full payment-service suite 68/68 green.

Design notes:
- **uniqueCode for API rows** = `date|amountCents|tin|ref<DBI documentKey>` (content-hash fallback) — the bank reference takes the balance slot since DBI provides no running balance (risk R4). ⚠ `DeduplicationService`'s reconstruct-based analysis assumes balance-based codes; do **not** run `/deduplicate/remove` across API-era rows without updating it first.
- Credits without a valid Georgian TIN are stored in `bankTransactions` and counted as `lastUnmatchedCredits` in `bankSyncState/tbc` (risk R8) — never silently dropped from the books, but they do not join the debt rollup.
- Window replacement (delete `source=tbc, date ≥ windowFrom`, reinsert) reproduces the benchmark's dedup semantics for `bankTransactions`; `payments` dedups by uniqueCode against `getAllUniqueCodesAfterCutoff()`.

**Runtime verification (2026-07-13):** payment-service booted locally against the live Firestore project; `GET /api/payments/bank/tbc/status` → 200 with config + empty `bankSyncState` (real Firestore round-trip); `POST .../sync` → 400 `TASTY_ERR_400` "TBC DBI integration is disabled…"; `POST .../password-change` → 400 with the same guard message. Error mapping added in `TbcSyncService`: client `IllegalState/IllegalArgument` → `ValidationException` (400), `TbcDbiException` → `ExternalServiceException` (502).

**NOT yet exercised (blocked on credentials):** the live TBC handshake — mTLS connection, OTP password-change against the bank, and a real movements fetch. Remaining before production: populate `TBC_DBI_*` values (needs Tasty's own TBC DBI contract — cert, username, Digipass), run the password-change handshake, verify first backfill against a real statement, flip `TBC_DBI_ENABLED=true`.

## 7. Implementation plan (after sign-off)

1. **Port `TbcDbiClient`** into `payment-service` (`ge.tastyerp.payment.bank.tbc`): SSL context builder, WS-Security envelopes, movements paging, password-change with OTP-in-nonce, typed exceptions. Near-verbatim from benchmark.
2. **Config**: `@ConfigurationProperties("bank-api.tbc")` matching §5 keys; validation on enable.
3. **Sync service + scheduler**: `TbcSyncService` with `@Scheduled(fixedDelayString="${bank-api.sync-interval-minutes:60}m"-equivalent, initialDelay 60s)`, guarded by enabled flag; overlap-window incremental fetch with Firestore-backed watermark; min-interval throttle.
4. **Mapper**: XML movement → `BankTransaction` → `PaymentDto` (canonical TIN via `TinValidator`, dates via `DateUtils`, amounts via `AmountUtils`); uniqueCode strategy per R4; `isAfterCutoff` set; `debtService.invalidate()` after writes; unmatched-TIN bucket per R8.
5. **Endpoints** under `/api/payments/bank/tbc/`: `POST sync` (manual trigger), `GET status`, `POST password-change` (OTP). Thin controllers + `ApiResponse` per house rules.
6. **Tests**: port `TbcDbiClientTest` + `TbcDbiClientPasswordChangeTest` patterns; mapper unit tests against Postman-collection sample payloads; parity with `DebtServiceParityTest` conventions.
7. **Verification**: compile + tests; with live creds — password-change handshake, first backfill, scheduled incremental run, spot-check mapped rows against a real statement.
