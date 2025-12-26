# Tasty ERP - Project Analysis Report

## 1. Project Purpose
**Tasty ERP** is a specialized Enterprise Resource Planning system designed for the **Meat Distribution** industry in the Georgian market.
Its primary goals are:
-   **Integration with RS.ge:** Seamlessly fetch waybills (invoices/delivery notes) from the Georgian Revenue Service via SOAP API.
-   **Debt Management:** Accurately calculate customer debts in real-time, handling sales (from RS.ge) and payments (Bank/Cash).
-   **Payment Reconciliation:** Import bank statements (Excel) and reconcile them with customer accounts.
-   **VAT Calculation:** Compute Value Added Tax based on Purchases and Sales.

## 2. Definition of Done (DoD)
Based on the project structure and `CLAUDE.md`, the system is "Done" when:
-   **Microservices Infrastructure:** All services (`api-gateway`, `waybill`, `payment`, `config`) are running and communicating.
-   **RS.ge Integration:** The system successfully authenticates and fetches waybills from RS.ge.
-   **Aggregation Logic:** Customer debts are correctly calculated by aggregating Sales (RS.ge) - Payments (Firestore).
-   **Frontend:** The React dashboard displays live data (Sales, VAT, Debts) without errors.
-   **No Stale Data:** The system adheres to the "No Waybill Storage" policy to stay within Firebase free tier limits.

## 3. Architecture Overview
The project follows a **Microservices Architecture** with a **Clean Architecture** internal design.

### Components
-   **Frontend:** React (Vite, TypeScript, Tailwind, TanStack Query).
-   **Backend:** Spring Boot 3.2+ (Java 17).
-   **Database:** Firebase Firestore (NoSQL).
-   **Infrastructure:** Docker Compose, Caddy (Reverse Proxy).

### Architecture Diagram

```
                                  [ User (Browser) ]
                                          │
                                          ▼
                                   [ Caddy (Rev Proxy) ]
                                          │
                                          ▼
                                  [ API Gateway :3005 ]
                                          │
        ┌───────────────────────┬─────────┴─────────┬───────────────────────┐
        │                       │                   │                       │
        ▼                       ▼                   ▼                       ▼
[ Waybill Service :8081 ] [ Payment Service :8082 ] [ Config Service :8888 ] [ Common Lib ]
        │   │                   │    │    ▲         │
        │   │ (SOAP)            │    │    │ (HTTP)  │
        │   ▼                   │    ▼    └─────────┘
        │ [ RS.ge ]             │ [ Aggregation Service ]
        │                       │    │    │
        │                       │    │    ▼
        │                       │    │  [ Firebase Firestore ]
        │                       │    │    ├── payments
        └───────────────────────┘    │    ├── manual_cash_payments
                                     │    ├── customer_debt_summary (Aggregated)
                                     │    └── config
                                     └─── (Read Waybills from RS.ge via Waybill Service)
```

## 4. Deep Data Analysis & Flow
The project uses a specific **Aggregation Pattern** to handle data efficiency:

1.  **Waybills (Sales):**
    -   **Storage:** NONE. Waybills are *never* saved to Firebase to save read quotas.
    -   **Display:** Fetched on-demand from RS.ge SOAP API when the user views the "Waybills" page or "Dashboard".
    -   **Performance:** Slightly higher latency (SOAP call) traded for zero storage cost and absolute data freshness.

2.  **Payments:**
    -   **Storage:** Persisted in Firestore (`payments` collection).
    -   **Source:** Excel uploads (Bank Statements) or Manual Entry.

3.  **Customer Debts (Aggregation):**
    -   **Storage:** Persisted in `customer_debt_summary`.
    -   **Calculation:** `Starting Debt + Total Sales (RS.ge) - Total Payments (Firestore)`.
    -   **Trigger:** Runs on Excel Upload or Manual Sync.

## 5. Bugs Found & Fixed

### Critical Bug: Empty Dashboard & Statistics
**Issue:**
The `DashboardPage` and Statistics endpoints were calling `WaybillService.getWaybills` and `WaybillService.getWaybillStatistics`. These methods were implemented to query the **WaybillRepository (Firestore)**.
Since the new architecture *does not save waybills to Firestore*, these methods returned empty lists (or stale data), causing the Dashboard to show **0 Sales** and **0 Waybills**.

**Fix:**
Refactored `WaybillService.java`:
1.  **`getWaybills`:** Now fetches directly from **RS.ge SOAP API**. It respects date filters and defaults to the "Cutoff Date" if no date is provided.
2.  **`getWaybillStatistics`:** Now calculates statistics by fetching live sales data from RS.ge (using `getAllSalesWaybills`), ensuring the stats match the actual data.
3.  **Dependency:** Ensured `rsGeSoapClient` is used for these read operations instead of the empty `waybillRepository`.

### Other Observations
-   **Hardcoded Cutoff Date:** The `cutoffDate` is defined as a property (`@Value`) in multiple services. It is recommended to centralize this in `config-service` to avoid inconsistency.
-   **Performance:** Fetching all waybills for the Dashboard on every load is a heavy operation. The current design accepts this trade-off, but caching (Redis or Caffeine) for short durations (e.g., 5-10 mins) is recommended for production scaling.
