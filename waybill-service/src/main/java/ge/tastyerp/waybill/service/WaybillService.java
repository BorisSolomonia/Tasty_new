package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillFetchRequest;
import ge.tastyerp.common.dto.waybill.WaybillFetchResponse;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.dto.waybill.WaybillVatSummaryDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.waybill.repository.WaybillRepository;
import ge.tastyerp.waybill.service.rsge.RsGeSoapClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for waybill management.
 *
 * ALL business logic for waybill operations is here.
 * Controllers only delegate to this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaybillService {

    private static final BigDecimal VAT_RATE = new BigDecimal("0.18");
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.18");

    private final WaybillRepository waybillRepository;
    private final RsGeSoapClient rsGeSoapClient;
    private final WaybillProcessingService processingService;

    @Value("${business.cutoff-date:2025-04-29}")
    private String cutoffDate;

    /**
     * Fetch waybills from RS.ge (NOT stored in Firebase - new architecture).
     * Waybills are always fetched fresh from RS.ge SOAP API.
     */
    public WaybillFetchResponse fetchWaybillsFromRsGe(WaybillFetchRequest request) {
        log.info("Fetching waybills from RS.ge: {} to {}",
                request.getStartDate(), request.getEndDate());

        if (request.getStartDate() != null && request.getEndDate() != null &&
                request.getEndDate().isBefore(request.getStartDate())) {
            throw new ge.tastyerp.common.exception.ValidationException(
                    "dateRange", "endDate must be on or after startDate");
        }

        // Call RS.ge SOAP API
        List<Map<String, Object>> rawWaybills = rsGeSoapClient.getWaybills(
                request.getStartDate(),
                request.getEndDate()
        );
        log.info("RS.ge returned raw sale waybills count={}", rawWaybills.size());

        // Process and normalize waybills
        List<WaybillDto> processed = processingService.processWaybills(rawWaybills, WaybillType.SALE);
        log.info("Processed sale waybills count={} (after status filtering)", processed.size());

        // NEW ARCHITECTURE: Do NOT save to Firebase
        // Waybills are always fetched fresh from RS.ge
        log.debug("Waybills NOT saved to Firebase (new architecture - always fetch fresh)");

        // Count after-cutoff waybills
        long afterCutoffCount = processed.stream()
                .filter(WaybillDto::isAfterCutoff)
                .count();

        String message = String.format("%d waybills fetched from RS.ge. %d after cutoff date.",
                processed.size(), afterCutoffCount);

        return WaybillFetchResponse.builder()
                .success(true)
                .message(message)
                .totalCount(processed.size())
                .afterCutoffCount((int) afterCutoffCount)
                .waybills(processed)
                .build();
    }

    /**
     * Fetch PURCHASE waybills (we are BUYER) from RS.ge (NOT stored in Firebase).
     * Purchase waybills are fetched fresh for VAT calculation only.
     */
    public WaybillFetchResponse fetchPurchaseWaybillsFromRsGe(WaybillFetchRequest request) {
        log.info("Fetching PURCHASE waybills from RS.ge: {} to {}",
                request.getStartDate(), request.getEndDate());

        if (request.getStartDate() != null && request.getEndDate() != null &&
                request.getEndDate().isBefore(request.getStartDate())) {
            throw new ge.tastyerp.common.exception.ValidationException(
                    "dateRange", "endDate must be on or after startDate");
        }

        List<Map<String, Object>> rawWaybills = rsGeSoapClient.getBuyerWaybills(
                request.getStartDate(),
                request.getEndDate()
        );
        log.info("RS.ge returned raw purchase waybills count={}", rawWaybills.size());

        List<WaybillDto> processed = processingService.processWaybills(rawWaybills, WaybillType.PURCHASE);
        log.info("Processed purchase waybills count={} (after status filtering)", processed.size());

        // NEW ARCHITECTURE: Do NOT save to Firebase
        // Purchase waybills are only needed for VAT calculation
        log.debug("Purchase waybills NOT saved to Firebase (new architecture)");

        long afterCutoffCount = processed.stream()
                .filter(WaybillDto::isAfterCutoff)
                .count();

        String message = String.format(
                "%d purchase waybills fetched from RS.ge. %d after cutoff date.",
                processed.size(), afterCutoffCount);

        return WaybillFetchResponse.builder()
                .success(true)
                .message(message)
                .totalCount(processed.size())
                .afterCutoffCount((int) afterCutoffCount)
                .waybills(processed)
                .build();
    }

    /**
     * Get ALL sale waybills from RS.ge for aggregation.
     * Fetches from cutoff date to today.
     * This method is used by payment-service aggregation.
     */
    public List<WaybillDto> getAllSalesWaybills() {
        LocalDate startDate = LocalDate.parse(cutoffDate).plusDays(1); // After cutoff
        LocalDate endDate = LocalDate.now();

        log.info("Fetching ALL sales waybills for aggregation: {} to {}", startDate, endDate);

        try {
            List<Map<String, Object>> rawWaybills = rsGeSoapClient.getWaybills(startDate, endDate);
            log.info("RS.ge returned {} raw sale waybills for aggregation", rawWaybills.size());

            List<WaybillDto> processed = processingService.processWaybills(rawWaybills, WaybillType.SALE);
            log.info("Processed {} sale waybills for aggregation (after status filtering)", processed.size());

            return processed;
        } catch (Exception e) {
            log.error("Error fetching all sales waybills for aggregation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch sales waybills from RS.ge", e);
        }
    }

    /**
     * Get waybills with optional filters.
     * REFACTORED: Now fetches directly from RS.ge instead of Firebase.
     */
    public List<WaybillDto> getWaybills(String customerId, String startDate, String endDate, Boolean afterCutoffOnly, WaybillType type) {
        log.info("Fetching waybills from RS.ge with filters: customerId={}, start={}, end={}, afterCutoff={}, type={}",
                customerId, startDate, endDate, afterCutoffOnly, type);

        LocalDate start = (startDate == null || startDate.isBlank()) ? null : DateUtils.parseDate(startDate);
        LocalDate end = (endDate == null || endDate.isBlank()) ? LocalDate.now() : DateUtils.parseDate(endDate);

        // Default start date logic
        LocalDate cutoff = LocalDate.parse(cutoffDate);
        if (start == null) {
            if (Boolean.TRUE.equals(afterCutoffOnly)) {
                start = cutoff.plusDays(1);
            } else {
                // Default to 30 days ago if no date specified and not strictly after cutoff
                start = LocalDate.now().minusDays(30);
            }
        }

        // Enforce cutoff if requested
        if (Boolean.TRUE.equals(afterCutoffOnly) && start.isBefore(cutoff)) {
            start = cutoff.plusDays(1);
        }

        // Safety: don't fetch if start > end
        if (start.isAfter(end)) {
            return java.util.Collections.emptyList();
        }

        List<Map<String, Object>> rawWaybills;
        if (type == WaybillType.PURCHASE) {
            rawWaybills = rsGeSoapClient.getBuyerWaybills(start, end);
        } else {
            // Default to Sales if type is SALE or null
            rawWaybills = rsGeSoapClient.getWaybills(start, end);
        }

        List<WaybillDto> processed = processingService.processWaybills(rawWaybills, type != null ? type : WaybillType.SALE);

        // Filter in memory
        String normalizedCustomerId = (customerId == null || customerId.isBlank()) ? null : TinValidator.normalize(customerId);

        return processed.stream()
                .filter(w -> {
                    if (normalizedCustomerId == null) return true;
                    if (type == WaybillType.PURCHASE) return normalizedCustomerId.equals(w.getSellerTin());
                    return normalizedCustomerId.equals(w.getBuyerTin());
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get waybill by ID.
     */
    public WaybillDto getWaybillById(String id) {
        return waybillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill", id));
    }

    /**
     * Get waybills by customer ID.
     */
    public List<WaybillDto> getWaybillsByCustomer(String customerId, Boolean afterCutoffOnly) {
        // Reuse the new getWaybills method
        return getWaybills(customerId, null, null, afterCutoffOnly, WaybillType.SALE);
    }

    /**
     * Get total sales for a customer.
     */
    public Map<String, Object> getCustomerTotalSales(String customerId) {
        List<WaybillDto> waybills = getWaybills(customerId, null, null, true, WaybillType.SALE);

        BigDecimal totalSales = waybills.stream()
                .map(WaybillDto::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        result.put("customerId", customerId);
        result.put("totalSales", totalSales);
        result.put("waybillCount", waybills.size());
        result.put("cutoffDate", cutoffDate);

        return result;
    }

    /**
     * Get waybill statistics.
     * REFACTORED: Uses live data from RS.ge (Sales only for performance/relevance)
     */
    public Object getWaybillStatistics() {
        // Fetch ALL sales from cutoff to now
        List<WaybillDto> sales = getAllSalesWaybills();

        long salesAfterCutoffCount = sales.size(); // getAllSalesWaybills fetches after cutoff

        BigDecimal totalSalesAmount = sales.stream()
                .map(WaybillDto::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Group by customer
        Map<String, Long> byCustomer = new HashMap<>();
        sales.forEach(w -> byCustomer.merge(w.getBuyerTin(), 1L, Long::sum));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalWaybills", sales.size()); // Just sales for now
        stats.put("salesWaybills", sales.size());
        stats.put("purchaseWaybills", 0); // Not fetching purchases to save time
        stats.put("afterCutoffSalesWaybills", salesAfterCutoffCount);
        stats.put("afterCutoffPurchaseWaybills", 0);
        stats.put("totalSalesAmount", totalSalesAmount);
        stats.put("totalAmount", totalSalesAmount);
        stats.put("uniqueCustomers", byCustomer.size());
        stats.put("cutoffDate", cutoffDate);

        return stats;
    }

    /**
     * VAT summary using legacy RS.ge rules:
     * - Include all waybills in the period (no confirmed-only filter)
     * - Ignore non-positive amounts when computing VAT base
     * - VAT = gross * 0.18 / 1.18
     */
    public WaybillVatSummaryDto getVatSummary(String startDate, String endDate, Boolean afterCutoffOnly) {
        LocalDate start = (startDate == null || startDate.isBlank()) ? null : DateUtils.parseDate(startDate);
        LocalDate end = (endDate == null || endDate.isBlank()) ? null : DateUtils.parseDate(endDate);

        if (start == null || end == null) {
            log.error("VAT Summary failed: Invalid date range startDate={} endDate={}", startDate, endDate);
            throw new ge.tastyerp.common.exception.ValidationException("dateRange", "startDate and endDate are required (yyyy-MM-dd)");
        }
        if (end.isBefore(start)) {
            log.error("VAT Summary failed: End date before start date");
            throw new ge.tastyerp.common.exception.ValidationException("dateRange", "endDate must be on or after startDate");
        }

        // NEW ARCHITECTURE: Fetch waybills directly from RS.ge SOAP API (NOT from Firebase)
        log.info("Fetching waybills from RS.ge for VAT calculation: {} to {}", start, end);

        List<Map<String, Object>> rawSales;
        List<Map<String, Object>> rawPurchases;

        try {
            rawSales = rsGeSoapClient.getWaybills(start, end);
            log.info("RS.ge returned {} raw sales waybills", rawSales != null ? rawSales.size() : "null");
        } catch (Exception e) {
            log.error("Failed to fetch sales waybills from RS.ge: {}", e.getMessage(), e);
            throw new ge.tastyerp.common.exception.ExternalServiceException("RS.ge", "Failed to fetch sales waybills: " + e.getMessage());
        }

        try {
            rawPurchases = rsGeSoapClient.getBuyerWaybills(start, end);
            log.info("RS.ge returned {} raw purchase waybills", rawPurchases != null ? rawPurchases.size() : "null");
        } catch (Exception e) {
            log.error("Failed to fetch purchase waybills from RS.ge: {}", e.getMessage(), e);
            throw new ge.tastyerp.common.exception.ExternalServiceException("RS.ge", "Failed to fetch purchase waybills: " + e.getMessage());
        }

        List<WaybillDto> sales = processingService.processWaybills(rawSales, WaybillType.SALE);
        List<WaybillDto> purchases = processingService.processWaybills(rawPurchases, WaybillType.PURCHASE);

        // Apply afterCutoffOnly filter if requested
        if (afterCutoffOnly != null && afterCutoffOnly) {
            sales = sales.stream().filter(WaybillDto::isAfterCutoff).collect(java.util.stream.Collectors.toList());
            purchases = purchases.stream().filter(WaybillDto::isAfterCutoff).collect(java.util.stream.Collectors.toList());
        }

        log.info("Fetched from RS.ge for VAT: {} sales, {} purchases", sales.size(), purchases.size());

        BigDecimal soldGross = sumPositiveAmounts(sales);
        BigDecimal purchasedGross = sumPositiveAmounts(purchases);

        log.info("=== VAT CALCULATION DETAILS ===");
        log.info("Sales waybills count: {}", sales.size());
        log.info("Purchase waybills count: {}", purchases.size());
        log.info("Sales gross amount (sum): {}", soldGross);
        log.info("Purchase gross amount (sum): {}", purchasedGross);

        BigDecimal soldVat = vatFromGross(soldGross);
        BigDecimal purchasedVat = vatFromGross(purchasedGross);
        BigDecimal netVat = soldVat.subtract(purchasedVat).setScale(2, RoundingMode.HALF_UP);

        log.info("Sales VAT (18%): {}", soldVat);
        log.info("Purchase VAT (18%): {}", purchasedVat);
        log.info("NET VAT (sales - purchases): {}", netVat);
        log.info("=== END VAT CALCULATION ===");

        long soldPositiveCount = countPositiveAmounts(sales);
        long purchasedPositiveCount = countPositiveAmounts(purchases);

        return WaybillVatSummaryDto.builder()
                .startDate(start)
                .endDate(end)
                .cutoffDate(cutoffDate)
                .soldWaybillCount(sales.size())
                .purchasedWaybillCount(purchases.size())
                .soldPositiveAmountCount(soldPositiveCount)
                .purchasedPositiveAmountCount(purchasedPositiveCount)
                .soldGross(soldGross)
                .purchasedGross(purchasedGross)
                .soldVat(soldVat)
                .purchasedVat(purchasedVat)
                .netVat(netVat)
                .build();
    }

    private BigDecimal sumPositiveAmounts(List<WaybillDto> waybills) {
        if (waybills == null || waybills.isEmpty()) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sum = BigDecimal.ZERO;
        for (WaybillDto wb : waybills) {
            BigDecimal amount = wb != null ? wb.getAmount() : null;
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.add(amount);
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private long countPositiveAmounts(List<WaybillDto> waybills) {
        if (waybills == null || waybills.isEmpty()) return 0;
        long count = 0;
        for (WaybillDto wb : waybills) {
            BigDecimal amount = wb != null ? wb.getAmount() : null;
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                count++;
            }
        }
        return count;
    }

    private BigDecimal vatFromGross(BigDecimal gross) {
        if (gross == null || gross.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return gross.multiply(VAT_RATE).divide(VAT_DIVISOR, 2, RoundingMode.HALF_UP);
    }
}
