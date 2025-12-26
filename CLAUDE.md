# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Tasty ERP** is a Meat Distribution ERP system for the Georgian market, built with Spring Boot Microservices architecture and Firebase Firestore as the database. This is a complete rebuild of the legacy React/Express.js application located at `../tasty_sal_app_2`.

## Architecture

### Microservices Structure

```
tasty-erp/
├── api-gateway/          # Spring Cloud Gateway - routing, security, rate limiting
├── waybill-service/      # RS.ge SOAP integration, waybill management
├── payment-service/      # Excel import, payment reconciliation, future Bank API
├── config-service/       # Centralized configuration, initial debts management
└── common/               # Shared DTOs, utilities, exceptions
```

### Clean Architecture Pattern

**CRITICAL: Controllers MUST NOT contain business logic. Only Services contain business logic.**

```
Controller Layer (HTTP handling only)
    │
    ▼
Service Layer (ALL business logic)
    │
    ▼
Repository Layer (Data access only)
    │
    ▼
Firebase Firestore
```

#### Layer Responsibilities

| Layer | Allowed | NOT Allowed |
|-------|---------|-------------|
| **Controller** | Request mapping, validation annotations, response wrapping | Business logic, data transformation, calculations |
| **Service** | Business logic, validation rules, data transformation, orchestration | Direct HTTP concerns, Firebase SDK calls |
| **Repository** | Firebase CRUD operations, query building | Business logic, validation |

### Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Java 17+ |
| Framework | Spring Boot 3.2.x |
| Gateway | Spring Cloud Gateway |
| Database | Firebase Firestore |
| SOAP Client | Apache CXF |
| Excel Parser | Apache POI |
| Build Tool | Maven (multi-module) |
| Containerization | Docker |
| Reverse Proxy | Caddy |

## Build and Development Commands

### Prerequisites
- Java 17+ (OpenJDK recommended)
- Maven 3.9+
- Docker & Docker Compose
- Firebase service account JSON

### Building

```bash
# Build all modules
mvn clean install

# Build specific module
mvn clean install -pl waybill-service -am

# Skip tests
mvn clean install -DskipTests

# Build Docker images
mvn spring-boot:build-image
```

### Running Locally

```bash
# Start all services with Docker Compose
docker-compose -f docker-compose.dev.yml up

# Run individual service
cd waybill-service
mvn spring-boot:run

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Testing

```bash
# Run all tests
mvn test

# Run specific service tests
mvn test -pl payment-service

# Run integration tests
mvn verify -P integration-tests

# Run single test class
mvn test -Dtest=PaymentReconciliationServiceTest
```

## Key Business Logic

### Cutoff Date Logic

**Location**: `config-service` → Firebase `config/system_settings`

```
CUTOFF_DATE = "2025-04-29"
PAYMENT_CUTOFF_DATE = "2025-04-29"
```

- Waybills after 2025-04-29 (April 30th onwards) are used for debt calculation
- Payments after 2025-04-29 are included in payment window
- String comparison: `date > "2025-04-29"` (YYYY-MM-DD format)

### Payment Reconciliation Algorithm

**Location**: `payment-service` → `PaymentReconciliationService`

```java
// uniqueCode = date|amountCents|customerId|balanceCents
String buildUniqueCode(LocalDate date, BigDecimal amount, String customerId, BigDecimal balance) {
    return String.format("%s|%d|%s|%d",
        date.toString(),
        amount.multiply(BigDecimal.valueOf(100)).intValue(),
        normalizeId(customerId),
        balance.multiply(BigDecimal.valueOf(100)).intValue()
    );
}
```

**Deduplication Flow**:
1. Index ALL existing Firebase payments by uniqueCode
2. For each Excel row: validate → build code → check duplicate → save if new
3. On-the-fly Set prevents same-upload duplicates

### Excel Column Mapping

| Column | Index | Purpose |
|--------|-------|---------|
| A | 0 | Date |
| B | 1 | Description |
| E | 4 | Amount |
| F | 5 | Balance |
| L | 11 | Customer ID |

### Customer Payment Calculation (SUMIFS equivalent)

```java
// Sum payments where:
// - customerId matches
// - source is authorized (tbc, bog, excel, bank-api)
// - date >= 2025-04-30
BigDecimal calculateCustomerPayments(String customerId, List<Payment> allPayments)
```

## Data Architecture

### NEW ARCHITECTURE (December 2024): Aggregation Pattern

**Problem**: Storing 4,951+ waybills in Firebase caused quota exhaustion (50k reads/day free tier).

**Solution**: Store ONLY aggregated customer debt summaries in Firebase. Always fetch waybills fresh from RS.ge SOAP API.

#### Architecture Flow

```
┌─────────────────────────────────────────────────────────┐
│                   WAYBILLS (NOT STORED)                 │
│                                                          │
│  RS.ge SOAP API ──────────────► Fetch on-demand        │
│                                                          │
│  Use cases:                                             │
│  - Waybills page (show all transactions)               │
│  - VAT calculation                                      │
│  - Customer debt aggregation                            │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│            AGGREGATED DATA (Firebase Firestore)         │
│                                                          │
│  customer_debt_summary (Document ID = customerId)       │
│  ├── customerId, customerName                           │
│  ├── totalSales, saleCount, lastSaleDate               │
│  ├── totalPayments, paymentCount, lastPaymentDate      │
│  ├── totalCashPayments, cashPaymentCount               │
│  ├── startingDebt, startingDebtDate                    │
│  ├── currentDebt (calculated)                           │
│  └── lastUpdated, updateSource                          │
│                                                          │
│  Formula: currentDebt = startingDebt + totalSales       │
│                        - totalPayments                   │
│                        - totalCashPayments               │
│                                                          │
│  Updated by:                                             │
│  - Excel upload (ExcelProcessingService)                │
│  - Bank API sync (future, hourly)                       │
│  - Manual sync button (future)                          │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│          INDIVIDUAL TRANSACTIONS (Firebase)              │
│                                                          │
│  payments/ - Bank statement payments                    │
│  manual_cash_payments/ - Manual cash entries            │
│  config/initial_debts - Starting balances               │
│                                                          │
│  These are the SOURCES for aggregation                  │
└─────────────────────────────────────────────────────────┘
```

#### Aggregation Process

**Trigger Points**:
1. Excel upload completes successfully
2. Future: Bank API hourly sync
3. Future: Manual "Refresh Debts" button

**Process** (`payment-service` → `AggregationService`):
```java
1. Fetch waybills from RS.ge (via waybill-service) - NOT from Firebase
2. Fetch payments from Firebase (payments collection)
3. Fetch manual cash payments from Firebase
4. Fetch initial debts from config-service
5. Group by customerId
6. Aggregate totals for each customer
7. Calculate currentDebt per customer
8. Compare with existing customer_debt_summary documents
9. Only update Firebase if data changed (change detection)
10. Batch save updated summaries
```

**Change Detection**: Prevents unnecessary Firebase writes by comparing:
- totalSales, saleCount, lastSaleDate
- totalPayments, paymentCount, lastPaymentDate
- totalCashPayments, cashPaymentCount
- startingDebt, currentDebt

Only updates if ANY field changed.

#### Benefits of New Architecture

| Metric | Old Architecture | New Architecture |
|--------|------------------|------------------|
| Firebase Reads (Waybills) | 4,951 per page load | 0 (fetch from RS.ge) |
| Firebase Reads (Customer Debts) | 4,951+ calculations | ~50 aggregated docs |
| Firebase Writes (Waybills) | 4,951 on fetch | 0 |
| Firebase Writes (Aggregation) | N/A | ~50 (only changed) |
| Page Load Speed | Slow (Firebase quota) | Fast (RS.ge direct) |
| Cost | Quota exceeded | Within free tier |

## Firebase Collections

```
firestore/
├── customer_debt_summary/     # NEW: Aggregated customer debt data (Document ID = customerId)
├── payments/                  # Bank statement payments (uniqueCode indexed)
├── manual_cash_payments/      # Manual cash payments
├── customers/                 # Customer master data
├── users/                     # Firebase Auth users
└── config/
    ├── system_settings        # Cutoff dates, batch sizes
    ├── initial_debts          # Starting debt balances (was hardcoded)
    └── bank_api_config        # Future: Banking API settings
```

**REMOVED**: `waybills/` collection - waybills are NO LONGER stored in Firebase

## Environment Variables

**NOTHING IS HARDCODED. All configuration via environment variables.**

### Required Variables

```env
# Firebase
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_CREDENTIALS_PATH=/secrets/firebase-sa.json

# RS.ge SOAP
SOAP_ENDPOINT=https://services.rs.ge/WayBillService/WayBillService.asmx
SOAP_SU=username:seller_id
SOAP_SP=password

# Service Ports
API_GATEWAY_PORT=3005
WAYBILL_SERVICE_PORT=8081
PAYMENT_SERVICE_PORT=8082
CONFIG_SERVICE_PORT=8888

# Business Logic (defaults, overridable via Firebase config)
CUTOFF_DATE=2025-04-29
PAYMENT_CUTOFF_DATE=2025-04-29
BATCH_SIZE=100
MAX_DATE_RANGE_MONTHS=12

# CORS (api-gateway)
FRONTEND_URL=http://localhost:3000
# Optional second allowed origin pattern (only used if set)
ALLOWED_ORIGIN_PATTERN=http://localhost:3000

# Gateway rate limiting (api-gateway)
RATE_LIMIT_CAPACITY=200
RATE_LIMIT_TOKENS=100
RATE_LIMIT_PERIOD_SECONDS=60
```

### Future: Banking API Variables

```env
TBC_BANK_API_ENABLED=false
TBC_BANK_API_URL=
TBC_BANK_CLIENT_ID=
TBC_BANK_CLIENT_SECRET=

BOG_BANK_API_ENABLED=false
BOG_BANK_API_URL=
BOG_BANK_API_KEY=

BANK_SYNC_INTERVAL=60
```

## Service Communication

### Internal (gRPC)

```
payment-service ←→ waybill-service (for customer waybill queries)
```

### External

```
Client → Caddy (443) → api-gateway (3005) → services (8081-8888)
```

## Deployment

### Production (GCP VM)

```bash
# Automatic via GitHub Actions on push to master
# Manual trigger:
gh workflow run deploy.yml
```

### Docker Compose Stack

```
┌─────────────────────────────────────────────┐
│                 Caddy (:80/:443)            │
└─────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│           api-gateway (:3005)               │
└─────────────────────────────────────────────┘
          │           │           │
          ▼           ▼           ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│  waybill    │ │  payment    │ │  config     │
│  (:8081)    │ │  (:8082)    │ │  (:8888)    │
└─────────────┘ └─────────────┘ └─────────────┘
          │           │           │
          └───────────┴───────────┘
                      │
                      ▼
              Firebase Firestore
```

## API Documentation

### Swagger UI
- Gateway: `http://localhost:3005/swagger-ui.html`
- Individual services: `http://localhost:{port}/swagger-ui.html`

### Key Endpoints

#### Waybill Service
```
POST /api/waybills/fetch             # Fetch from RS.ge (NOT saved to Firebase anymore)
POST /api/waybills/purchase/fetch    # Fetch purchase waybills (for VAT only)
GET  /api/waybills/sales/all         # NEW: Get ALL sales for aggregation (from RS.ge)
GET  /api/waybills                   # DEPRECATED: List waybills (uses old Firebase data)
GET  /api/waybills/customer/{tin}    # DEPRECATED: By customer (uses old Firebase data)
GET  /api/waybills/stats             # DEPRECATED: Stats (uses old Firebase data)
GET  /api/waybills/vat               # VAT calculation (fetches fresh from RS.ge)
```

**IMPORTANT**: `/api/waybills/fetch` no longer saves to Firebase. It only returns waybills.

#### Payment Service
```
POST /api/payments/excel/upload      # Upload bank statement + triggers aggregation
POST /api/payments/excel/validate    # Validate only (dry run)
GET  /api/payments                   # List payments
GET  /api/payments/customer/{tin}    # Customer payments (SUMIFS)
POST /api/payments/manual            # Manual cash payment
GET  /api/payments/analysis          # NEW: Customer debts from aggregated data
```

**NEW**: After Excel upload completes, `AggregationService` automatically runs and updates `customer_debt_summary` collection.

#### Config Service
```
GET  /api/config/settings          # All system settings
PUT  /api/config/settings/{key}    # Update setting
GET  /api/config/debts             # All initial debts
POST /api/config/debts             # Add initial debt
PUT  /api/config/debts/{id}        # Update debt
```

## Code Style Guidelines

### Controller Example (NO business logic)

```java
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/excel/upload")
    public ResponseEntity<ExcelUploadResponse> uploadExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bank") String bank) {

        // Controller only delegates to service
        ExcelUploadResponse response = paymentService.processExcelUpload(file, bank);
        return ResponseEntity.ok(response);
    }
}
```

### Service Example (ALL business logic here)

```java
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private final PaymentRepository paymentRepository;
    private final ConfigService configService;

    public ExcelUploadResponse processExcelUpload(MultipartFile file, String bank) {
        // ALL business logic in service
        List<ExcelRow> rows = parseExcel(file);
        Set<String> existingCodes = paymentRepository.getAllUniqueCodes();

        List<Payment> newPayments = rows.stream()
            .filter(row -> row.getAmount().compareTo(BigDecimal.ZERO) > 0)
            .filter(row -> isInPaymentWindow(row.getDate()))
            .filter(row -> !existingCodes.contains(buildUniqueCode(row)))
            .map(this::toPayment)
            .collect(Collectors.toList());

        paymentRepository.saveAll(newPayments);

        return buildResponse(rows, newPayments);
    }
}
```

## Migrating from Legacy

### Key Differences

| Legacy (React/Express) | New (Spring Boot) |
|------------------------|-------------------|
| 135KB monolithic component | 4 focused microservices |
| Hardcoded INITIAL_CUSTOMER_DEBTS | Firebase config/initial_debts |
| localStorage + Firebase | Firebase only |
| XLSX.js library | Apache POI |
| TypeScript SOAP client | Apache CXF |
| No DTOs | Proper DTOs with validation |

### Migration Checklist

- [ ] Run initial debts migration script
- [ ] Verify RS.ge SOAP credentials
- [ ] Test Excel upload with production data
- [ ] Validate cutoff date filtering
- [ ] Test duplicate detection accuracy
- [ ] Performance test with 5000 waybills

## Troubleshooting

### Common Issues

1. **Firebase connection fails**
   - Check `FIREBASE_CREDENTIALS_PATH` points to valid JSON
   - Verify service account has Firestore permissions

2. **RS.ge SOAP timeout**
   - Date range too large → auto-chunks to 72h intervals
   - Increase `RSGE_TIMEOUT` environment variable

3. **Excel parsing errors**
   - Verify column mapping matches expected format
   - Check date format (Excel serial vs string)

4. **Duplicate detection misses**
   - Verify balance column (F) is being read correctly
   - Check uniqueCode format matches exactly

## Security

- JWT validation via Firebase Auth tokens
- Rate limiting at API Gateway (100 req/min default)
- No credentials in code or git
- All secrets via GCP Secret Manager
- HTTPS enforced via Caddy

## Monitoring

- Spring Boot Actuator endpoints: `/actuator/health`, `/actuator/metrics`
- Container health checks configured
- Structured JSON logging for GCP Cloud Logging
