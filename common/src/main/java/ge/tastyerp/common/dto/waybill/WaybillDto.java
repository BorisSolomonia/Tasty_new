package ge.tastyerp.common.dto.waybill;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object for Waybill entities.
 * Represents a waybill from RS.ge system (sale or purchase).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillDto {

    private String id;
    private String waybillId;
    private WaybillType type;     // SALE or PURCHASE

    private String customerId;      // BUYER_TIN
    private String customerName;    // BUYER_NAME
    private String buyerTin;        // RS.ge BUYER_TIN (audit/debug)
    private String buyerName;       // RS.ge BUYER_NAME (audit/debug)

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private BigDecimal amount;
    private Integer status;
    private boolean isAfterCutoff;

    // Original RS.ge fields (for debugging/audit)
    private String sellerTin;
    private String sellerName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<WaybillGoodDto> goods;
}
