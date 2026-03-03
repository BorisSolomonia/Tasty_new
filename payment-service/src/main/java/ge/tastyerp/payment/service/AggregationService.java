package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.aggregation.CustomerDebtSummaryDto;
import ge.tastyerp.common.dto.config.InitialDebtDto;
import ge.tastyerp.common.dto.payment.ManualCashPaymentDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.waybill.CustomerSalesTotalsDto;
import ge.tastyerp.payment.repository.CustomerDebtSummaryRepository;
import ge.tastyerp.payment.repository.ManualCashPaymentRepository;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregation Service - Core business logic for customer debt calculation.
 *
 * This service:
 * 1. Fetches waybills from RS.ge (via waybill-service) - NOT stored in Firebase
 * 2. Fetches payments from Firebase
 * 3. Fetches manual cash payments from Firebase
 * 4. Fetches initial debts from config-service
 * 5. Aggregates by customer
 * 6. Calculates: currentDebt = startingDebt + totalSales - totalPayments - totalCashPayments
 * 7. Detects changes (only update Firebase if data changed)
 * 8. Saves aggregated summaries to customer_debt_summary collection
 *
 * Triggered by:
 * - Excel upload (ExcelProcessingService)
 * - Future: Bank API sync (hourly)
 * - Future: Manual sync button
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregationService {

    private final CustomerDebtSummaryRepository debtSummaryRepository;
    private final PaymentRepository paymentRepository;
    private final ManualCashPaymentRepository cashPaymentRepository;

    @Autowired
    @Qualifier("internalRestTemplate")
    private RestTemplate restTemplate;

    @Value("${waybill.service.url:http://waybill-service:8081}")
    private String waybillServiceUrl;

    @Value("${config.service.url:http://config-service:8888}")
    private String configServiceUrl;

    @Value("${cutoff.date:2025-04-29}")
    private String cutoffDate;

    /**
     * Main aggregation method.
     * Called after Excel upload or manual sync trigger.
     *
     * @param updateSource Source of the update ("excel_upload", "bank_api", "manual_sync")
     * @return Summary of aggregation results
     */
    public AggregationResult aggregateCustomerDebts(String updateSource) {
        log.info("Starting customer debt aggregation. Source: {}", updateSource);

        try {
            // 1. Fetch pre-aggregated sales totals from waybill-service (avoids timeout)
            Map<String, CustomerSalesTotalsDto> salesTotals = fetchCustomerSalesTotals();
            List<PaymentDto> payments = paymentRepository.findAll();
            // Get all manual cash payments (use early date to get all records)
            List<PaymentDto> cashPayments = cashPaymentRepository.findByDateAfter(LocalDate.of(2020, 1, 1));
            List<InitialDebtDto> initialDebts = fetchInitialDebts();

            log.info("Fetched data - Customer sales groups: {}, Payments: {}, Cash Payments: {}, Initial Debts: {}",
                    salesTotals.size(), payments.size(), cashPayments.size(), initialDebts.size());

            // 2. Filter payments by cutoff date (waybills already filtered inside waybill-service)
            List<PaymentDto> filteredPayments = filterPaymentsAfterCutoff(payments);
            List<PaymentDto> filteredCashPayments = filterCashPaymentsAfterCutoff(cashPayments);

            log.info("After cutoff filter - Payments: {}, Cash Payments: {}",
                    filteredPayments.size(), filteredCashPayments.size());

            // 3. Build initial debts map
            Map<String, InitialDebtDto> initialDebtsMap = initialDebts.stream()
                    .collect(Collectors.toMap(InitialDebtDto::getCustomerId, debt -> debt, (a, b) -> a));

            // 4. Get all unique customer IDs (union of all sources)
            Set<String> allCustomerIds = new HashSet<>(salesTotals.keySet());
            filteredPayments.forEach(p -> allCustomerIds.add(p.getCustomerId()));
            filteredCashPayments.forEach(c -> allCustomerIds.add(c.getCustomerId()));
            initialDebtsMap.keySet().forEach(allCustomerIds::add);

            log.info("Found {} unique customers", allCustomerIds.size());

            // 5. Aggregate by customer
            List<CustomerDebtSummaryDto> newSummaries = allCustomerIds.stream()
                    .map(customerId -> aggregateForCustomer(
                            customerId,
                            salesTotals.get(customerId),
                            filteredPayments,
                            filteredCashPayments,
                            initialDebtsMap.get(customerId),
                            updateSource
                    ))
                    .collect(Collectors.toList());

            // 6. Detect changes and update only changed records
            AggregationResult result = detectChangesAndUpdate(newSummaries);

            log.info("Aggregation completed. Updated: {}, Unchanged: {}, New: {}",
                    result.getUpdatedCount(), result.getUnchangedCount(), result.getNewCount());

            return result;

        } catch (Exception e) {
            log.error("Error during customer debt aggregation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to aggregate customer debts", e);
        }
    }

    /**
     * Aggregate data for a single customer.
     */
    private CustomerDebtSummaryDto aggregateForCustomer(
            String customerId,
            CustomerSalesTotalsDto salesTotals,
            List<PaymentDto> allPayments,
            List<PaymentDto> allCashPayments,
            InitialDebtDto initialDebt,
            String updateSource) {

        List<PaymentDto> customerPayments = allPayments.stream()
                .filter(p -> customerId.equals(p.getCustomerId()))
                .collect(Collectors.toList());

        List<PaymentDto> customerCashPayments = allCashPayments.stream()
                .filter(c -> customerId.equals(c.getCustomerId()))
                .collect(Collectors.toList());

        // Get customer name (from pre-aggregated sales totals, payments, or initial debt)
        String customerName = getCustomerName(salesTotals, customerPayments, initialDebt);

        // Sales data comes pre-aggregated from waybill-service
        BigDecimal totalSales = salesTotals != null ? salesTotals.getTotalSales() : BigDecimal.ZERO;
        Integer saleCount = salesTotals != null ? salesTotals.getSaleCount() : 0;
        LocalDate lastSaleDate = salesTotals != null ? salesTotals.getLastSaleDate() : null;

        // Aggregate payments
        BigDecimal totalPayments = customerPayments.stream()
                .map(PaymentDto::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Integer paymentCount = customerPayments.size();

        LocalDate lastPaymentDate = customerPayments.stream()
                .map(PaymentDto::getPaymentDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        // Aggregate cash payments
        BigDecimal totalCashPayments = customerCashPayments.stream()
                .map(PaymentDto::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Integer cashPaymentCount = customerCashPayments.size();

        // Get starting debt
        BigDecimal startingDebt = initialDebt != null ? initialDebt.getDebt() : BigDecimal.ZERO;
        LocalDate startingDebtDate = initialDebt != null ? LocalDate.parse(initialDebt.getDate()) : null;

        // Calculate current debt
        // Formula: currentDebt = startingDebt + totalSales - totalPayments - totalCashPayments
        BigDecimal currentDebt = startingDebt
                .add(totalSales)
                .subtract(totalPayments)
                .subtract(totalCashPayments);

        return CustomerDebtSummaryDto.builder()
                .customerId(customerId)
                .customerName(customerName)
                .totalSales(totalSales)
                .saleCount(saleCount)
                .lastSaleDate(lastSaleDate)
                .totalPayments(totalPayments)
                .paymentCount(paymentCount)
                .lastPaymentDate(lastPaymentDate)
                .totalCashPayments(totalCashPayments)
                .cashPaymentCount(cashPaymentCount)
                .startingDebt(startingDebt)
                .startingDebtDate(startingDebtDate)
                .currentDebt(currentDebt)
                .lastUpdated(LocalDateTime.now())
                .updateSource(updateSource)
                .build();
    }

    /**
     * Detect changes and update only modified records.
     */
    private AggregationResult detectChangesAndUpdate(List<CustomerDebtSummaryDto> newSummaries) {
        int updatedCount = 0;
        int unchangedCount = 0;
        int newCount = 0;

        List<CustomerDebtSummaryDto> toUpdate = new ArrayList<>();

        for (CustomerDebtSummaryDto newSummary : newSummaries) {
            Optional<CustomerDebtSummaryDto> existing = debtSummaryRepository.findById(newSummary.getCustomerId());

            if (existing.isEmpty()) {
                // New customer
                toUpdate.add(newSummary);
                newCount++;
            } else {
                // Check if data changed
                if (hasDataChanged(existing.get(), newSummary)) {
                    toUpdate.add(newSummary);
                    updatedCount++;
                } else {
                    unchangedCount++;
                }
            }
        }

        // Batch update
        if (!toUpdate.isEmpty()) {
            debtSummaryRepository.saveAll(toUpdate);
            log.info("Saved {} customer debt summaries to Firebase", toUpdate.size());
        }

        return AggregationResult.builder()
                .totalCustomers(newSummaries.size())
                .newCount(newCount)
                .updatedCount(updatedCount)
                .unchangedCount(unchangedCount)
                .build();
    }

    /**
     * Check if aggregated data has changed.
     * Only compares financial data, not metadata.
     */
    private boolean hasDataChanged(CustomerDebtSummaryDto existing, CustomerDebtSummaryDto newData) {
        return !Objects.equals(existing.getTotalSales(), newData.getTotalSales())
                || !Objects.equals(existing.getSaleCount(), newData.getSaleCount())
                || !Objects.equals(existing.getLastSaleDate(), newData.getLastSaleDate())
                || !Objects.equals(existing.getTotalPayments(), newData.getTotalPayments())
                || !Objects.equals(existing.getPaymentCount(), newData.getPaymentCount())
                || !Objects.equals(existing.getLastPaymentDate(), newData.getLastPaymentDate())
                || !Objects.equals(existing.getTotalCashPayments(), newData.getTotalCashPayments())
                || !Objects.equals(existing.getCashPaymentCount(), newData.getCashPaymentCount())
                || !Objects.equals(existing.getStartingDebt(), newData.getStartingDebt())
                || !Objects.equals(existing.getCurrentDebt(), newData.getCurrentDebt());
    }

    /**
     * Fetch pre-aggregated customer sales totals from waybill-service.
     * Returns ~50 CustomerSalesTotalsDto objects instead of thousands of raw waybills,
     * avoiding RestTemplate read timeout during the 1-3 minute RS.ge fetch.
     */
    private Map<String, CustomerSalesTotalsDto> fetchCustomerSalesTotals() {
        try {
            String url = waybillServiceUrl + "/api/waybills/sales/customer-totals";
            log.info("Fetching customer sales totals from: {}", url);

            ResponseEntity<List<CustomerSalesTotalsDto>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<CustomerSalesTotalsDto>>() {}
            );

            List<CustomerSalesTotalsDto> list = response.getBody() != null ? response.getBody() : Collections.emptyList();
            log.info("Received sales totals for {} customers", list.size());
            return list.stream().collect(Collectors.toMap(CustomerSalesTotalsDto::getCustomerId, dto -> dto, (a, b) -> a));
        } catch (Exception e) {
            log.error("Error fetching customer sales totals: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch customer sales totals from waybill-service", e);
        }
    }

    /**
     * Fetch initial debts from config-service.
     */
    private List<InitialDebtDto> fetchInitialDebts() {
        try {
            String url = configServiceUrl + "/api/config/debts";

            ResponseEntity<List<InitialDebtDto>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<InitialDebtDto>>() {}
            );

            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Error fetching initial debts: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch initial debts", e);
        }
    }

    /**
     * Filter payments after cutoff date.
     * Date comparison: paymentDate >= cutoffDate + 1 day (2025-04-30)
     */
    private List<PaymentDto> filterPaymentsAfterCutoff(List<PaymentDto> payments) {
        LocalDate cutoff = LocalDate.parse(cutoffDate).plusDays(1); // 2025-04-30
        return payments.stream()
                .filter(p -> p.getPaymentDate() != null && !p.getPaymentDate().isBefore(cutoff))
                .collect(Collectors.toList());
    }

    /**
     * Filter cash payments after cutoff date.
     */
    private List<PaymentDto> filterCashPaymentsAfterCutoff(List<PaymentDto> cashPayments) {
        LocalDate cutoff = LocalDate.parse(cutoffDate).plusDays(1); // 2025-04-30
        return cashPayments.stream()
                .filter(c -> c.getPaymentDate() != null && !c.getPaymentDate().isBefore(cutoff))
                .collect(Collectors.toList());
    }

    /**
     * Get customer name from available data sources.
     */
    private String getCustomerName(CustomerSalesTotalsDto salesTotals, List<PaymentDto> payments, InitialDebtDto initialDebt) {
        // Try pre-aggregated sales totals first
        if (salesTotals != null && salesTotals.getCustomerName() != null) {
            return salesTotals.getCustomerName();
        }

        // Try payments
        if (!payments.isEmpty() && payments.get(0).getCustomerName() != null) {
            return payments.get(0).getCustomerName();
        }

        // Try initial debt
        if (initialDebt != null && initialDebt.getName() != null) {
            return initialDebt.getName();
        }

        return "Unknown Customer";
    }

    /**
     * Result of aggregation operation.
     */
    @lombok.Data
    @lombok.Builder
    public static class AggregationResult {
        private int totalCustomers;
        private int newCount;
        private int updatedCount;
        private int unchangedCount;
    }
}
