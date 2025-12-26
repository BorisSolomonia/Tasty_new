package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.aggregation.CustomerDebtSummaryDto;
import ge.tastyerp.common.dto.payment.CustomerAnalysisDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.payment.PaymentSummaryDto;
import ge.tastyerp.common.dto.payment.WaybillSummaryDto;
import ge.tastyerp.payment.repository.CustomerDebtSummaryRepository;
import ge.tastyerp.payment.repository.ManualCashPaymentRepository;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for customer debt analysis.
 *
 * Business Logic (from legacy project):
 * 1. Get starting debts from Firebase (initial_debts collection)
 * 2. Get sales (waybills) AFTER cutoff date ONLY
 * 3. Get payments AFTER cutoff date ONLY (from all sources)
 * 4. Calculate debt: startingDebt + totalSales - totalPayments
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerAnalysisService {

    private final PaymentRepository paymentRepository;
    private final ManualCashPaymentRepository manualCashPaymentRepository;
    private final CustomerDebtSummaryRepository debtSummaryRepository;
    private final RestTemplate restTemplate;

    @Value("${cutoff.date:2025-04-29}")
    private String cutoffDateString;

    @Value("${waybill.service.url:http://waybill-service:8081}")
    private String waybillServiceUrl;

    @Value("${config.service.url:http://config-service:8888}")
    private String configServiceUrl;

    /**
     * Get customer analysis for ALL customers using AGGREGATED data.
     * This is the NEW ARCHITECTURE method - uses customer_debt_summary collection.
     * Much faster than analyzeAllCustomersDetailed() as it uses pre-aggregated Firebase data.
     *
     * Use this for:
     * - Payments page customer table (no individual transaction lists needed)
     * - Dashboard summaries
     * - Quick customer debt overview
     */
    public List<CustomerAnalysisDto> analyzeAllCustomers() {
        log.info("Analyzing all customers using AGGREGATED data");

        List<CustomerDebtSummaryDto> summaries = debtSummaryRepository.findAll();
        log.info("Found {} customer debt summaries", summaries.size());

        return summaries.stream()
                .map(this::convertSummaryToAnalysis)
                .collect(Collectors.toList());
    }

    /**
     * Get customer analysis for ALL customers with DETAILED lists.
     * This is the LEGACY method - fetches individual waybills and payments.
     * Slower than analyzeAllCustomers() but provides transaction details.
     *
     * Use this for:
     * - Customer details modal (when transaction lists are needed)
     * - Export to Excel with details
     * - Debugging/reconciliation
     *
     * @deprecated Use analyzeAllCustomers() for summary data. Only use this when details are needed.
     */
    @Deprecated
    public List<CustomerAnalysisDto> analyzeAllCustomersDetailed() {
        log.info("Analyzing all customers");

        LocalDate cutoffDate = LocalDate.parse(cutoffDateString);
        LocalDate paymentWindowStart = cutoffDate.plusDays(1); // >= 2025-04-30

        // 1. Get initial debts from config-service
        Map<String, InitialDebtInfo> initialDebts = fetchInitialDebts();

        // 2. Get waybills AFTER cutoff from waybill-service
        Map<String, CustomerSales> customerSales = fetchCustomerSales(cutoffDate);

        // 3. Get payments AFTER cutoff from payment-service (this service)
        Map<String, CustomerPayments> customerPayments = fetchCustomerPayments(paymentWindowStart);

        // 4. Combine all customer IDs
        Set<String> allCustomerIds = new HashSet<>();
        allCustomerIds.addAll(initialDebts.keySet());
        allCustomerIds.addAll(customerSales.keySet());
        allCustomerIds.addAll(customerPayments.keySet());

        // 5. Build analysis for each customer
        return allCustomerIds.stream()
                .map(customerId -> buildCustomerAnalysis(
                        customerId,
                        initialDebts.getOrDefault(customerId, new InitialDebtInfo()),
                        customerSales.getOrDefault(customerId, new CustomerSales()),
                        customerPayments.getOrDefault(customerId, new CustomerPayments())
                ))
                .collect(Collectors.toList());
    }

    /**
     * Get customer analysis for a specific customer.
     */
    public CustomerAnalysisDto analyzeCustomer(String customerId) {
        log.info("Analyzing customer: {}", customerId);

        LocalDate cutoffDate = LocalDate.parse(cutoffDateString);
        LocalDate paymentWindowStart = cutoffDate.plusDays(1);

        // 1. Get initial debt
        InitialDebtInfo initialDebt = fetchInitialDebtForCustomer(customerId);

        // 2. Get sales after cutoff
        CustomerSales sales = fetchSalesForCustomer(customerId, cutoffDate);

        // 3. Get payments after cutoff
        CustomerPayments payments = fetchPaymentsForCustomer(customerId, paymentWindowStart);

        return buildCustomerAnalysis(customerId, initialDebt, sales, payments);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private CustomerAnalysisDto buildCustomerAnalysis(
            String customerId,
            InitialDebtInfo initialDebt,
            CustomerSales sales,
            CustomerPayments payments) {

        // Name resolution priority: waybill > initial debt > customer ID
        String customerName = customerId;
        if (sales.waybills != null && !sales.waybills.isEmpty() && sales.waybills.get(0).getCustomerName() != null) {
            customerName = sales.waybills.get(0).getCustomerName();
        } else if (initialDebt.customerName != null) {
            customerName = initialDebt.customerName;
        }

        // Calculate current debt: startingDebt + totalSales - totalPayments
        BigDecimal currentDebt = initialDebt.amount
                .add(sales.totalSales)
                .subtract(payments.totalPayments);

        return CustomerAnalysisDto.builder()
                .customerId(customerId)
                .customerName(customerName)
                .totalSales(sales.totalSales)
                .totalPayments(payments.totalPayments)
                .totalCashPayments(payments.totalCashPayments)
                .currentDebt(currentDebt)
                .startingDebt(initialDebt.amount)
                .startingDebtDate(initialDebt.date)
                .waybillCount(sales.waybillCount)
                .paymentCount(payments.paymentCount)
                .waybills(sales.waybills)
                .payments(payments.payments)
                .cashPayments(payments.cashPayments)
                .build();
    }

    private Map<String, InitialDebtInfo> fetchInitialDebts() {
        try {
            // Call config-service: GET /api/config/debts
            String url = configServiceUrl + "/api/config/debts";
            log.debug("Fetching initial debts from: {}", url);

            // Response format: {success: true, data: [{customerId, name, debt, date}]}
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.get("data") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> debts = (List<Map<String, Object>>) response.get("data");

                return debts.stream()
                        .collect(Collectors.toMap(
                                debt -> (String) debt.get("customerId"),
                                debt -> new InitialDebtInfo(
                                        (String) debt.get("name"),
                                        new BigDecimal(String.valueOf(debt.get("debt"))),
                                        LocalDate.parse((String) debt.get("date"))
                                )
                        ));
            }
        } catch (Exception e) {
            log.error("Error fetching initial debts: {}", e.getMessage(), e);
        }
        return Collections.emptyMap();
    }

    private InitialDebtInfo fetchInitialDebtForCustomer(String customerId) {
        try {
            String url = configServiceUrl + "/api/config/debts/" + customerId;
            log.debug("Fetching initial debt for customer {} from: {}", customerId, url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.get("data") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> debt = (Map<String, Object>) response.get("data");

                return new InitialDebtInfo(
                        (String) debt.get("name"),
                        new BigDecimal(String.valueOf(debt.get("debt"))),
                        LocalDate.parse((String) debt.get("date"))
                );
            }
        } catch (Exception e) {
            log.warn("No initial debt found for customer {}: {}", customerId, e.getMessage());
        }
        return new InitialDebtInfo();
    }

    private Map<String, CustomerSales> fetchCustomerSales(LocalDate cutoffDate) {
        try {
            // Call waybill-service: GET /api/waybills?afterCutoffOnly=true&type=SALE
            String url = waybillServiceUrl + "/api/waybills?afterCutoffOnly=true&type=SALE";
            log.debug("Fetching sales after cutoff from: {}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.get("data") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> waybills = (List<Map<String, Object>>) response.get("data");

                Map<String, CustomerSales> salesMap = new HashMap<>();

                waybills.forEach(wb -> {
                    String customerId = (String) wb.get("customerId");
                    if (customerId == null) return;

                    CustomerSales sales = salesMap.computeIfAbsent(customerId, k -> new CustomerSales());
                    BigDecimal amount = new BigDecimal(String.valueOf(wb.get("amount")));
                    sales.totalSales = sales.totalSales.add(amount);
                    sales.waybillCount++;

                    WaybillSummaryDto summary = WaybillSummaryDto.builder()
                            .waybillId((String) wb.get("waybillId"))
                            .customerId(customerId)
                            .customerName((String) wb.get("customerName"))
                            .date(LocalDate.parse((String) wb.get("date")))
                            .amount(amount)
                            .isAfterCutoff((Boolean) wb.get("isAfterCutoff"))
                            .build();
                    sales.waybills.add(summary);
                });

                return salesMap;
            }
        } catch (Exception e) {
            log.error("Error fetching customer sales: {}", e.getMessage(), e);
        }
        return Collections.emptyMap();
    }

    private CustomerSales fetchSalesForCustomer(String customerId, LocalDate cutoffDate) {
        try {
            String url = waybillServiceUrl + "/api/waybills/customer/" + customerId + "?afterCutoffOnly=true";
            log.debug("Fetching sales for customer {} from: {}", customerId, url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.get("data") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> waybills = (List<Map<String, Object>>) response.get("data");

                CustomerSales sales = new CustomerSales();

                waybills.forEach(wb -> {
                    BigDecimal amount = new BigDecimal(String.valueOf(wb.get("amount")));
                    sales.totalSales = sales.totalSales.add(amount);
                    sales.waybillCount++;

                    WaybillSummaryDto summary = WaybillSummaryDto.builder()
                            .waybillId((String) wb.get("waybillId"))
                            .customerId(customerId)
                            .customerName((String) wb.get("customerName"))
                            .date(LocalDate.parse((String) wb.get("date")))
                            .amount(amount)
                            .isAfterCutoff((Boolean) wb.get("isAfterCutoff"))
                            .build();
                    sales.waybills.add(summary);
                });

                return sales;
            }
        } catch (Exception e) {
            log.warn("No sales found for customer {}: {}", customerId, e.getMessage());
        }
        return new CustomerSales();
    }

    private Map<String, CustomerPayments> fetchCustomerPayments(LocalDate paymentWindowStart) {
        // Get all payments after payment window start
        List<PaymentDto> allPayments = paymentRepository.findByDateAfter(paymentWindowStart);
        List<PaymentDto> cashPayments = manualCashPaymentRepository.findByDateAfter(paymentWindowStart);

        Map<String, CustomerPayments> paymentsMap = new HashMap<>();

        // Process bank/excel payments
        allPayments.forEach(p -> {
            String customerId = p.getCustomerId();
            if (customerId == null) return;

            CustomerPayments payments = paymentsMap.computeIfAbsent(customerId, k -> new CustomerPayments());
            BigDecimal amount = p.getAmount();
            payments.totalPayments = payments.totalPayments.add(amount);
            payments.paymentCount++;

            PaymentSummaryDto summary = PaymentSummaryDto.builder()
                    .paymentId(p.getId())
                    .customerId(customerId)
                    .payment(amount)
                    .date(p.getPaymentDate())
                    .isAfterCutoff(true)
                    .source(p.getSource())
                    .uniqueCode(p.getUniqueCode())
                    .description(p.getDescription())
                    .balance(p.getBalance())
                    .build();
            payments.payments.add(summary);
        });

        // Process manual cash payments
        cashPayments.forEach(p -> {
            String customerId = p.getCustomerId();
            if (customerId == null) return;

            CustomerPayments payments = paymentsMap.computeIfAbsent(customerId, k -> new CustomerPayments());
            BigDecimal amount = p.getAmount();
            payments.totalPayments = payments.totalPayments.add(amount);
            payments.totalCashPayments = payments.totalCashPayments.add(amount);
            payments.paymentCount++;

            PaymentSummaryDto summary = PaymentSummaryDto.builder()
                    .paymentId(p.getId())
                    .customerId(customerId)
                    .payment(amount)
                    .date(p.getPaymentDate())
                    .isAfterCutoff(true)
                    .source("manual-cash")
                    .description(p.getDescription())
                    .build();
            payments.payments.add(summary);
            payments.cashPayments.add(summary);
        });

        return paymentsMap;
    }

    private CustomerPayments fetchPaymentsForCustomer(String customerId, LocalDate paymentWindowStart) {
        List<PaymentDto> payments = paymentRepository.findByCustomerIdAndDateAfter(customerId, paymentWindowStart);
        List<PaymentDto> cashPayments = manualCashPaymentRepository.findByCustomerIdAndDateAfter(customerId, paymentWindowStart);

        CustomerPayments result = new CustomerPayments();

        // Process bank/excel payments
        payments.forEach(p -> {
            BigDecimal amount = p.getAmount();
            result.totalPayments = result.totalPayments.add(amount);
            result.paymentCount++;

            PaymentSummaryDto summary = PaymentSummaryDto.builder()
                    .paymentId(p.getId())
                    .customerId(customerId)
                    .payment(amount)
                    .date(p.getPaymentDate())
                    .isAfterCutoff(true)
                    .source(p.getSource())
                    .uniqueCode(p.getUniqueCode())
                    .description(p.getDescription())
                    .balance(p.getBalance())
                    .build();
            result.payments.add(summary);
        });

        // Process manual cash payments
        cashPayments.forEach(p -> {
            BigDecimal amount = p.getAmount();
            result.totalPayments = result.totalPayments.add(amount);
            result.totalCashPayments = result.totalCashPayments.add(amount);
            result.paymentCount++;

            PaymentSummaryDto summary = PaymentSummaryDto.builder()
                    .paymentId(p.getId())
                    .customerId(customerId)
                    .payment(amount)
                    .date(p.getPaymentDate())
                    .isAfterCutoff(true)
                    .source("manual-cash")
                    .description(p.getDescription())
                    .build();
            result.payments.add(summary);
            result.cashPayments.add(summary);
        });

        return result;
    }

    /**
     * Convert CustomerDebtSummaryDto to CustomerAnalysisDto.
     * Used by the new aggregated analysis methods.
     */
    private CustomerAnalysisDto convertSummaryToAnalysis(CustomerDebtSummaryDto summary) {
        return CustomerAnalysisDto.builder()
                .customerId(summary.getCustomerId())
                .customerName(summary.getCustomerName())
                .totalSales(summary.getTotalSales())
                .totalPayments(summary.getTotalPayments())
                .totalCashPayments(summary.getTotalCashPayments())
                .currentDebt(summary.getCurrentDebt())
                .startingDebt(summary.getStartingDebt())
                .startingDebtDate(summary.getStartingDebtDate())
                .waybillCount(summary.getSaleCount())
                .paymentCount(summary.getPaymentCount())
                // Note: waybills and payments lists are null in aggregated mode
                // Use analyzeCustomerDetailed() if you need transaction lists
                .waybills(Collections.emptyList())
                .payments(Collections.emptyList())
                .cashPayments(Collections.emptyList())
                .build();
    }

    // ==================== INNER CLASSES ====================

    private static class InitialDebtInfo {
        String customerName;
        BigDecimal amount = BigDecimal.ZERO;
        LocalDate date;

        InitialDebtInfo() {}

        InitialDebtInfo(String customerName, BigDecimal amount, LocalDate date) {
            this.customerName = customerName;
            this.amount = amount;
            this.date = date;
        }
    }

    private static class CustomerSales {
        BigDecimal totalSales = BigDecimal.ZERO;
        Integer waybillCount = 0;
        List<WaybillSummaryDto> waybills = new ArrayList<>();
    }

    private static class CustomerPayments {
        BigDecimal totalPayments = BigDecimal.ZERO;
        BigDecimal totalCashPayments = BigDecimal.ZERO;
        Integer paymentCount = 0;
        List<PaymentSummaryDto> payments = new ArrayList<>();
        List<PaymentSummaryDto> cashPayments = new ArrayList<>();
    }
}
