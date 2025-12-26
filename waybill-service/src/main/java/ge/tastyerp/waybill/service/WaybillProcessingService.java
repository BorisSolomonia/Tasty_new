package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.util.AmountUtils;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.common.util.TinValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for processing and normalizing waybills from RS.ge.
 *
 * This implements the EXACT waybill processing logic from the legacy application:
 * - Filter out STATUS = -1 or -2 (cancelled/invalid)
 * - Extract BUYER_TIN, BUYER_NAME, FULL_AMOUNT
 * - Normalize dates to YYYY-MM-DD
 * - Mark waybills after cutoff date
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaybillProcessingService {

    @Value("${business.cutoff-date:2025-04-29}")
    private String cutoffDate;

    // Amount field priority list (matching legacy exactly)
    private static final List<String> AMOUNT_FIELDS = Arrays.asList(
            "FULL_AMOUNT", "full_amount", "FullAmount", "fullAmount",
            "TOTAL_AMOUNT", "total_amount", "totalAmount", "TotalAmount",
            "AMOUNT_LARI", "amount_lari", "AmountLari", "amountLari",
            "NET_AMOUNT", "net_amount", "NetAmount", "netAmount",
            "GROSS_AMOUNT", "gross_amount", "GrossAmount", "grossAmount",
            "AMOUNT", "amount", "Amount",
            "SUM", "sum", "Sum",
            "SUMA", "suma", "Suma",
            "VALUE", "value", "Value",
            "VALUE_LARI", "value_lari",
            "PRICE", "price", "Price",
            "TOTAL_PRICE", "total_price",
            "COST", "cost", "Cost",
            "TOTAL_COST", "total_cost"
    );

    /**
     * Process raw waybills from RS.ge into normalized DTOs.
     */
    public List<WaybillDto> processWaybills(List<Map<String, Object>> rawWaybills, WaybillType type) {
        log.info("Processing {} raw waybills", rawWaybills.size());

        List<WaybillDto> processed = new ArrayList<>();
        int skippedByStatus = 0;

        for (Map<String, Object> raw : rawWaybills) {
            // Check status - skip cancelled waybills
            Object statusObj = getField(raw, "STATUS", "status");
            if (statusObj != null) {
                int status = parseStatus(statusObj);
                if (status == -1 || status == -2) {
                    skippedByStatus++;
                    continue;
                }
            }

            WaybillDto dto = mapToDto(raw, type);
            if (dto != null) {
                processed.add(dto);
            }
        }

        log.info("Processed {} waybills, skipped {} by status", processed.size(), skippedByStatus);
        return processed;
    }

    /**
     * Map raw waybill data to DTO.
     */
    private WaybillDto mapToDto(Map<String, Object> raw, WaybillType type) {
        String waybillId = getStringField(raw, "ID", "id");
        if (waybillId == null) {
            waybillId = "wb_" + System.currentTimeMillis() + "_" + Math.random();
        }

        // Extract buyer info (RS.ge BUYER)
        String buyerTin = getStringField(raw, "BUYER_TIN", "buyer_tin", "BuyerTin");
        String buyerName = getStringField(raw, "BUYER_NAME", "buyer_name", "BuyerName");

        if (buyerTin != null) {
            buyerTin = TinValidator.normalize(buyerTin);
        }

        // Seller info (RS.ge SELLER)
        String sellerTin = getStringField(raw, "SELLER_TIN", "seller_tin", "SellerTin");
        String sellerName = getStringField(raw, "SELLER_NAME", "seller_name", "SellerName");

        if (sellerTin != null) {
            sellerTin = TinValidator.normalize(sellerTin);
        }

        // Customer/Counterparty (ERP semantics)
        // SALE: customer is BUYER; PURCHASE: customer is SELLER
        String customerId = type == WaybillType.PURCHASE ? sellerTin : buyerTin;
        String customerName = type == WaybillType.PURCHASE ? sellerName : buyerName;

        // Extract date - RS.ge uses various field names
        Object dateObj = getField(raw, "CREATE_DATE", "create_date", "CreateDate",
                "CREATEDATE", "Date", "DATE", "date",
                "WAYBILL_DATE", "waybill_date", "WaybillDate");
        if (dateObj != null) {
            log.debug("Found date field with value: {} (type: {})", dateObj, dateObj.getClass().getSimpleName());
        } else {
            log.debug("No date field found in waybill. Available keys: {}", raw.keySet());
        }
        LocalDate date = DateUtils.parseDate(dateObj);

        // Check if after cutoff
        boolean isAfterCutoff = false;
        if (date != null) {
            isAfterCutoff = DateUtils.isAfterCutoff(date, cutoffDate);
        }

        // Extract amount
        BigDecimal amount = extractAmount(raw);

        // Extract status
        Object statusObj = getField(raw, "STATUS", "status");
        Integer status = statusObj != null ? parseStatus(statusObj) : null;

        return WaybillDto.builder()
                .waybillId(waybillId)
                .type(type)
                .customerId(customerId)
                .customerName(customerName)
                .buyerTin(buyerTin)
                .buyerName(buyerName)
                .date(date)
                .amount(amount)
                .status(status)
                .isAfterCutoff(isAfterCutoff)
                .sellerTin(sellerTin)
                .sellerName(sellerName)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Extract amount using priority field list.
     */
    private BigDecimal extractAmount(Map<String, Object> raw) {
        for (String field : AMOUNT_FIELDS) {
            Object value = raw.get(field);
            if (value != null) {
                BigDecimal amount = AmountUtils.parseAmount(value);
                if (amount.compareTo(BigDecimal.ZERO) != 0) {
                    return amount;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Get field with multiple possible keys.
     */
    private Object getField(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Get string field with multiple possible keys.
     */
    private String getStringField(Map<String, Object> map, String... keys) {
        Object value = getField(map, keys);
        if (value == null) return null;
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    /**
     * Parse status to integer.
     */
    private int parseStatus(Object statusObj) {
        if (statusObj instanceof Number) {
            return ((Number) statusObj).intValue();
        }
        try {
            return Integer.parseInt(statusObj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
