# Tasty ERP - Complete Development & Deployment Guide

> **A complete guide for junior developers on building and deploying a Spring Boot + React ERP system from scratch**

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture Deep Dive](#architecture-deep-dive)
3. [Technology Stack](#technology-stack)
4. [Building the Application](#building-the-application)
5. [Development Problems & Solutions](#development-problems--solutions)
6. [Deployment Process](#deployment-process)
7. [Deployment Problems & Solutions](#deployment-problems--solutions)
8. [Common Patterns & Best Practices](#common-patterns--best-practices)
9. [Troubleshooting Guide](#troubleshooting-guide)

---

## Project Overview

### What is Tasty ERP?

Tasty ERP is a **Meat Distribution ERP System** built for the Georgian market. It manages:

- **Sales Waybills** from RS.ge (Georgian Revenue Service)
- **Bank Statement Processing** (TBC Bank, Bank of Georgia)
- **Customer Debt Tracking** with visual warnings
- **Payment Reconciliation** with deduplication
- **VAT Calculations** for tax reporting

### Why Was It Built?

This is a **complete rebuild** of a legacy React/Express.js application that had:
- 135KB monolithic components
- Hardcoded configuration
- No proper architecture
- Performance issues
- Maintenance nightmares

### What Makes This Special?

- ✅ **Clean Architecture** - Controller → Service → Repository pattern
- ✅ **Microservices** - 4 independent services
- ✅ **No Hardcoding** - Everything via environment variables
- ✅ **Firebase Firestore** - Scalable NoSQL database
- ✅ **React Query** - Smart caching and data synchronization
- ✅ **Docker** - Containerized deployment
- ✅ **CI/CD** - Automated deployment via GitHub Actions

---

## Architecture Deep Dive

### System Architecture Diagram

```
┌─────────────────────────────────────────────┐
│              Caddy (Reverse Proxy)          │
│            :80 → :443 (HTTPS)               │
└─────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│           Spring Cloud Gateway              │
│              (:3005)                        │
│   - Routing                                 │
│   - Security (JWT)                          │
│   - Rate Limiting                           │
└─────────────────────────────────────────────┘
          │           │           │
          ▼           ▼           ▼
┌───────────┐ ┌───────────┐ ┌───────────┐
│ Waybill   │ │ Payment   │ │ Config    │
│ Service   │ │ Service   │ │ Service   │
│ :8081     │ │ :8082     │ │ :8888     │
└───────────┘ └───────────┘ └───────────┘
          │           │           │
          └───────────┴───────────┘
                      │
                      ▼
              Firebase Firestore
```

### Microservices Breakdown

#### 1. **API Gateway** (Port 3005)
**Purpose**: Entry point for all requests

**Responsibilities**:
- Route requests to correct service
- JWT token validation
- Rate limiting (100 req/min default)
- CORS configuration
- Load balancing

**Key Files**:
- `application.yml` - Gateway routes configuration
- Route example: `/api/payments/**` → `http://payment-service:8082`

---

#### 2. **Waybill Service** (Port 8081)
**Purpose**: Manage sales and purchase waybills from RS.ge

**Responsibilities**:
- Fetch waybills from RS.ge SOAP API
- Calculate VAT summaries
- **NO STORAGE** - waybills are NOT saved to Firebase (changed Dec 2024)
- Serve fresh data on-demand

**Why No Storage?**
- Old approach: Stored 4,951+ waybills → exceeded Firebase free tier (50k reads/day)
- New approach: Fetch from RS.ge on-demand → only aggregate data stored

**Key Endpoints**:
```
POST /api/waybills/fetch
GET  /api/waybills/sales/all
GET  /api/waybills/vat
```

**Key Files**:
- `WaybillService.java` - Business logic
- `WaybillRepository.java` - Firebase operations
- `SoapClient.java` - RS.ge integration

---

#### 3. **Payment Service** (Port 8082)
**Purpose**: Handle bank statements and payment reconciliation

**Responsibilities**:
- Excel file upload (TBC/BOG formats)
- Payment deduplication
- Manual cash payment tracking
- Payment status calculation (yellow/red warnings)
- Customer debt aggregation

**Excel Processing Flow**:
```
1. User uploads Excel file
2. Parse rows (columns A=Date, E=Amount, F=Balance, L=CustomerID)
3. Build uniqueCode: date|amountCents|customerId|balanceCents
4. Check duplicates against Firebase
5. Save new payments in batches (100 rows/batch)
6. Trigger aggregation job
```

**Critical Formula - UniqueCode**:
```java
// THIS IS CRITICAL FOR DEDUPLICATION
String uniqueCode = date + "|" + amountCents + "|" + customerId + "|" + balanceCents;

// Example: "2025-05-13|141000|404851255|232246"
```

**Why Include Balance?**
- Same customer can pay same amount multiple times per day
- Balance after transaction is unique
- Distinguishes legitimate separate payments

**Key Endpoints**:
```
POST /api/payments/excel/upload
GET  /api/payments/status           (NEW - color warnings)
GET  /api/payments/customer/{id}
DELETE /api/payments/bank
```

**Key Files**:
- `ExcelProcessingService.java` - Excel parsing logic
- `PaymentReconciliationService.java` - Deduplication
- `PaymentStatusService.java` - Color indicator calculation
- `PaymentRepository.java` - Firebase CRUD

---

#### 4. **Config Service** (Port 8888)
**Purpose**: Centralized configuration management

**Responsibilities**:
- Store system settings (cutoff dates)
- Manage initial customer debts
- Provide configuration to other services

**Key Firebase Documents**:
```
config/
├── system_settings
│   ├── CUTOFF_DATE: "2025-04-29"
│   └── PAYMENT_CUTOFF_DATE: "2025-04-29"
└── initial_debts
    └── {customerId}: {debt, name, date}
```

**Why Cutoff Date?**
- Debt calculation starts AFTER cutoff
- Waybills after 2025-04-29 count towards debt
- Payments after 2025-04-29 reduce debt

---

### Data Flow Examples

#### Example 1: User Uploads Bank Statement

```
1. User selects Excel file + bank (TBC/BOG)
2. Frontend: POST /api/payments/excel/upload
3. API Gateway → Payment Service
4. Payment Service:
   a. Parse Excel (Apache POI)
   b. For each row:
      - Build uniqueCode
      - Check if exists in Firebase
      - Save if new
   c. Batch save (100 rows at a time)
   d. Trigger aggregation job
5. Return summary: {added: 28, duplicates: 0, skipped: 1}
```

#### Example 2: Calculate Customer Debt

```
Formula: currentDebt = startingDebt + totalSales - totalPayments

Example for Customer 404851255:
- startingDebt: 0 (from config/initial_debts)
- totalSales: 67,614.00 (from RS.ge waybills)
- totalPayments: 346,631.00 (from Firebase payments)
- currentDebt: 0 + 67,614 - 346,631 = -279,017.00 (overpaid!)
```

#### Example 3: Payment Status Colors

```
Backend calculates:
1. Find last payment date per customer
2. Calculate: daysSince = today - lastPaymentDate
3. Assign color:
   - daysSince < 14: "none" (no color)
   - 14 ≤ daysSince < 30: "yellow" ⚠️
   - daysSince ≥ 30: "red" 🚨
4. Return: Map<customerId, PaymentStatus>

Frontend applies:
- Yellow row: bg-yellow-50/50
- Red row: bg-red-50/50
```

---

## Technology Stack

### Backend (Java/Spring Boot)

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Language** | Java | 17+ | Core language |
| **Framework** | Spring Boot | 3.2.x | Web framework |
| **Gateway** | Spring Cloud Gateway | - | API routing |
| **Database** | Firebase Firestore | - | NoSQL database |
| **SOAP Client** | Apache CXF | - | RS.ge integration |
| **Excel Parser** | Apache POI | - | Excel file processing |
| **Build Tool** | Maven | 3.9+ | Dependency management |
| **Containerization** | Docker | - | Deployment |

### Frontend (React/TypeScript)

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Language** | TypeScript | - | Type safety |
| **Framework** | React | 18+ | UI framework |
| **Routing** | TanStack Router | - | Client-side routing |
| **State Management** | TanStack Query | - | Server state & caching |
| **Styling** | Tailwind CSS | - | Utility-first CSS |
| **UI Components** | Shadcn/ui | - | Pre-built components |
| **Build Tool** | Vite | - | Fast bundler |

### Infrastructure

| Component | Technology | Purpose |
|-----------|-----------|---------|
| **Cloud** | Google Cloud Platform (GCP) | VM hosting |
| **Reverse Proxy** | Caddy | HTTPS, automatic SSL |
| **CI/CD** | GitHub Actions | Automated deployment |
| **Secrets** | GCP Secret Manager | Secure credentials |
| **Registry** | Google Artifact Registry | Docker images |

---

## Building the Application

### Prerequisites Checklist

```bash
# 1. Install Java 17+
java -version
# Output: openjdk version "17.0.x"

# 2. Install Maven 3.9+
mvn -version
# Output: Apache Maven 3.9.x

# 3. Install Node.js 18+
node --version
# Output: v18.x.x

# 4. Install Docker
docker --version
# Output: Docker version 24.x.x

# 5. Install Git
git --version
# Output: git version 2.x.x
```

### Step 1: Clone Repository

```bash
git clone https://github.com/BorisSolomonia/Tasty_new.git
cd Tasty_new
```

### Step 2: Project Structure

```
tasty-erp/
├── api-gateway/              # Spring Cloud Gateway
│   ├── src/main/
│   │   ├── java/
│   │   └── resources/
│   │       └── application.yml
│   └── pom.xml
│
├── waybill-service/          # Waybill management
│   ├── src/main/
│   │   ├── java/
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   └── repository/
│   │   └── resources/
│   └── pom.xml
│
├── payment-service/          # Payment reconciliation
│   ├── src/main/
│   │   ├── java/
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   └── repository/
│   │   └── resources/
│   └── pom.xml
│
├── config-service/           # Configuration management
│   ├── src/main/
│   └── pom.xml
│
├── common/                   # Shared DTOs and utilities
│   ├── src/main/java/
│   │   ├── dto/
│   │   └── util/
│   └── pom.xml
│
├── tasty-erp-frontend/       # React application
│   ├── src/
│   │   ├── pages/
│   │   ├── components/
│   │   ├── lib/
│   │   └── types/
│   ├── package.json
│   └── vite.config.ts
│
├── docker-compose.yml        # Production config
├── docker-compose.dev.yml    # Development config
├── Caddyfile.production      # Caddy config
├── .github/workflows/
│   └── deploy.yml            # CI/CD pipeline
└── pom.xml                   # Parent POM
```

### Step 3: Environment Variables Setup

Create `.env` file (NEVER commit this!):

```bash
# Firebase
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_CREDENTIALS_PATH=/secrets/firebase-sa.json

# RS.ge SOAP API
SOAP_ENDPOINT=https://services.rs.ge/WayBillService/WayBillService.asmx
SOAP_SU=username:seller_id
SOAP_SP=password

# Service Ports
API_GATEWAY_PORT=3005
WAYBILL_SERVICE_PORT=8081
PAYMENT_SERVICE_PORT=8082
CONFIG_SERVICE_PORT=8888

# Business Logic
CUTOFF_DATE=2025-04-29
PAYMENT_CUTOFF_DATE=2025-04-29

# CORS
FRONTEND_URL=http://localhost:3000

# Gateway Rate Limiting
RATE_LIMIT_CAPACITY=200
RATE_LIMIT_TOKENS=100
```

### Step 4: Build Backend

```bash
# Build all services
mvn clean install

# Output:
# [INFO] BUILD SUCCESS
# [INFO] Total time: 21.980 s

# Build specific service
mvn clean install -pl payment-service -am

# Skip tests (faster)
mvn clean install -DskipTests
```

### Step 5: Build Frontend

```bash
cd tasty-erp-frontend

# Install dependencies
npm install

# Build for production
npm run build

# Output:
# ✓ 2582 modules transformed.
# ✓ built in 5.93s

# Run development server
npm run dev
```

### Step 6: Run Locally with Docker

```bash
# Development mode
docker-compose -f docker-compose.dev.yml up

# Production mode
docker-compose up

# Check running containers
docker ps

# View logs
docker logs payment-service
docker logs api-gateway
```

### Step 7: Verify Services

```bash
# Check gateway
curl http://localhost:3005/actuator/health

# Check payment service
curl http://localhost:8082/actuator/health

# Check API endpoint
curl http://localhost:3005/api/payments/stats
```

---

## Development Problems & Solutions

### Problem 1: Duplicate Payments (8,525.00 Discrepancy)

**Issue**: Customer 404851255 showed 43,789.60 but should show 35,264.60

**Investigation**:
```bash
# Excel file had 28 payments totaling 35,264.60
# But app showed 43,789.60
# Difference: 8,525.00
```

**Root Cause Discovery**:
1. Initially thought: Excel parsing wrong
2. Checked Excel manually: Correct (28 rows, sum = 35,264.60)
3. Checked Firebase: Found manual cash payment of 8,525.00
4. **Real issue**: Total = bank payments (35,264.60) + manual cash (8,525.00) = 43,789.60

**Solution**: This was CORRECT! User misunderstood that total includes both bank and manual payments.

**Lesson**: Always verify assumptions before coding. Ask clarifying questions!

---

### Problem 2: Same-Day Same-Amount Payments Skipped

**Issue**: Customer 405135946 had 244 payments, but 14 were skipped as "duplicates"

**Example**:
```
Date: 2025-05-13
Amount: 1410.0
Payment 1: Balance after = 2322.46
Payment 2: Balance after = 6773.46

UniqueCode (WITHOUT balance): "2025-05-13|141000|405135946"
Result: Both payments have SAME code → Second marked as duplicate ❌
```

**Root Cause**:
```java
// OLD CODE (WRONG)
String uniqueCode = date + "|" + amountCents + "|" + customerId;
// Same date + same amount + same customer = duplicate detection fails!
```

**Evolution of Solution**:

**Attempt 1**: Remove balance from uniqueCode
- **Why**: Overlapping bank statements show different balances for same transaction
- **Problem**: Created new issue - legitimate same-day payments marked as duplicates

**Attempt 2** (FINAL): Include balance in uniqueCode
```java
// NEW CODE (CORRECT)
String uniqueCode = date + "|" + amountCents + "|" + customerId + "|" + balanceCents;
// Example: "2025-05-13|141000|405135946|232246"
```

**Why This Works**:
- Balance after transaction is UNIQUE
- Even if same customer pays same amount twice in one day, balance differs
- For true duplicates (same statement uploaded twice), balance will match

**Files Changed**:
- `UniqueCodeGenerator.java` - Added balance back
- `PaymentRepository.java` - Updated reconstructUniqueCode()
- `DeduplicationService.java` - Updated to use full format

**Verification**:
```bash
# Before fix: 230 unique codes (14 duplicates)
# After fix: 244 unique codes (0 duplicates)
```

**Lesson**: Balance is NOT just extra data - it's a critical part of payment identity!

---

### Problem 3: Date Parsing - Georgian Bank Format

**Issue**: Dates in DD/MM/YYYY format (Georgian standard) were parsed as MM/DD/YYYY (US format)

**Example**:
```
Excel: "13/05/2025" (May 13, 2025)
Parsed as: May 13, 2025 ✅ (lucky - same result)

Excel: "05/13/2025" (May 13, 2025)
Parsed as: Invalid date ❌ (month 13 doesn't exist)
```

**Root Cause**:
```java
// OLD CODE
LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
// Assumes MM/DD/YYYY (US format)
```

**Solution**:
```java
// NEW CODE
private static final Pattern DMY_PATTERN = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");

Matcher dmyMatcher = DMY_PATTERN.matcher(dateStr);
if (dmyMatcher.matches()) {
    int day = Integer.parseInt(dmyMatcher.group(1));    // Parse DAY first
    int month = Integer.parseInt(dmyMatcher.group(2));  // Then MONTH
    int year = Integer.parseInt(dmyMatcher.group(3));
    return LocalDate.of(year, month, day);
}
```

**Files Changed**:
- `DateUtils.java` - Fixed parsing order

**Testing**:
```
Input: "13/05/2025"
Old: Parse error or wrong date
New: May 13, 2025 ✅
```

**Lesson**: Never assume date formats! Always clarify with users and handle multiple formats.

---

### Problem 4: Total Debt Calculation Wrong

**Issue**: Total debt on payments page didn't match sum of individual customer debts

**Example**:
```
Customer A: debt = 1000 (100 starting + 500 sales - 400 payments)
Customer B: debt = 500 (50 starting + 300 sales - 150 payments)

Expected Total: 1500
Actual Total: 800 (wrong!)
```

**Root Cause**:
```javascript
// WRONG - payments-page.tsx
const totalOutstanding = totalSales - totalPayments;
// Missing starting debts!
```

**Solution**:
```javascript
// CORRECT
let totalStartingDebts = 0;

initialDebts.forEach(debt => {
  if (!excludedCustomers.has(debt.customerId)) {
    totalStartingDebts += debt.debt;
  }
});

const totalOutstanding = totalStartingDebts + totalSales - totalPayments;
```

**Formula Comparison**:
```
Individual customer debt: startingDebt + sales - payments ✅
Total debt (OLD):        0 + totalSales - totalPayments ❌
Total debt (NEW):        totalStartingDebts + totalSales - totalPayments ✅
```

**Files Changed**:
- `payments-page.tsx` - Fixed includedTotals calculation

**Lesson**: Aggregation formula must match individual calculation!

---

### Problem 5: Waybills Page Shows Different Total Than Payments Page

**Issue**: Same data, different totals on two pages

**Root Cause 1**: Waybills page calculated independently
```javascript
// waybills-page.tsx (OLD)
const netDebt = totalSales - totalPayments;
// Missing starting debts!
```

**Root Cause 2**: Waybills page didn't respect excluded customers
```javascript
// Payments page: Sums only checked customers
// Waybills page: Summed ALL customers
```

**Evolution of Solutions**:

**Attempt 1**: Cache total from payments page
```javascript
// payments-page.tsx
React.useEffect(() => {
  queryClient.setQueryData(['totalOutstanding'], totalOutstanding)
}, [totalOutstanding])

// waybills-page.tsx
const netDebt = queryClient.getQueryData(['totalOutstanding']) ?? fallback
```
**Problem**: Only works if user visits payments page first!

**Attempt 2** (BETTER): Fetch initial debts on waybills page
```javascript
// waybills-page.tsx
const initialDebtsQuery = useCachedQuery({
  queryKey: ['config', 'initialDebts'],
  // ... same query as payments page
})

const totalStartingDebts = initialDebts.reduce((sum, debt) => sum + debt.debt, 0)
const netDebt = totalStartingDebts + totalSales - totalPayments
```
**Problem**: Still didn't respect excluded customers!

**Attempt 3** (FINAL): Read excluded customers from localStorage
```javascript
// Read excluded customers (same as payments page)
const excludedCustomers = React.useMemo(() => {
  const raw = window.localStorage.getItem('tasty-erp-excluded-customers')
  return new Set(JSON.parse(raw))
}, [])

// Group by customer
const customerSales = new Map()
afterCutoffWaybills.forEach(w => {
  customerSales.set(w.customerId, (customerSales.get(w.customerId) || 0) + w.amount)
})

// Sum only included customers
let totalSales = 0
customerSales.forEach((amount, customerId) => {
  if (!excludedCustomers.has(customerId)) {
    totalSales += amount
  }
})

// Same for payments and starting debts
const netDebt = totalStartingDebts + totalSales - totalPayments
```

**Files Changed**:
- `waybills-page.tsx` - Added initial debts query, excluded customer filtering

**Verification**:
```
Scenario 1: Visit waybills first
Old: Wrong total (missing starting debts)
New: Correct total ✅

Scenario 2: Uncheck customer on payments, go to waybills
Old: Still includes unchecked customer
New: Respects unchecked customer ✅

Scenario 3: Mobile vs Desktop
Old: Different totals (cache dependency)
New: Same total ✅
```

**Lesson**: Pages should be self-sufficient! Don't rely on cache from other pages.

---

### Problem 6: Payment Status Colors - Where to Calculate?

**Requirement**:
- Yellow background: 14-30 days since last payment
- Red background: 30+ days since last payment
- Only for customers with debt > 0

**3 Implementation Options Analyzed**:

**Option 1**: Frontend calculates
```javascript
// payments-page.tsx
const status = customers.map(c => {
  const lastPayment = Math.max(...c.payments.map(p => p.date))
  const daysSince = (today - lastPayment) / (1000 * 60 * 60 * 24)
  const color = daysSince < 14 ? 'none' : daysSince < 30 ? 'yellow' : 'red'
  return { ...c, statusColor: color }
})
```
**Pros**: Simple, no backend change
**Cons**: Business logic in frontend ❌, not reusable

**Option 2**: Backend aggregation (store in Firebase)
```java
// AsyncAggregationService.java
public void aggregateCustomerData() {
  // Calculate and store status in customer_debt_summary
  customerDebtSummary.put("statusColor", calculateColor(lastPaymentDate));
  firestore.collection("customer_debt_summary").document(customerId).set(data);
}
```
**Pros**: Pre-calculated, fast reads
**Cons**: Only updates on aggregation trigger, could be stale

**Option 3** (CHOSEN): Separate backend endpoint
```java
// PaymentStatusService.java
@Service
public class PaymentStatusService {
  public Map<String, PaymentStatusDto> calculatePaymentStatus() {
    // Fetch payments, group by customer, calculate status
    return statusMap;
  }
}

// PaymentController.java
@GetMapping("/status")
public ResponseEntity<ApiResponse<Map<String, PaymentStatusDto>>> getPaymentStatus() {
  return ResponseEntity.ok(ApiResponse.success(paymentStatusService.calculatePaymentStatus()));
}
```

**Why Option 3 Won**:
- ✅ Backend calculates (as requested)
- ✅ Always fresh (calculated on-demand)
- ✅ Clean separation of concerns
- ✅ Easy to cache independently (10 min TTL)
- ✅ Doesn't mess with existing aggregation
- ✅ Reusable for future features

**Implementation Details**:

Backend (`PaymentStatusService.java`):
```java
private static final int WARNING_THRESHOLD_DAYS = 14;
private static final int DANGER_THRESHOLD_DAYS = 30;

public Map<String, PaymentStatusDto> calculatePaymentStatus() {
    // 1. Fetch all payments after cutoff
    List<PaymentDto> payments = paymentRepository.findAll();

    // 2. Filter to authorized sources (TBC, BOG, manual-cash)
    List<PaymentDto> relevantPayments = payments.stream()
        .filter(p -> p.getPaymentDate().isAfter(cutoffDate))
        .filter(this::isAuthorizedPayment)
        .toList();

    // 3. Group by customer, find last payment date
    Map<String, LocalDate> lastPaymentByCustomer = new HashMap<>();
    for (PaymentDto payment : relevantPayments) {
        LocalDate current = lastPaymentByCustomer.get(payment.getCustomerId());
        if (current == null || payment.getPaymentDate().isAfter(current)) {
            lastPaymentByCustomer.put(payment.getCustomerId(), payment.getPaymentDate());
        }
    }

    // 4. Calculate status for each customer
    Map<String, PaymentStatusDto> statusMap = new HashMap<>();
    LocalDate today = LocalDate.now();

    for (Map.Entry<String, LocalDate> entry : lastPaymentByCustomer.entrySet()) {
        int daysSince = (int) ChronoUnit.DAYS.between(entry.getValue(), today);
        String color = determineStatusColor(daysSince);

        statusMap.put(entry.getKey(), PaymentStatusDto.builder()
            .customerId(entry.getKey())
            .lastPaymentDate(entry.getValue())
            .daysSinceLastPayment(daysSince)
            .statusColor(color)
            .build());
    }

    return statusMap;
}

private String determineStatusColor(int days) {
    if (days < WARNING_THRESHOLD_DAYS) return "none";
    if (days < DANGER_THRESHOLD_DAYS) return "yellow";
    return "red";
}
```

Frontend (`payments-page.tsx`):
```typescript
// 1. Fetch status from backend
const paymentStatusQuery = useQuery({
  queryKey: ['payments', 'status'],
  queryFn: () => paymentsApi.getStatus(),
  staleTime: 1000 * 60 * 10, // Cache for 10 minutes
})

const paymentStatus = paymentStatusQuery.data || {}

// 2. Apply colors to table rows
paginatedCustomers.map((customer) => {
  const status = paymentStatus[customer.customerId]

  // Only show color for customers with debt > 0
  const statusColor = customer.currentDebt > 0 && status?.statusColor !== 'none'
    ? status?.statusColor
    : null

  const rowBgClass = statusColor === 'yellow'
    ? 'bg-yellow-50/50 dark:bg-yellow-900/10 hover:bg-yellow-100/50'
    : statusColor === 'red'
    ? 'bg-red-50/50 dark:bg-red-900/10 hover:bg-red-100/50'
    : 'hover:bg-accent/50'

  return <tr className={rowBgClass}>...</tr>
})
```

**Files Created**:
- `PaymentStatusDto.java` - DTO with color field
- `PaymentStatusService.java` - Calculation logic
- Updated `PaymentController.java` - Added /status endpoint
- Updated `payments-page.tsx` - Fetched and applied colors

**Testing**:
```bash
# Test endpoint
curl http://localhost:3005/api/payments/status

# Response:
{
  "success": true,
  "data": {
    "404851255": {
      "customerId": "404851255",
      "lastPaymentDate": "2025-12-19",
      "daysSinceLastPayment": 18,
      "statusColor": "yellow"  # ⚠️ Warning!
    },
    "405135946": {
      "customerId": "405135946",
      "lastPaymentDate": "2026-01-05",
      "daysSinceLastPayment": 1,
      "statusColor": "none"  # ✅ Recent payment
    }
  }
}
```

**Lesson**: For new features, create separate endpoints. Don't shoehorn into existing logic!

---

## Deployment Process

### Production Infrastructure

```
Internet
    ↓
Caddy (:443 HTTPS)
    ↓
API Gateway (:3005)
    ↓
Services (8081, 8082, 8888)
    ↓
Firebase Firestore
```

### Step 1: GCP VM Setup

```bash
# 1. Create VM instance
gcloud compute instances create tasty-erp-vm \
  --zone=us-central1-a \
  --machine-type=e2-medium \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud

# 2. Configure firewall
gcloud compute firewall-rules create allow-http \
  --allow tcp:80

gcloud compute firewall-rules create allow-https \
  --allow tcp:443

# 3. SSH into VM
gcloud compute ssh tasty-erp-vm
```

### Step 2: Install Dependencies on VM

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# Install Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# Verify
docker --version
docker-compose --version
```

### Step 3: Firebase Setup

```bash
# 1. Create Firebase project at console.firebase.google.com
# 2. Enable Firestore Database
# 3. Create service account:
#    - Go to Project Settings → Service Accounts
#    - Generate new private key
#    - Download JSON file

# 4. Upload to GCP Secret Manager
gcloud secrets create firebase-sa --data-file=firebase-sa.json

# 5. Grant VM access to secret
gcloud secrets add-iam-policy-binding firebase-sa \
  --member="serviceAccount:VM_SERVICE_ACCOUNT" \
  --role="roles/secretmanager.secretAccessor"
```

### Step 4: Configure GitHub Actions

Create `.github/workflows/deploy.yml`:

```yaml
name: Deploy to GCP VM

on:
  push:
    branches: [master]
  workflow_dispatch:

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest

    steps:
      # 1. Checkout code
      - uses: actions/checkout@v3

      # 2. Set up Java 17
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      # 3. Build backend with Maven
      - name: Build with Maven
        run: mvn clean package -DskipTests

      # 4. Build Docker images
      - name: Build Docker images
        run: |
          docker build -t gcr.io/${{ secrets.GCP_PROJECT_ID }}/api-gateway:${{ github.sha }} ./api-gateway
          docker build -t gcr.io/${{ secrets.GCP_PROJECT_ID }}/waybill-service:${{ github.sha }} ./waybill-service
          docker build -t gcr.io/${{ secrets.GCP_PROJECT_ID }}/payment-service:${{ github.sha }} ./payment-service
          docker build -t gcr.io/${{ secrets.GCP_PROJECT_ID }}/config-service:${{ github.sha }} ./config-service

      # 5. Authenticate with GCP
      - name: Authenticate to Google Cloud
        uses: google-github-actions/auth@v1
        with:
          credentials_json: ${{ secrets.GCP_SA_KEY }}

      # 6. Configure Docker for GCP
      - name: Configure Docker for GCP
        run: gcloud auth configure-docker

      # 7. Push images to Artifact Registry
      - name: Push Docker images
        run: |
          docker push gcr.io/${{ secrets.GCP_PROJECT_ID }}/api-gateway:${{ github.sha }}
          docker push gcr.io/${{ secrets.GCP_PROJECT_ID }}/waybill-service:${{ github.sha }}
          docker push gcr.io/${{ secrets.GCP_PROJECT_ID }}/payment-service:${{ github.sha }}
          docker push gcr.io/${{ secrets.GCP_PROJECT_ID }}/config-service:${{ github.sha }}

      # 8. Build frontend
      - name: Build Frontend
        run: |
          cd tasty-erp-frontend
          npm install
          npm run build

      # 9. Deploy to VM
      - name: Deploy to GCP VM
        run: |
          gcloud compute scp docker-compose.yml tasty-erp-vm:~/
          gcloud compute scp Caddyfile.production tasty-erp-vm:~/Caddyfile
          gcloud compute ssh tasty-erp-vm --command="
            docker-compose pull &&
            docker-compose up -d
          "
```

### Step 5: Configure Secrets in GitHub

Go to Repository Settings → Secrets → Actions:

```
GCP_PROJECT_ID: your-gcp-project-id
GCP_SA_KEY: <service-account-json>
FIREBASE_PROJECT_ID: your-firebase-project
SOAP_SU: rs-ge-username:seller-id
SOAP_SP: rs-ge-password
```

### Step 6: Deploy

```bash
# Automatic deployment
git add .
git commit -m "Deploy: Add new feature"
git push origin master
# GitHub Actions will automatically deploy!

# Manual deployment
gh workflow run deploy.yml

# Monitor deployment
gh run watch
```

### Step 7: Verify Deployment

```bash
# Check services are running
docker ps

# Output:
# CONTAINER ID   IMAGE                    STATUS
# abc123         api-gateway:latest       Up 2 minutes
# def456         payment-service:latest   Up 2 minutes
# ghi789         waybill-service:latest   Up 2 minutes

# Check logs
docker logs api-gateway
docker logs payment-service

# Test endpoints
curl https://your-domain.com/api/payments/stats
```

---

## Deployment Problems & Solutions

### Problem 1: Caddy Not Starting - ERR_CONNECTION_REFUSED

**Issue**: After deployment, API returned `ERR_CONNECTION_REFUSED`

**Investigation**:
```bash
# Check Caddy status
docker ps | grep caddy
# Output: Caddy container not running!

docker logs caddy
# Output: Waiting for dependencies...
```

**Root Cause**:
```yaml
# docker-compose.production.yml (WRONG)
services:
  caddy:
    depends_on:
      api-gateway:
        condition: service_healthy
      payment-service:
        condition: service_healthy
    # If ANY service fails health check, Caddy won't start!
```

**Problem**:
- Caddy had strict `depends_on` with health checks
- If any backend service was unhealthy, Caddy never started
- Creates circular dependency: backend needs Caddy for routing!

**Solution**:
```yaml
# docker-compose.production.yml (CORRECT)
services:
  caddy:
    depends_on:
      - api-gateway
      # No health check condition!
    restart: unless-stopped
```

**Additional Fix** - Add retries to Caddyfile:
```
# Caddyfile.production
reverse_proxy api-gateway:3005 {
  lb_try_duration 30s      # Keep trying for 30 seconds
  lb_try_interval 2s       # Wait 2 seconds between retries
  health_uri /actuator/health
  health_interval 10s
}
```

**Files Changed**:
- `docker-compose.production.yml` - Removed health check conditions
- `Caddyfile.production` - Added retry configuration
- `deploy.yml` - Increased wait time to 30s

**Lesson**: Don't make reverse proxy depend on backend health. Let it retry!

---

### Problem 2: Firebase Connection Timeout on First Deploy

**Issue**: Services started but couldn't connect to Firebase

**Logs**:
```
ERROR: Failed to connect to Firestore
java.net.SocketTimeoutException: connect timed out
```

**Root Cause**: Firebase credentials not mounted correctly

**Investigation**:
```bash
# Check secret exists
gcloud secrets versions access latest --secret="firebase-sa"
# ✅ Secret exists

# Check volume mount
docker inspect payment-service | grep Mounts
# ❌ No /secrets mount!
```

**Solution**:
```yaml
# docker-compose.production.yml
services:
  payment-service:
    environment:
      FIREBASE_CREDENTIALS_PATH: /secrets/firebase-sa.json
    volumes:
      - /var/secrets/firebase-sa.json:/secrets/firebase-sa.json:ro
    # Readonly mount for security
```

**On VM**:
```bash
# Create secrets directory
sudo mkdir -p /var/secrets

# Fetch from Secret Manager
gcloud secrets versions access latest --secret="firebase-sa" | \
  sudo tee /var/secrets/firebase-sa.json > /dev/null

# Secure permissions
sudo chmod 400 /var/secrets/firebase-sa.json
```

**Lesson**: Always verify volume mounts and file permissions!

---

### Problem 3: CORS Errors After Deployment

**Issue**: Frontend could access backend locally, but not in production

**Browser Console**:
```
Access to XMLHttpRequest at 'https://api.example.com/api/payments'
from origin 'https://example.com' has been blocked by CORS policy
```

**Root Cause**: CORS configuration wrong in API Gateway

**Investigation**:
```yaml
# application.yml (WRONG)
spring:
  cloud:
    gateway:
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins: "http://localhost:3000"
            # Production URL missing!
```

**Solution**:
```yaml
# application.yml (CORRECT)
spring:
  cloud:
    gateway:
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - ${FRONTEND_URL}                    # Environment variable
              - ${ALLOWED_ORIGIN_PATTERN:#{null}}  # Optional second origin
            allowedMethods:
              - GET
              - POST
              - PUT
              - DELETE
              - OPTIONS
            allowedHeaders: "*"
            allowCredentials: true
            maxAge: 3600

# Environment variables
FRONTEND_URL=https://example.com
ALLOWED_ORIGIN_PATTERN=https://*.example.com
```

**Testing**:
```bash
# Test CORS preflight
curl -X OPTIONS https://api.example.com/api/payments \
  -H "Origin: https://example.com" \
  -H "Access-Control-Request-Method: POST" \
  -v

# Should return:
# Access-Control-Allow-Origin: https://example.com
# Access-Control-Allow-Methods: POST, GET, ...
```

**Lesson**: Use environment variables for URLs. Test CORS before deploying!

---

### Problem 4: Services Out of Memory (OOM Killed)

**Issue**: Payment service kept crashing after few hours

**Logs**:
```bash
docker logs payment-service
# Output: (empty - container killed)

dmesg | grep -i kill
# Output: Out of memory: Killed process 1234 (java)
```

**Root Cause**: Default JVM heap size too large for VM

**Investigation**:
```bash
# Check VM memory
free -h
# Total: 4GB
# Used: 3.8GB (uh oh!)

# Check Docker stats
docker stats
# payment-service: 2.1GB memory usage
```

**Solution**: Limit JVM heap size

```yaml
# docker-compose.production.yml
services:
  payment-service:
    environment:
      JAVA_OPTS: >-
        -Xms256m
        -Xmx512m
        -XX:+UseG1GC
        -XX:MaxGCPauseMillis=200
    deploy:
      resources:
        limits:
          memory: 768M
        reservations:
          memory: 256M
```

**Explanation**:
- `-Xms256m`: Initial heap = 256MB
- `-Xmx512m`: Max heap = 512MB
- `UseG1GC`: Better garbage collector
- `limits.memory`: Docker hard limit

**Monitoring**:
```bash
# Watch memory usage
watch -n 2 'docker stats --no-stream | grep payment'

# JVM memory info
docker exec payment-service java -XX:+PrintFlagsFinal -version | grep HeapSize
```

**Lesson**: Always set JVM memory limits based on available VM resources!

---

### Problem 5: GitHub Actions Timeout

**Issue**: Deployment failed with timeout after 60 minutes

**GitHub Actions Log**:
```
Error: The operation was canceled.
The job running on runner GitHub Actions 1 has exceeded the maximum execution time of 360 minutes.
```

**Root Cause**: Maven downloaded internet during build

**Investigation**:
```yaml
# deploy.yml (SLOW)
- name: Build with Maven
  run: mvn clean package
  # Downloads dependencies every time!
```

**Solution**: Cache Maven dependencies

```yaml
# deploy.yml (FAST)
- name: Cache Maven packages
  uses: actions/cache@v3
  with:
    path: ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-

- name: Build with Maven
  run: mvn clean package -DskipTests
  # Uses cached dependencies!
```

**Before**: 45 minutes build time
**After**: 5 minutes build time

**Lesson**: Always cache dependencies in CI/CD pipelines!

---

## Common Patterns & Best Practices

### Pattern 1: Clean Architecture - No Logic in Controllers

**WRONG** ❌:
```java
@RestController
public class PaymentController {

  @PostMapping("/upload")
  public ResponseEntity uploadExcel(MultipartFile file) {
    // ❌ Business logic in controller!
    List<Payment> payments = new ArrayList<>();
    for (Row row : parseExcel(file)) {
      if (row.getAmount() > 0) {
        String code = row.getDate() + "|" + row.getAmount();
        if (!isDuplicate(code)) {
          payments.add(new Payment(row));
        }
      }
    }
    saveAll(payments);
    return ResponseEntity.ok(payments);
  }
}
```

**CORRECT** ✅:
```java
@RestController
@RequiredArgsConstructor
public class PaymentController {

  private final ExcelProcessingService excelProcessingService;

  @PostMapping("/upload")
  public ResponseEntity uploadExcel(MultipartFile file, String bank) {
    // ✅ Controller only delegates to service
    ExcelUploadResponse response = excelProcessingService.processExcelUpload(file, bank);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
```

**Why**:
- Controllers handle HTTP only
- Services contain ALL business logic
- Easy to test services independently
- Easy to reuse service logic

---

### Pattern 2: DTO Layer Between API and Domain

**Structure**:
```
Controller → DTO → Service → Entity → Repository
```

**Example**:
```java
// DTO (API layer)
@Data
public class PaymentDto {
  private String customerId;
  private LocalDate paymentDate;
  private BigDecimal amount;
  private String source;
}

// Entity (Domain layer)
@Entity
public class Payment {
  @Id
  private String id;
  private String uniqueCode;
  private String customerId;
  private LocalDate paymentDate;
  private BigDecimal amount;
  // ... more fields
}

// Service converts between them
public Payment toEntity(PaymentDto dto) {
  return Payment.builder()
    .customerId(dto.getCustomerId())
    .paymentDate(dto.getPaymentDate())
    .amount(dto.getAmount())
    .build();
}
```

**Why**:
- API changes don't affect domain
- Can hide sensitive fields
- Easier versioning

---

### Pattern 3: React Query for Server State

**WRONG** ❌:
```typescript
const [payments, setPayments] = useState([])
const [loading, setLoading] = useState(false)

useEffect(() => {
  setLoading(true)
  fetch('/api/payments')
    .then(res => res.json())
    .then(data => setPayments(data))
    .finally(() => setLoading(false))
}, [])

// Re-fetch on every component mount!
// No caching, no deduplication
```

**CORRECT** ✅:
```typescript
const paymentsQuery = useQuery({
  queryKey: ['payments', 'afterCutoff', startDate],
  queryFn: () => paymentsApi.getAll(startDate),
  staleTime: 1000 * 60 * 15, // Cache for 15 minutes
  gcTime: 1000 * 60 * 30,    // Keep in memory for 30 min
  retry: 1,
})

const payments = paymentsQuery.data || []

// Automatic caching!
// Automatic deduplication!
// Automatic refetching!
```

**Benefits**:
- Automatic caching
- Deduplication (same query = 1 request)
- Background refetching
- Optimistic updates
- Error handling

---

### Pattern 4: Environment Variables for Configuration

**WRONG** ❌:
```java
@Service
public class WaybillService {
  private final String SOAP_ENDPOINT = "https://services.rs.ge/...";
  private final String USERNAME = "admin";
  private final String PASSWORD = "secret123";
  // Hardcoded! Can't change without recompiling!
}
```

**CORRECT** ✅:
```java
@Service
public class WaybillService {

  @Value("${soap.endpoint}")
  private String soapEndpoint;

  @Value("${soap.username}")
  private String username;

  @Value("${soap.password}")
  private String password;
}
```

```yaml
# application.yml
soap:
  endpoint: ${SOAP_ENDPOINT}
  username: ${SOAP_SU}
  password: ${SOAP_SP}
```

```bash
# .env (never commit!)
SOAP_ENDPOINT=https://services.rs.ge/...
SOAP_SU=admin:seller123
SOAP_SP=secretpassword
```

**Why**:
- No secrets in code
- Easy to change per environment (dev/prod)
- 12-factor app principles

---

### Pattern 5: Batch Processing for Performance

**WRONG** ❌:
```java
// Save payments one by one
for (Payment payment : payments) {
  paymentRepository.save(payment);
  // 100 payments = 100 database calls!
}
```

**CORRECT** ✅:
```java
// Batch save
List<Payment> batch = new ArrayList<>();

for (Payment payment : payments) {
  batch.add(payment);

  if (batch.size() >= 100) {
    paymentRepository.saveAll(batch);  // 1 database call for 100 items
    batch.clear();
  }
}

// Save remaining
if (!batch.isEmpty()) {
  paymentRepository.saveAll(batch);
}
```

**Performance**:
- 1000 payments
- One-by-one: ~30 seconds
- Batched (100): ~3 seconds
- **10x faster!**

---

## Troubleshooting Guide

### Issue: Build Fails with "Cannot find symbol"

**Error**:
```
[ERROR] cannot find symbol
  symbol:   class PaymentDto
  location: package ge.tastyerp.common.dto.payment
```

**Cause**: Dependency not built

**Solution**:
```bash
# Build dependencies first
mvn clean install -pl common -am

# Then build service
mvn clean install -pl payment-service
```

---

### Issue: Docker Image Won't Start

**Error**:
```
docker: Error response from daemon: pull access denied
```

**Cause**: Image not pushed to registry

**Solution**:
```bash
# Tag image
docker tag payment-service:latest gcr.io/PROJECT_ID/payment-service:latest

# Push to registry
docker push gcr.io/PROJECT_ID/payment-service:latest
```

---

### Issue: Firebase "Permission Denied"

**Error**:
```
PERMISSION_DENIED: Missing or insufficient permissions
```

**Cause**: Service account lacks Firestore permissions

**Solution**:
```bash
# Grant Firestore permissions
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member="serviceAccount:SERVICE_ACCOUNT@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/datastore.user"
```

---

### Issue: CORS Still Blocking After Fix

**Error**:
```
CORS policy: No 'Access-Control-Allow-Origin' header
```

**Cause**: Browser cached preflight response

**Solution**:
```bash
# 1. Hard refresh browser (Ctrl+Shift+R)

# 2. Clear browser cache completely

# 3. Test with curl (bypasses browser cache)
curl -X POST https://api.example.com/api/payments/upload \
  -H "Origin: https://example.com" \
  -v
```

---

### Issue: Payment Status Colors Not Showing

**Steps to Debug**:

```javascript
// 1. Check if endpoint returns data
fetch('https://api.example.com/api/payments/status')
  .then(r => r.json())
  .then(console.log)

// 2. Check React Query state
console.log('Status query:', paymentStatusQuery)
console.log('Status data:', paymentStatus)

// 3. Check if data reaches component
paginatedCustomers.forEach(c => {
  const status = paymentStatus[c.customerId]
  console.log(c.customerId, status?.statusColor)
})

// 4. Check CSS classes applied
document.querySelectorAll('tr[class*="bg-yellow"]')
```

---

## Appendix: Complete File Reference

### Backend Files

**Controllers** (HTTP handling only):
- `PaymentController.java` - Payment endpoints
- `WaybillController.java` - Waybill endpoints
- `ConfigController.java` - Configuration endpoints

**Services** (ALL business logic):
- `ExcelProcessingService.java` - Excel parsing and validation
- `PaymentReconciliationService.java` - Deduplication logic
- `PaymentStatusService.java` - Color indicator calculation
- `WaybillService.java` - RS.ge integration
- `AsyncAggregationService.java` - Background aggregation

**Repositories** (Data access only):
- `PaymentRepository.java` - Firebase payment CRUD
- `WaybillRepository.java` - Firebase waybill CRUD

**DTOs** (Data transfer):
- `PaymentDto.java`
- `PaymentStatusDto.java`
- `ExcelUploadResponse.java`
- `CustomerPaymentSummary.java`

**Utilities**:
- `UniqueCodeGenerator.java` - Payment deduplication
- `DateUtils.java` - Date parsing (DD/MM/YYYY support)
- `AmountUtils.java` - Currency calculations

### Frontend Files

**Pages**:
- `payments-page.tsx` - Main customer debt page
- `waybills-page.tsx` - Waybill viewer

**API Client**:
- `api-client.ts` - HTTP client with error handling

**Types**:
- `domain.ts` - TypeScript types (Payment, Waybill, etc.)

**Utilities**:
- `use-cached-query.ts` - localStorage + React Query
- `erp-calculations.ts` - Business calculations

### Configuration Files

**Docker**:
- `docker-compose.yml` - Production config
- `docker-compose.dev.yml` - Development config
- `Dockerfile` (each service)

**Caddy**:
- `Caddyfile.production` - Reverse proxy config

**CI/CD**:
- `.github/workflows/deploy.yml` - Automated deployment

**Spring Boot**:
- `application.yml` (each service) - Service configuration

---

## Conclusion

This guide covered:

✅ Complete project architecture
✅ Every technology choice explained
✅ Real problems encountered during development
✅ Step-by-step solutions with code
✅ Deployment from scratch
✅ Troubleshooting common issues

**Key Takeaways**:

1. **Clean Architecture Matters** - Separation of concerns makes debugging 10x easier
2. **Test Assumptions** - The "duplicate payment" was actually correct!
3. **Date Formats Are Evil** - Always clarify DD/MM/YYYY vs MM/DD/YYYY
4. **Cache Carefully** - Don't depend on other pages' cached data
5. **Backend Calculates** - Keep business logic server-side
6. **Environment Variables** - Never hardcode configuration
7. **Batch Operations** - 10x performance improvement
8. **Always Monitor** - Logs, metrics, and alerts save hours

**For Junior Developers**:

- Start with understanding the architecture diagram
- Read error messages carefully - they tell you exactly what's wrong
- When stuck, add logging/console.log to see what's happening
- Test locally before deploying
- Use version control - commit often
- Ask questions - even "obvious" assumptions can be wrong!

**Next Steps**:

- Implement unit tests for services
- Add integration tests for APIs
- Set up monitoring (Prometheus + Grafana)
- Implement backup strategy for Firebase
- Add feature flags for gradual rollouts

Good luck building! 🚀
